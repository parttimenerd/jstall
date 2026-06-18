# SAP d-kom Demo Jam — "One-Shot JVM Diagnostics for the Agentic Age"

## 1. Storyboard: "Stay in Flow"

> **Roter Faden:** A developer's most precious resource is *flow state*. Traditional production debugging destroys it — pulling you out of learning, forcing context switches, demanding hours of manual analysis. JStall exists so that AI can reason over deterministic runtime truth and give you your afternoon back.

---

### 0:00 – 0:45 — Act 1: Flow State (The Hook)

**Emotion:** Calm, focused, relatable.

**Visual:** The presenter is watching an online training video (fullscreen, headphones on). The IDE is minimized. Life is good.

**Script:**

> "We all know this feeling. You finally have a quiet afternoon. No meetings. You're learning something new. You're in flow."

A Slack notification slides in from the top-right corner:

> **#prod-incidents** — Michael Scott: *@channel 🔥 47 users unable to reserve seats in sflight-srv. Deadlock suspected. Who can look?*

The presenter sighs, pauses the video, and opens the Fiori travel app.

> "And then reality hits."

Click **Reserve Seat**. Wait 8 seconds. A cryptic error appears:

> `FATAL: Transaction 0x7F2A failed — SeatInventoryService$$EnhancerBySpringCGLIB proxy encountered an unresolvable monitor contention in synchronized region`

> "Monitor contention. CGLIB proxies. Great. In the old world, this means downloading a 5,000-line thread dump, opening it in a text editor, and spending the next two hours manually tracing which thread holds which lock.
>
> My afternoon is gone. My flow state — destroyed.
>
> Unless... I don't do any of that."

---

### 0:45 – 2:45 — Act 2: Delegation (The Agentic Turn)

**Emotion:** Confident pivot — handing work to a capable digital coworker.

**Visual:** Switch to VS Code. Open Copilot chat. Type one sentence.

**Script:**

> "In the agentic age, I don't need to be the debugger. I need to be the *delegator*.
>
> I'll ask my AI coworker to diagnose the live JVM."

**Prompt typed:**
```
Our travel app throws 'monitor contention' errors when users reserve seats. Diagnose the running Java app.
```

The AI calls JStall tools. Tool calls flash in the output panel:
- `jstall_list_jvms` → finds `SFlightApplication`
- `jstall_status` (full, intelligentFilter) → detects 3-node deadlock cycle
- `jstall_reveal_code` → highlights three files, three lock chains

**Script (while AI works):**

> "Here's what's happening under the hood. JStall is a one-shot JVM diagnostic tool built by the SapMachine team. It doesn't produce thousands of noisy thread dump lines. It *deterministically analyzes* the runtime — detecting deadlocks, identifying starved threads, and filtering out framework noise.
>
> The key insight: the AI is not hallucinating from logs. It's reasoning over structured runtime state pulled directly from the live JVM.
>
> And look — it found a circular deadlock across *three* service classes. BookingLedger waits for PaymentGateway, PaymentGateway waits for SeatInventory, SeatInventory waits for BookingLedger. A classic hidden cycle that you'd never spot by reading any single file."

The AI proposes a fix: global lock ordering with `ReentrantLock` + `tryLock()`.

> "The fix is textbook once you *see* the cycle: impose a total lock order. The AI proposes exactly that.
>
> I click Accept."

---

### 2:45 – 3:45 — Act 3: Validation (Closing the Loop)

**Emotion:** Satisfaction. The loop is closed. Flow restored.

**Visual:** Terminal — rebuild & restart. Return to Fiori app.

```bash
# Rebuild and restart
./scripts/demo.sh fix
```

Click **Reserve Seat** again. Instant success. Green checkmark.

**Script:**

> "Rebuild. Redeploy. And... it works.
>
> From Slack notification to verified fix — under four minutes. No thread dump archaeology. No lock-graph whiteboard sessions. No lost afternoon.
>
> My flow state? Still intact."

---

### 3:45 – 5:15 — Act 4: Enterprise Reality (The Privacy Angle)

**Emotion:** Grounded, practical — addressing the skeptic in the audience.

**Visual:** Terminal. Direct CF connection + local LLM.

```bash
cf java jstall sflight-srv --args 'processes'
cf java jstall sflight-srv --args 'ai <PID> --local --no-native --intelligent-filter'
```

A structured AI analysis streams into the terminal — entirely local.

**Script:**

> "Now I know what you're thinking. 'That's great for a demo, but my company won't let me send JVM diagnostics to a cloud LLM.'
>
> Fair point. So JStall also works completely offline.
>
> Here I'm connecting to the same Cloud Foundry JVM, but running the AI analysis entirely on-device — a local Qwen model through llama.cpp. No external APIs. No data leaves the machine.
>
> Same deterministic diagnosis. Same structured output. Zero compliance risk.
>
> Enterprise-grade debugging doesn't have to mean enterprise-grade pain."

---

### 5:15 – 6:00 — Act 5: Vision (The Takeaway)

**Emotion:** Inspirational close. Call back to the opening.

**Visual:** Return briefly to the paused training video. Then back to the IDE with the deadlock visualization highlighted.

**Script:**

> "Here's the thing: I never finished that training video. But I *could* have.
>
> The whole point of JStall is that production debugging shouldn't require a human to become a human thread-dump parser. In the agentic age, AI agents need *structured runtime truth* — not log files, not guesses, not hallucinations.
>
> At the SapMachine team, we're building open-source tools that bridge live JVM state with AI reasoning. So the next time production catches fire, your AI coworker handles the archaeology — and you stay in flow.
>
> Thank you."

---

### Narrative Summary (Roter Faden)

| Beat | Emotion | Thread |
|------|---------|--------|
| Act 1 | Calm → interrupted | Flow is precious; traditional debugging destroys it |
| Act 2 | Confident delegation | AI + deterministic tools = no manual archaeology |
| Act 3 | Satisfaction | Loop closed in minutes, not hours |
| Act 4 | Practical trust | Works offline too — no excuses |
| Act 5 | Inspirational close | You could have finished that video. Next time you will. |

---

## 2. Injected Deadlock — Three-Service Circular Dependency

**Architecture:** Three Spring `@Service` classes with `synchronized` methods that
call into each other, creating a hidden circular lock dependency:

```
BookingLedger → PaymentGateway → SeatInventory → BookingLedger
```

**Files:**
- `cap-sflight/srv/src/main/java/com/sap/cap/sflight/processor/SeatReservationService.java` — orchestrator + `@Scheduled` tasks
- `cap-sflight/srv/src/main/java/com/sap/cap/sflight/processor/BookingLedgerService.java` — holds BookingLedger lock, calls PaymentGateway
- `cap-sflight/srv/src/main/java/com/sap/cap/sflight/processor/PaymentGatewayService.java` — holds PaymentGateway lock, calls SeatInventory
- `cap-sflight/srv/src/main/java/com/sap/cap/sflight/processor/SeatInventoryService.java` — holds SeatInventory lock, calls BookingLedger

**Three concurrent paths that create the cycle:**

| Path | Thread Name | Lock Order |
|------|-------------|------------|
| HTTP request (reserve seat) | `ReserveSeat-HTTP-Worker` | Booking → Payment → Inventory |
| Payment reconciliation | `PaymentReconcile-Worker` | Payment → Inventory |
| Inventory audit | `InventoryAudit-Worker` | Inventory → Booking |

**Why it's subtle:**
- Each service method is simply `synchronized` — no nested `synchronized` blocks visible in any single file
- Lock nesting happens through **cross-service method calls** (service A calls service B's synchronized method while holding its own lock)
- A developer reviewing any single class sees nothing wrong
- The `@Scheduled` annotations look innocuous
- Deadlock is non-deterministic (requires all 3 paths to execute concurrently)

**Why it's easy to explain after diagnosis:**
- JStall's deadlock detection shows the 3-node cycle with exact stack traces
- Each thread clearly shows: "locked X, waiting for Y"
- The fix is obvious once you see the cycle

**UI manifestation:**
- User clicks "Reserve Seat" button in the Fiori travel app
- After ~8-12 seconds, a cryptic error dialog appears:
  > FATAL: Transaction 0x7F2A failed — SeatInventoryService$$EnhancerBySpringCGLIB proxy encountered an unresolvable monitor contention...

**Versions on disk:**
- `.buggy` — `synchronized` cross-service calls with `@Scheduled` background tasks (circular deadlock)
- `.fixed` — Global `ReentrantLock` ordering with `tryLock()` (no cross-service locking)

**Reset to buggy state:**
```bash
./scripts/demo.sh start          # full cycle: kill → swap buggy → build → run
./scripts/demo.sh fix            # full cycle: kill → fix → build → run
./scripts/demo.sh stop           # kill running app
./scripts/demo.sh status         # show code version & app state
./scripts/demo.sh trigger        # trigger deadlock via curl
./scripts/demo.sh check          # pre-flight checklist validation
```

Or use the lower-level script directly:
```bash
./scripts/demo-reset.sh              # swap to buggy version
./scripts/demo-reset.sh --fix        # swap back to fixed version
./scripts/demo-reset.sh --status     # check which version is active
./scripts/demo-reset.sh --run        # full cycle: kill app → swap buggy → rebuild → restart
./scripts/demo-reset.sh --run --fix  # full cycle with fixed version
./scripts/demo-reset.sh --fix --run  # same (flags are order-independent)
```

**To trigger locally (one-command):**
```bash
./scripts/demo.sh start
# Once started, trigger the deadlock:
./scripts/demo.sh trigger
# Or manually:
curl http://localhost:4004/api/reserve-seat
```

**To trigger via UI:**
```bash
./scripts/demo-reset.sh --run
# Open browser:
open http://localhost:4004/travel_processor/webapp/index.html
# Navigate to any Travel → scroll to "My Itinerary" → click "Reserve Seat"
```

**To trigger locally (manual):**
```bash
./scripts/demo-reset.sh
cd cap-sflight
mvn spring-boot:run -Denforcer.skip=true -pl srv
# In another terminal:
curl http://localhost:4004/api/reserve-seat
```

---

## 3. Cloud Foundry Configuration

Already configured in `mta-java.yaml`:

```yaml
- name: sflight-srv
  type: java
  properties:
    JBP_CONFIG_COMPONENTS: "jres: ['com.sap.xs.java.buildpack.jre.SAPMachineJRE']"
    JBP_CONFIG_SAP_MACHINE_JRE: '{ use_offline_repository: false, version: 21.+ }'
  parameters:
    buildpack: sap_java_buildpack_jakarta
```

**Deploy:**
```bash
cd cap-sflight
mbt build
cf deploy mta_archives/capire.sflight_1.0.0.mtar
```

**Trigger deadlock on CF:**
```bash
cf app sflight-srv | grep routes   # get the URL
curl https://<route>/api/reserve-seat
```

---

## 4. VS Code / MCP Tool Configuration

The JStall extension registers its tools natively as VS Code `languageModelTools`.
No separate MCP server JSON is needed — the tools (`jstall_remote`, `jstall_status`,
`jstall_reveal_code`, etc.) are automatically available in Copilot chat.

Pre-configured in `.vscode/settings.json`:
```json
{
  "jstall.remote.cfApps": ["sflight-srv"],
  "jstall.mcp.status.noNative": true,
  "jstall.mcp.status.intelligentFilter": true
}
```

---

## 5. Demo Script (What to type in Copilot chat)

### Act 0: The Interruption (Notification)

**Pre-stage:** Start the notification timer before beginning the "training video" act:
```bash
./scripts/demo.sh notify 30   # fires 30s after starting
```

This shows a Teams/Slack-style notification popup:
> "#prod-incidents — Michael Scott: @channel 🔥 47 users unable to reserve seats..."

### Act 1: The User Complaint (UI)

1. Open `http://localhost:4004/travel_processor/webapp/index.html`
2. Click on any Travel row to open the Object Page
3. Scroll down to **"My Itinerary"** section
4. Click the **"Reserve Seat"** button
5. Wait ~8-12 seconds → a cryptic error dialog appears
6. "Something is clearly wrong — let's investigate with JStall"

### Act 2: Diagnosis (Copilot + JStall)

**Prompt:**
> Our travel app is throwing cryptic errors when users try to reserve seats. The UI says "monitor contention" and "synchronized region". Can you diagnose the running Java app?

The AI will:
1. Call `jstall_list_jvms` → find `SFlightApplication` PID
2. Call `jstall_status` with `full: true`, `noNative: true`, `intelligentFilter: true` → detect 3-way deadlock
3. Call `jstall_reveal_code` → highlight deadlocked lines across **three different files**:
   - `BookingLedgerService.java:31` (recordBooking → calls paymentGateway.chargePassenger)
   - `PaymentGatewayService.java:43` (reconcilePayments → calls seatInventory.releaseUnpaidHolds)
   - `SeatInventoryService.java:52` (auditInventory → calls bookingLedger.confirmBooking)
4. Explain the circular dependency: Booking → Payment → Inventory → Booking
5. Propose fix: global lock ordering with `ReentrantLock` + `tryLock()`

### Act 3: The Fix

The AI proposes replacing `synchronized` methods with a global lock ordering strategy:
- Define 3 `ReentrantLock` instances with a fixed acquisition order: `BOOKING_LOCK → PAYMENT_LOCK → INVENTORY_LOCK`
- Use `tryLock()` with timeouts to prevent indefinite blocking
- Remove cross-service `synchronized` calls

### CF Demo (Alternative)

**Prompt:**
> Our Cloud Foundry app 'sflight-srv' is stalled. Find the bug.

The AI will:
1. Call `jstall_remote` (CF, "sflight-srv", "list") → discover PID
2. Call `jstall_remote` (CF, "sflight-srv", "status", ["--full", "<PID>"]) → detect deadlock
3. Call `jstall_reveal_code` → highlight deadlocked lines across three service files
4. Propose fix: global `ReentrantLock` ordering

---

## 6. The Fix (Expected AI proposal)

Replace `synchronized` service methods with a global lock ordering strategy.
The key insight: impose a total order on lock acquisition across all three services.

**SeatReservationService.java (fixed):**
```java
@RestController
public class SeatReservationService {

    // Global lock ordering: booking → payment → inventory
    static final ReentrantLock BOOKING_LOCK = new ReentrantLock();
    static final ReentrantLock PAYMENT_LOCK = new ReentrantLock();
    static final ReentrantLock INVENTORY_LOCK = new ReentrantLock();

    private static final long TIMEOUT_SEC = 3;

    @GetMapping("/api/reserve-seat")
    public String reserveSeat() {
        try {
            if (!BOOKING_LOCK.tryLock(TIMEOUT_SEC, TimeUnit.SECONDS)) return "retry";
            try {
                if (!PAYMENT_LOCK.tryLock(TIMEOUT_SEC, TimeUnit.SECONDS)) return "retry";
                try {
                    if (!INVENTORY_LOCK.tryLock(TIMEOUT_SEC, TimeUnit.SECONDS)) return "retry";
                    try {
                        bookingLedger.recordBookingUnsync("PAX-4217");
                        paymentGateway.chargePassengerUnsync("PAX-4217", 299.99);
                        seatInventory.confirmSeatUnsync("PAX-4217");
                        return "Reservation completed successfully.";
                    } finally { INVENTORY_LOCK.unlock(); }
                } finally { PAYMENT_LOCK.unlock(); }
            } finally { BOOKING_LOCK.unlock(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted.";
        }
    }
}
```

**Key changes:**
1. **Global lock ordering** — all paths acquire locks in the same order (Booking → Payment → Inventory)
2. **`tryLock` with timeout** — prevents indefinite blocking
3. **No `@Scheduled` tasks holding locks** — background work decoupled from lock-protected state
4. **`*Unsync` methods** — services expose non-synchronized variants; caller holds global locks

---

## 7. Local AI Dream Sequence (Epilogue)

### Install llama-server (llama.cpp)
```bash
# macOS
brew install llama.cpp
```

### Start llama-server with the model
```bash
# Use the bundled launch script (downloads model on first run, ~9 GB):
./scripts/00-launch-llm.sh --medium

# Or start manually:
llama-server -hf AaryanK/Qwen3.5-9B-GGUF:Q8_0
```

The server runs on `http://127.0.0.1:8080` by default.

### Configuration (`.jstall-ai-config`)
```properties
provider=local
model=AaryanK/Qwen3.5-9B-GGUF:Q8_0
local.host=http://127.0.0.1:8080
```

### Run JStall with local AI analysis
```bash
# First, find the Java PID
jstall processes

# Run the AI command against a local JVM (reads config from .jstall-ai-config)
jstall ai <PID> --local --no-native --intelligent-filter

# Or with additional flags:
jstall ai <PID> --local --full --no-native --intelligent-filter
```

Available model tiers (via `./scripts/00-launch-llm.sh`):
- `--fast` — Qwen3-1.7B (~2 GB, fastest, less accurate)
- `--medium` — Qwen3.5-9B (~9 GB, good balance) ← **recommended for demo**
- `--slow` — Qwen3.5-27B (~27 GB, most accurate, slower)

This runs the thread dump analysis **entirely on-device** — no data leaves the machine.

---

## 8. Pre-demo Checklist

Run the automated check first:
```bash
./scripts/demo.sh check
```

Manual verification:
- [ ] Run `./scripts/demo.sh start` to set up buggy version and start app
- [ ] Verify: `./scripts/demo.sh status` → code: buggy, app: running
- [ ] `cf login` to the target org/space
- [ ] App deployed and running: `cf app sflight-srv`
- [ ] `cf install-plugin cf-cli-java-plugin` (for `cf java` support)
- [ ] JStall extension installed in VS Code
- [ ] Extension rebuilt: `npm run compile`
- [ ] Window reloaded: `Cmd+Shift+P` → "Developer: Reload Window"
- [ ] Trigger deadlock: `curl https://<route>/api/reserve-seat`
- [ ] Verify JStall can reach it: `cf java jstall sflight-srv status`
- [ ] Copilot chat open and ready
- [ ] Notification script tested: `./scripts/demo.sh notify` → press 'r'
- [ ] (Local) App started: `./scripts/demo.sh start`
- [ ] (Local) UI accessible: `open http://localhost:4004/travel_processor/webapp/index.html`
- [ ] (Local) Deadlock triggered via UI: click "Reserve Seat" → wait for error dialog
- [ ] (Local) Deadlock triggered via curl: `curl http://localhost:4004/api/reserve-seat`
- [ ] (Epilogue) llama-server running: `./scripts/00-launch-llm.sh --medium`
