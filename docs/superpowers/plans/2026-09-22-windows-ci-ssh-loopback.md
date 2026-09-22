# Windows CI SSH Loopback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `windows-test` CI job that installs OpenSSH Server, sets up SSH loopback to `localhost`, launches a background JVM, and runs `jstall --ssh "ssh localhost" status <pid>` to verify the Windows `ProcessBuilder`-direct SSH fix works end-to-end.

**Architecture:** A new job in `.github/workflows/ci.yml` runs on `windows-latest` on every push. A PowerShell setup step installs `OpenSSH.Server`, generates an ed25519 keypair, wires up `authorized_keys` with correct ACLs, and adds `localhost` to `known_hosts`. A subsequent step launches `Hello.java` as a background JVM and captures its PID. The final step runs `jstall` with `--ssh "ssh -o StrictHostKeyChecking=no localhost"` and asserts it exits 0.

**Tech Stack:** GitHub Actions `windows-latest`, Windows OpenSSH Server (optional feature), PowerShell, Maven, Java (SapMachine 21)

---

### Task 1: Add the `windows-test` job skeleton to ci.yml

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Open ci.yml and append the new job after the existing `test` job**

Add at the end of `.github/workflows/ci.yml`:

```yaml
  windows-test:
    runs-on: windows-latest

    steps:
      - uses: actions/checkout@v5

      - name: Set up JDK 21
        uses: actions/setup-java@v5
        with:
          java-version: 21
          distribution: 'sapmachine'
          cache: maven

      - name: Build package
        run: mvn package -DskipTests
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add windows-test job skeleton"
```

---

### Task 2: Add OpenSSH Server setup step

**Files:**
- Modify: `.github/workflows/ci.yml` (append to `windows-test` job steps)

The tricky parts on Windows OpenSSH:
- `authorized_keys` for administrator accounts must live in `C:\ProgramData\ssh\administrators_authorized_keys`, not the user's `.ssh` folder
- That file requires ACLs restricted to `SYSTEM` and the `Administrators` group only — OpenSSH refuses it otherwise
- `sshd_config` ships with the `AuthorizedKeysFile` line pointing to `administrators_authorized_keys` for admin users, so we just need to populate that file correctly

- [ ] **Step 1: Append the SSH setup step to the `windows-test` job in ci.yml**

```yaml
      - name: Set up OpenSSH Server
        shell: pwsh
        run: |
          # Install OpenSSH Server optional feature
          Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0

          # Start sshd
          Start-Service sshd

          # Generate ed25519 keypair (no passphrase, no prompts)
          $sshDir = "$env:USERPROFILE\.ssh"
          New-Item -ItemType Directory -Force -Path $sshDir | Out-Null
          ssh-keygen -t ed25519 -f "$sshDir\id_ed25519" -N '""'

          # Wire up authorized_keys for admin users
          $adminKeysFile = "C:\ProgramData\ssh\administrators_authorized_keys"
          Copy-Item "$sshDir\id_ed25519.pub" $adminKeysFile

          # Fix ACLs: only SYSTEM and Administrators, no inheritance
          icacls $adminKeysFile /inheritance:r /grant "SYSTEM:(F)" /grant "Administrators:(F)"

          # Pre-populate known_hosts so ssh doesn't prompt
          $hostKey = (ssh-keyscan -H localhost 2>$null)
          Add-Content "$sshDir\known_hosts" $hostKey

          # Smoke-test: verify ssh localhost works
          $result = ssh -o StrictHostKeyChecking=no localhost "echo hello"
          if ($result -ne "hello") {
            Write-Error "SSH loopback smoke test failed: got '$result'"
            exit 1
          }
          Write-Host "SSH loopback OK"
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: set up OpenSSH Server loopback on windows-test job"
```

---

### Task 3: Launch background JVM and run jstall SSH test

**Files:**
- Modify: `.github/workflows/ci.yml` (append to `windows-test` job steps)

The `Hello.java` at the repo root sleeps for 5 minutes — sufficient for the test. We compile it with the runner's `javac`, start it in the background, capture the PID, wait briefly for the JVM to be attach-ready, then run jstall.

Note: On Windows, `jstall` is not a native executable (no GraalVM native build in the current CI). We run it via `java -jar target\jstall.jar`.

- [ ] **Step 1: Append the JVM launch + jstall test step**

```yaml
      - name: Run jstall SSH loopback test
        shell: pwsh
        run: |
          # Compile Hello.java (uses the JDK set up by setup-java above)
          javac Hello.java

          # Start background JVM
          $proc = Start-Process -FilePath "java" -ArgumentList "-cp", ".", "Hello" `
                    -PassThru -WindowStyle Hidden
          $pid = $proc.Id
          Write-Host "Started Hello JVM with PID $pid"

          # Wait for JVM attach mechanism to become ready (Attach Listener starts ~2s in)
          Start-Sleep -Seconds 5

          # Run jstall via the jar with SSH loopback
          java -jar target\jstall.jar --ssh "ssh -o StrictHostKeyChecking=no localhost" status $pid
          $exitCode = $LASTEXITCODE

          # Clean up
          Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue

          if ($exitCode -ne 0) {
            Write-Error "jstall --ssh loopback test failed with exit code $exitCode"
            exit $exitCode
          }
          Write-Host "jstall SSH loopback test passed"
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add jstall SSH loopback integration test on Windows"
```

---

### Task 4: Verify the job passes on CI

- [ ] **Step 1: Push the branch and watch the Actions run**

```bash
git push
```

Open the Actions tab and wait for the `windows-test` job to complete.

- [ ] **Step 2: If the SSH setup step fails**

Common causes:
- `Add-WindowsCapability` may already report the feature as installed — that's fine, it's idempotent
- `sshd_config` may not have `PubkeyAuthentication yes` enabled by default on some images. If the smoke test fails with "Permission denied (publickey)", add this before `Start-Service sshd`:
  ```powershell
  $cfg = "C:\ProgramData\ssh\sshd_config"
  (Get-Content $cfg) -replace '#PubkeyAuthentication yes','PubkeyAuthentication yes' | Set-Content $cfg
  ```

- [ ] **Step 3: If the jstall step fails with JVM attach errors**

Increase the `Start-Sleep` from 5 to 10 seconds. The Attach Listener in Windows JVMs can be slower to start than on Linux.

- [ ] **Step 4: If the job passes, update the `--ssh` option description to drop the Windows caveat**

In `Main.java:57`, change:

```java
@Option(names = {"-s", "--ssh"}, description = "Execution command prefix for running commands on a remote host via SSH (e.g., 'ssh user@host'), only Linux/Mac support on remote", prevents = {"--cf", "--file"})
```

to:

```java
@Option(names = {"-s", "--ssh"}, description = "Execution command prefix for running commands on a remote host via SSH (e.g., 'ssh user@host')", prevents = {"--cf", "--file"})
```

- [ ] **Step 5: Commit the description fix**

```bash
git add src/main/java/me/bechberger/jstall/Main.java
git commit -m "docs: remove Windows caveat from --ssh option now that it is supported"
```
