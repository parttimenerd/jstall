# AI eval: harness, results, and notes

This directory contains a 6-scenario evaluation harness for `jstall ai` plus the
test apps that drive it. Built during a session to find correctness/speed regressions
in the AI mode and validate fixes.

## Layout
- `apps/` — Java test apps, each demonstrating one classic JVM issue
- `run-eval.sh` — launches each app, runs `jstall ai --short --no-pretty <pid>`,
  scores accuracy with regex, records elapsed time + tool-call count
- `results/<run-tag>/` — per-scenario `.out`/`.err`/`.meta` and a `summary.txt`

## Scenarios
| Scenario | App | Expected diagnosis |
|---|---|---|
| `deadlock` | ABBA monitor deadlock | JVM-reported deadlock, thread names + lock addrs |
| `hot-loop` | one thread spinning | identify `cpu-burner` at ~100% CPU |
| `lock-contention` | 8 threads on one synchronized block | lock contention (not deadlock) |
| `queue-backpressure` | producer blocked on full ArrayBlockingQueue | producer/queue-full |
| `healthy` | idle ScheduledThreadPoolExecutor | no false-positives |
| `pool-starvation` | 100 tasks queued, 2 worker pool | starvation/saturation/queued-tasks |

## Running the eval

```bash
mvn package -DskipTests -q
# Make sure a llama-server is running on :8080 (or let jstall auto-launch)
bash ai-eval/run-eval.sh <run-tag>
```

## Key findings

### Speed regression: `--short` second-pass could hang for 30+ minutes

The baseline run (Qwen3.5-9B-GGUF Q8_0, original prompt) had two scenarios that
took 2000s+ each. Both hit a second-pass `createShortSummary` LLM call that hung.
Output was already concise; the second pass was wasted work.

**Fix** (`AiAnalyzer.java`): skip second-pass summary when first-pass output is
already short. Threshold: `SHORT_SUMMARY_THRESHOLD = 1500` chars.

```
Before: avg 714s/scenario, healthy 2075s, pool-starvation 2038s
After:  avg  26s/scenario, healthy   30s, pool-starvation   22s
```

**~27× speedup end-to-end on the 9B model.**

### Harness bug: stdin passed through to `java -jar jstall.jar`

The harness uses process substitution (`done < <(read_scenarios)`) to feed
scenarios into the loop. When `java -jar jstall.jar ai` runs without `</dev/null`,
the JVM consumes from the loop's stdin and the next `read` returns EOF — so the
loop exited after one iteration. Fixed by adding `< /dev/null` to the `java -jar`
invocation.

### Prompt tweak: less hallucination on small models without losing the strong rules

Original prompt led the bullets with "Flag:" and a list of rules.
The 1.7B model on small-bullet rule lists tended to:
- copy the rules as findings even when triggers were absent (false positives)
- claim the app was healthy when an obvious 100% CPU thread was visible

Single-clause edit (`AiAnalyzer.DEFAULT_USER_PROMPT`):

```
"One-sentence bottom line then bullet findings. Each bullet must cite a specific
 thread name, lock address, section, or number from the data above — never
 repeat a rule whose trigger is absent. Flag: ..."
```

Two effects:
- "cite a specific name/number" forces the model to re-read the data → catches the
  cpu-burner case
- "never repeat a rule whose trigger is absent" kills the rule-copying failure mode

## Final scores

### Qwen3.5-9B-GGUF Q8_0
| Scenario | Diagnosis | Time |
|---|---|---|
| deadlock | ✅ ABBA deadlock with thread names + lock addrs | 24s |
| hot-loop | ✅ cpu-burner 99.9% CPU at HotLoop.lambda$main$0 | 23s |
| lock-contention | ✅ 8 threads blocked on shared lock | 27s |
| queue-backpressure | ✅ producer-1 blocked on ArrayBlockingQueue.put | 33s |
| healthy | ✅ idle workers (no false-positives) | 30s |
| pool-starvation | ❌ false-negative (data signal too weak after compression) | 22s |

**5/6 pass · avg 26s/scenario · 159s total**

### Qwen3-1.7B-GGUF Q8_0 (~6× smaller model)
| Scenario | Diagnosis | Time |
|---|---|---|
| deadlock | ✅ | 13s |
| hot-loop | ✅ | 12s |
| lock-contention | ✅ | 19s |
| queue-backpressure | ✅ | 12s |
| healthy | ✅ | 13s |
| pool-starvation | ❌ | 13s |

**5/6 pass · avg 13s/scenario · 82s total** — same accuracy as 9B at ~2× speed.

## Remaining limitations

**`pool-starvation` false-negative on both models.** The compressed thread-state
summary (e.g. "9 RUNNABLE, 4 TIMED_WAITING, 1 WAITING") has no signal that 100
tasks are queued for 2 workers. ContextCompressor would need to surface
ExecutorService queue depths (or ThreadPoolExecutor queue lengths) to make this
visible. Not a prompt issue.

The 9B model occasionally has runaway generation (985s for hot-loop with the
new prompt in one run) that produces correct but very-late output. Likely needs
a max-tokens cap or anti-repetition penalty in the OpenAi request.
