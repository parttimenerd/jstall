#!/bin/bash
# Harness for evaluating `jstall ai` accuracy and speed against test apps.
#
# Usage: ./run-eval.sh <run-tag>
# Output: ai-eval/results/<run-tag>/<scenario>.{out,err,meta} and summary.txt

set -uo pipefail

RUN_TAG="${1:-baseline}"
EVAL_DIR="$(cd "$(dirname "$0")" && pwd)"
APPS_DIR="$EVAL_DIR/apps"
RESULTS_DIR="$EVAL_DIR/results/$RUN_TAG"
JSTALL_JAR="$EVAL_DIR/../target/jstall.jar"

if [[ ! -f "$JSTALL_JAR" ]]; then
    echo "ERROR: $JSTALL_JAR not found. Run: mvn package -DskipTests -q"
    exit 1
fi

mkdir -p "$RESULTS_DIR"

# Each scenario is defined by 5 fields separated by TABS (so regex columns
# can contain '|' freely). Bash's `read` treats TAB as whitespace and collapses
# consecutive tabs, so use the literal token `-` for an empty regex column.
#   <name> \t <main-class> \t <must-contain> \t <must-not-contain> \t <must-contain-2>
read_scenarios() {
cat <<'EOF'
deadlock	Deadlock	deadlock	-	-
hot-loop	HotLoop	cpu-burner	-	-
lock-contention	LockContention	contend	-	-
queue-backpressure	QueueBackpressure	(producer|backpressure|queue.*full|full.*queue|ArrayBlockingQueue)	-	-
healthy	Healthy	-	-	-
pool-starvation	PoolStarvation	(starv|saturat|exhaust|pool.*sleep|pool.*idle|all.*workers.*sleep|tasks.*pile|backlog|pool.*full|task.*reject|hidden-timed-waiting|TIMED_WAITING.*pool|starved-pool)	-	-
heap-growth	HeapGrowth	(heap.*grow|heap.*spike|spike.*heap|spike.*\+|alloc.*spike|allocation.*spike|heap.*Δ|MiB.*5s|memory.*leak|heap.*pressure|heap.*used.*\+|spike|allocator)	-	-
thread-leak	ThreadLeak	(thread.*leak|leak-spawner|too many threads|thread.*count|leaked-thread)	-	-
reentrant-contention	ReentrantContention	(rl-contender|reentrant|lock.*conten|contend)	-	-
deadlock-plus-hot	DeadlockPlusHot	(deadlock|abba)	-	cpu-burner
condition-wait	ConditionWait	(cond-waiter|condition|await|park|signal)	-	-
sleep-storm	SleepStorm	-	(deadlock detected|deadlock cycle|abba-|monitor contention detected|critical issue detected|severe.*starv|pool.*is.*starv|active.*starvation)	-
livelock	Livelock	(livelock|spin|tryLock|trylock|polite|cpu.*hot|hot.*spot|cpu.*saturat|cpu.*100|99\.|100\.0)	-	-
recursive-sync	RecursiveSync	(deep-recursor|recursive|recursion|computation|cpu.*hot|hot.*spot|deep stack|prime|cpu.*100|99\.|100\.0)	(deadlock detected|deadlock cycle|monitor contention detected)	-
slow-io	SlowIO	(io-reader|socketRead0|socket.*read|native.*read|blocked.*io|i/o block|network.*wait|stuck.*read)	-	-
cpu-prime	CpuPrime	(prime-cruncher|cpu.*hot|hot.*spot|cpu.*100|cpu.*saturat|99\.|100\.0)	-	-
two-deadlocks	TwoDeadlocks	(pair-x|pair-y|two deadlock|2 deadlock|multiple deadlock|both cycle|two cycle|pair x|pair y)	-	deadlock
mass-contention	MassContention	(mass-contender|severe.*contention|heavy.*contention|monitor contention|many.*blocked|30.*blocked|many threads.*block)	-	-
gc-pressure	GcPressure	(alloc-churner|alloc.*rate|alloc.*spike|gc.*pressure|allocation.*pressure|heap.*churn|heap.*grow|young.*alloc|allocation rate)	-	-
scheduled-pileup	ScheduledPileup	(sched-worker|backlog|pile.*up|behind|slow.*task|task.*overrun|schedul.*overrun|saturat|fixed-rate)	-	-
forkjoin-saturation	ForkJoinSaturation	(ForkJoin|fj-submitter|common.*pool|pool.*saturat|cpu.*hot|cpu.*100|99\.|100\.0|recursive)	-	-
syncqueue-stall	SyncQueueStall	(sq-producer|SynchronousQueue|transfer|park.*producer|producer.*park|no consumer|hand.?off|put|stall)	-	-
mixed-workload	MixedWorkload	(worker-|healthy|normal|no.*issue|mixed|sleep|low.*cpu|stable)	-	-
triple-deadlock	TripleDeadlock	(tri-t1|tri-t2|tri-t3|three.*thread|triple.*deadlock|3.*thread.*deadlock|cyclic)	-	deadlock
writer-starvation	WriterStarvation	(rw-writer|rw-reader|writer.*starv|reader.*writer|read.*write.*lock|ReentrantReadWriteLock|reader.*hold)	-	-
test-suite-hang	TestSuiteHang	(test-HangingTest|HangingTest\.testThatHangs|hang.*test|stuck.*test)	-	(BLOCKED|monitor|contention|holding.*lock|blocking)
petclinic-idle	EXTERN:launch-petclinic.sh	(tomcat|http-nio|catalina|spring|petclinic|idle|healthy|normal|no.*issue|low.*cpu)	-	-
petclinic-loaded	EXTERN:launch-petclinic-with-load.sh	(http-nio|tomcat|request|servlet|catalina|petclinic|cpu|hot.*spot|RUNNABLE)	-	-
EOF
}

SUMMARY="$RESULTS_DIR/summary.txt"
: > "$SUMMARY"
echo "Run: $RUN_TAG" >> "$SUMMARY"
echo "Started: $(date)" >> "$SUMMARY"
echo "----" >> "$SUMMARY"

PASS=0
FAIL=0
TOTAL_TIME=0
TOTAL_TOOL_CALLS=0
N=0

while IFS=$'\t' read -r name main_class must_regex must_not_regex must_regex2; do
    [[ -z "$name" ]] && continue
    echo ""
    echo "=== $name ($main_class) ==="

    READY_FILE="$(mktemp -t jstall-eval.XXXXXX)"
    rm -f "$READY_FILE"

    if [[ "$main_class" == EXTERN:* ]]; then
        # External launcher script. The script is given the ready-file path as $1
        # and is responsible for printing the target PID on its stdout once ready.
        SCRIPT="${main_class#EXTERN:}"
        SCRIPT_ABS="$EVAL_DIR/$SCRIPT"
        if [[ ! -x "$SCRIPT_ABS" ]]; then
            echo "FAIL: $name launcher $SCRIPT_ABS not executable" | tee -a "$SUMMARY"
            FAIL=$((FAIL+1)); N=$((N+1)); continue
        fi
        APP_PID=$("$SCRIPT_ABS" "$READY_FILE")
        if [[ -z "$APP_PID" || ! -d "/proc/$APP_PID" && "$(uname)" != "Darwin" ]]; then
            # macOS doesn't have /proc — fall through if PID is non-empty
            if [[ -z "$APP_PID" ]]; then
                echo "FAIL: $name launcher returned no PID" | tee -a "$SUMMARY"
                FAIL=$((FAIL+1)); N=$((N+1)); continue
            fi
        fi
    else
        cd "$APPS_DIR"
        java -cp . "$main_class" "$READY_FILE" >/dev/null 2>&1 &
        APP_PID=$!
        disown 2>/dev/null || true
        cd - >/dev/null
    fi

    for i in $(seq 1 600); do
        [[ -f "$READY_FILE" ]] && break
        sleep 0.1
    done

    if [[ ! -f "$READY_FILE" ]]; then
        echo "FAIL: $name app never became ready" | tee -a "$SUMMARY"
        kill -9 "$APP_PID" 2>/dev/null
        FAIL=$((FAIL+1)); N=$((N+1))
        continue
    fi

    OUT="$RESULTS_DIR/$name.out"
    ERR="$RESULTS_DIR/$name.err"
    META="$RESULTS_DIR/$name.meta"

    START=$(date +%s)
    timeout 360 java -jar "$JSTALL_JAR" ai --short --no-pretty "$APP_PID" \
        > "$OUT" 2> "$ERR" < /dev/null
    EXIT=$?
    END=$(date +%s)
    ELAPSED=$((END - START))

    kill -15 "$APP_PID" 2>/dev/null
    sleep 0.5
    kill -9 "$APP_PID" 2>/dev/null
    wait "$APP_PID" 2>/dev/null
    # If this scenario spawned curl loaders, kill them too (petclinic-loaded)
    pkill -9 -f "127.0.0.1:9[0-9][0-9][0-9]/owners" 2>/dev/null || true
    pkill -9 -f "curl -s -o /dev/null http://127.0.0.1:9" 2>/dev/null || true
    rm -f "$READY_FILE"

    # Score it
    TOOL_CALLS=$(grep -c '^\[tool\] ' "$ERR" 2>/dev/null)
    [[ -z "$TOOL_CALLS" ]] && TOOL_CALLS=0
    PASSED=true
    REASONS=()

    if [[ $EXIT -ne 0 && $EXIT -ne 124 ]]; then
        PASSED=false
        REASONS+=("exit=$EXIT")
    fi
    if [[ $EXIT -eq 124 ]]; then
        PASSED=false
        REASONS+=("timeout(360s)")
    fi
    if [[ -n "$must_regex" && "$must_regex" != "-" ]]; then
        if ! grep -qiE "$must_regex" "$OUT"; then
            PASSED=false
            REASONS+=("missing must-match: $must_regex")
        fi
    fi
    if [[ -n "${must_regex2:-}" && "${must_regex2:-}" != "-" ]]; then
        if ! grep -qiE "$must_regex2" "$OUT"; then
            PASSED=false
            REASONS+=("missing must-match-2: $must_regex2")
        fi
    fi
    if [[ -n "$must_not_regex" && "$must_not_regex" != "-" ]]; then
        if grep -qiE "$must_not_regex" "$OUT"; then
            PASSED=false
            REASONS+=("contains forbidden: $must_not_regex")
        fi
    fi
    # False-positive check on healthy: lines that POSITIVELY claim a problem.
    # A line containing "no deadlock" or "deadlock: 0" or "not present" is fine (negation).
    if [[ "$name" == "healthy" ]]; then
        if grep -iE "(deadlock|contention|starvation|exhaust)" "$OUT" \
                | grep -ivE "(no [a-z ]*(deadlock|contention)|0 deadlock|none detected|free of|absent|without|not present|not detected|not found|not observed|no signs of|no evidence of)" \
                | grep -iE "(detected|found|present|observed|active|holding)" >/dev/null; then
            PASSED=false
            REASONS+=("false-positive on healthy app")
        fi
    fi

    {
        echo "scenario=$name"
        echo "elapsed_s=$ELAPSED"
        echo "exit=$EXIT"
        echo "tool_calls=$TOOL_CALLS"
        echo "passed=$PASSED"
        echo "reasons=${REASONS[*]:-}"
    } > "$META"

    if $PASSED; then
        STATUS="PASS"; PASS=$((PASS+1))
    else
        STATUS="FAIL"; FAIL=$((FAIL+1))
    fi
    N=$((N+1))
    TOTAL_TIME=$((TOTAL_TIME + ELAPSED))
    TOTAL_TOOL_CALLS=$((TOTAL_TOOL_CALLS + TOOL_CALLS))

    LINE="$STATUS $name elapsed=${ELAPSED}s tool_calls=$TOOL_CALLS exit=$EXIT"
    if ! $PASSED; then
        LINE="$LINE reasons=[${REASONS[*]}]"
    fi
    echo "$LINE" | tee -a "$SUMMARY"
done < <(read_scenarios)

echo "----" >> "$SUMMARY"
echo "Pass: $PASS / $N" >> "$SUMMARY"
echo "Fail: $FAIL / $N" >> "$SUMMARY"
echo "Total time: ${TOTAL_TIME}s" >> "$SUMMARY"
if [[ $N -gt 0 ]]; then
    echo "Avg time per scenario: $((TOTAL_TIME / N))s" >> "$SUMMARY"
fi
echo "Total tool calls: $TOTAL_TOOL_CALLS" >> "$SUMMARY"
echo "Done: $(date)" >> "$SUMMARY"

cat "$SUMMARY"
