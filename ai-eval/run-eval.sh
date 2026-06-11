#!/bin/bash
# Harness for evaluating `jstall ai` accuracy and speed against test apps.
#
# Usage: ./run-eval.sh <run-tag> [<model>] [<base-url>]
# Output: ai-eval/results/<run-tag>/<scenario>.{out,err,meta} and summary.txt
#
# When <model> is set, passed via --model to `jstall ai`.
# When <base-url> is set, passed via --base-url (assumes the llama-server is
# already running there serving <model>). The eval will not auto-launch a server.

set -uo pipefail

RUN_TAG="${1:-baseline}"
EVAL_MODEL="${2:-}"
EVAL_BASE_URL="${3:-}"
EVAL_DIR="$(cd "$(dirname "$0")" && pwd)"
APPS_DIR="$EVAL_DIR/apps"
RESULTS_DIR="$EVAL_DIR/results/$RUN_TAG"
JSTALL_JAR="$EVAL_DIR/../target/jstall.jar"

if [[ ! -f "$JSTALL_JAR" ]]; then
    echo "ERROR: $JSTALL_JAR not found. Run: mvn package -DskipTests -q"
    exit 1
fi

mkdir -p "$RESULTS_DIR"

# Build the jstall ai args once.
AI_ARGS=(ai --short --no-pretty)
[[ -n "$EVAL_MODEL" ]]    && AI_ARGS+=(--model "$EVAL_MODEL")
[[ -n "$EVAL_BASE_URL" ]] && AI_ARGS+=(--base-url "$EVAL_BASE_URL")

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
finalizer-stall	FinalizerStall	(Finalizer|fin-lock|finaliz|reference)	-	-
nio-selector-spin	NioSelectorSpin	(nio-event-loop|selector|select|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0|epoll|kqueue)	-	-
classloader-lock	ClassloaderLock	(cl-loader|classloader|class.*loader|defineClass|findClass|class.*lock|classload|BLOCKED|monitor.*conten|lock.*conten|java monitor|holding.*lock)	-	-
semaphore-exhaust	SemaphoreExhaust	(sem-acquirer|sem-holder|semaphore|permit|exhaust|all.*permit|no.*permit|Semaphore)	-	-
completable-chain	CompletableChain	(cf-waiter|completable|future|stage|stall|waiting.*future|never complete|chain)	-	-
latch-deadlock	LatchDeadlock	(latch-awaiter|countdown|latch|await|never.*decrement|never.*countdown|CountDownLatch)	-	-
barrier-stall	BarrierStall	(barrier-party|barrier|cyclic|await|party|never.*arrive|short.*party|missing.*party|CyclicBarrier)	-	-
native-block	NativeBlock	(native-blocked|native-holder|monitor|BLOCKED|contention|holding.*lock)	-	-
junit-hang	JunitHang	(junit|HangingIntegrationTest|test|stuck|hang|DB.*lock|jupiter|Test worker)	-	-
reflection-loop	ReflectionLoop	(reflect-cruncher|reflect|invoke|Method.invoke|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0|RUNNABLE)	-	-
petclinic-idle	EXTERN:launch-petclinic.sh	(tomcat|http-nio|catalina|spring|petclinic|idle|healthy|normal|no.*issue|low.*cpu)	-	-
petclinic-loaded	EXTERN:launch-petclinic-with-load.sh	(http-nio|tomcat|request|servlet|catalina|petclinic|cpu|hot.*spot|RUNNABLE)	-	-
stamped-lock	StampedLockContention	(sl-writer|sl-reader|StampedLock|stamp.*lock|write.*lock|read.*lock.*block|lock.*writer|writer.*hold)	-	-
exchanger-stall	ExchangerStall	(ex-producer|exchanger|exchange|waiting.*partner|no.*partner|Exchanger)	-	-
object-wait-missed	ObjectWaitMissedNotify	(waiter-|Object\.wait|monitor.*wait|notify|WAITING.*lock|never.*notif|missed.*signal|wait.*lock)	-	-
executor-shutdown-stall	ExecutorShutdownStall	(shutdown-worker|shutdown-awaiter|awaitTermination|executor.*shutdown|shutdown.*stall|terminat|pool.*starv|TIMED_WAITING.*worker|starvat|sleep.*worker)	-	-
heap-oom	HeapOOM	(oom-allocator|heap.*used.*[89][0-9]\.|heap.*8[0-9]%|heap.*pressure|memory.*pressure|high.*heap|oom|out.*of.*memory|heap.*[89][0-9])	-	-
spin-wait-cpu	SpinWaitCpu	(spin-waiter|spin.*wait|busy.?spin|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0|RUNNABLE)	-	-
timer-deadlock	TimerDeadlock	(timer.*thread|lock-holder|timer|BLOCKED|monitor.*conten|contention|holding.*lock|java.*monitor)	-	-
single-threaded-stall	SingleThreadedStall	(event-loop|main-awaiter|awaitTermination|single.*thread.*block|starv|saturat|backlog|queued.*task|pool.*sleep|terminat)	-	-
priority-inversion	PriorityInversion	(low-prio|high-prio|med-prio|priority.*inversion|inversion|BLOCKED|monitor.*conten|holding.*lock|lock.*holder)	-	-
thread-pool-sizing	ThreadPoolSizing	(oversize-worker|too many threads|thread.*count|500.*thread|thread.*leak|oversized|thread-count-warning)	-	-
chm-contention	ConcurrentHashMapContention	(chm-contender|ConcurrentHashMap|map.*contend|contend|BLOCKED|monitor.*conten|cpu.*hot|hot.*spot)	-	-
string-interning	StringInterningPressure	(intern-spammer|intern|metaspace|gc.*pressure|alloc.*rate|allocation.*spike|cpu.*hot|hot.*spot|heap.*pressure)	-	-
lbq-drain	LinkedBlockingQueueDrain	(lbq-consumer|lbq-producer|consumer.*idle|consumer.*wait|drain|healthy|idle|WAITING|take)	-	-
virtual-threads-healthy	VirtualThreadsHealthy	(virtual|carrier|healthy|idle|normal|no.*issue|low.*cpu|sleeping|TIMED_WAITING)	-	-
clinit-deadlock	DeadlockTriggeredByInit	(clinit-t1|clinit-t2|class.*init|class.*load|deadlock|BLOCKED|monitor|waiting.*class|initialization)	-	-
recurring-leak	RecurringLeakWithGc	(request-handler|leak|heap.*grow|heap.*Δ|alloc.*spike|memory.*leak|heap.*pressure|heap.*used.*\+|MiB.*5s)	-	-
connection-pool-exhaust	ConnectionPoolExhaust	(db-slow-query|db-waiting-req|semaphore|permit|pool.*exhaust|connection.*pool|connPool|Semaphore|waiting.*connection)	-	-
virtual-thread-pin	VirtualThreadPin	(vt-worker|semaphore.*exhaust|permit.*exhaust|no.*permit|stall.*semaphore|Semaphore|16.*WAITING|executor.*stall|worker.*stall)	-	-
phaser-stall	PhaserStall	(phaser-party|phaser|phase.*never|arriveAndAwait|party.*missing|barrier|advance)	-	-
thread-local-leak	ThreadLocalLeak	(tl-holder|ThreadLocal|thread.*local.*leak|thread.*count|too many threads|memory.*grow|heap.*pressure)	-	-
park-unpark-missed	ParkUnparkMissed	(park-waiter|LockSupport|park|missed.*signal|unpark.*before|signal.*missed|WAITING)	-	-
gc-thrash	GcThrash	(gc-thrash-driver|gc.*thrash|gc.*pressure|full.*gc|gc.*rate|alloc.*rate|heap.*churn|gc.*busy|old.*gen|GC overhead)	-	-
forkjoin-managed-block	ForkJoinManagedBlock	(ForkJoin|ForkJoinPool|pool.*block|pool.*stall|pool.*starv|managed.*block|fork.*join|future.*get|pool.*wait|worker.*block|TIMED_WAITING.*pool|pool.*TIMED_WAITING)	-	-
h2-row-lock-contend	EXTERN:launch-h2-row-lock-contend.sh	(h2-tx-writer|h2-tx-reader|row.*lock|lock.*row|tx.*wait|transaction.*block|BLOCKED|monitor.*conten|UPDATE.*wait|row.*contend)	-	-
h2-long-query	EXTERN:launch-h2-long-query.sh	(h2-slow-query|slow.*query|long.*query|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0|RUNNABLE|query.*run)	-	-
h2-deadlock-txn	EXTERN:launch-h2-deadlock-txn.sh	(h2-txn-holder|h2-txn-waiter|h2.*lock|row.*lock|transaction.*wait|waiting.*lock|h2.*txn|BLOCKED|lock.*wait)	-	-
hikari-pool-exhaust	EXTERN:launch-hikari-pool-exhaust.sh	(hikari-holder|hikari-waiter|hikari|HikariCP|pool.*exhaust|connection.*timeout|waiting.*connection|getConnection)	-	-
hikari-deadlock	EXTERN:launch-hikari-deadlock.sh	(hik-dl-thread|hikari|HikariCP|deadlock|pool.*exhaust|pool.*empty|connection.*wait|getConnection)	-	-
r4j-bulkhead-full	EXTERN:launch-r4j-bulkhead-full.sh	(bh-caller|bh-pool|bulkhead|Bulkhead|ThreadPoolBulkhead|saturated|full.*pool|max.*concurrent|queue.*full)	-	-
r4j-ratelimiter-queue	EXTERN:launch-r4j-ratelimiter-queue.sh	(rl-waiter|rl-permit|RateLimiter|rate.*limit|rate.*limiter|permit.*wait|throttle|rl-gate)	-	-
cp2-pool-stall	EXTERN:launch-cp2-pool-stall.sh	(cp2-holder|cp2-waiter|GenericObjectPool|commons.*pool|borrow.*object|pool.*exhaust|object.*pool)	-	-
actor-mailbox-full	ActorMailboxFull	(actor-sender|actor-dispatcher|mailbox.*full|backpressure|queue.*full|full.*queue|ArrayBlockingQueue|producer.*block)	-	-
reactive-backpressure	ReactiveBackpressure	(rx-publisher|rx-subscriber|backpressure|slow.*subscriber|queue.*full|producer.*block|put.*block)	-	-
event-loop-blocked	EventLoopBlocked	(io-event-loop|event.*loop.*block|blocked.*loop|single.*thread.*block|io.*block|loop.*stall)	-	-
scheduled-task-pileup	ScheduledTaskPileup	(sched-worker|scheduled.*behind|task.*overrun|pile.*up|backlog|fixed.*rate.*behind|slow.*task)	-	-
blocking-rpc-call	BlockingRpcCall	(rpc-caller|socketRead0|socket.*read|blocked.*io|rpc.*block|network.*wait|stuck.*read|readBytes)	-	-
jdbc-connection-leak	JdbcConnectionLeak	(jdbc-leaker|jdbc-honest|semaphore|permit|pool.*exhaust|connection.*leak|pool.*empty|waiting.*connection)	-	-
work-stealing-contention	WorkStealingContention	(ForkJoin|BLOCKED|monitor.*conten|work.*steal|commonPool|cpu.*contend|synchronized|holding.*lock)	-	-
recursive-forkjoin	RecursiveForkJoin	(InfiniteTask|ForkJoin|recursive.*fork|fork.*recursive|pool.*saturat|cpu.*hot|worker.*block)	-	-
monitor-spurious-wakeup	MonitorSpuriousWakeup	(spurious-waiter|Object\.wait|spurious.*wakeup|condition.*false|notify.*again|WAITING|wait.*loop)	-	-
slow-serialization-cpu	SlowSerializationCpu	(ser-worker|ObjectOutputStream|ObjectInputStream|serializ|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0)	-	-
regex-catastrophic	RegexCatastrophic	(regex-cruncher|catastrophic.*backtrack|backtrack|Pattern.*match|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0)	-	-
xml-parsing-cpu	XmlParsingCpu	(xml-parser|DocumentBuilder|SAX|DOM.*pars|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0|RUNNABLE)	-	-
crypto-hash-cpu	CryptoHashCpu	(crypto-hasher|SHA-256|MessageDigest|hash.*cpu|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0|digest)	-	-
sorting-hot-loop	SortingHotLoop	(sort-cruncher|Arrays\.sort|sort.*cpu|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0|RUNNABLE)	-	-
json-parsing-cpu	JsonParsingCpu	(json-parser|json.*cpu|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0|RUNNABLE)	-	-
zip-decompression-cpu	ZipDecompressionCpu	(zip-inflater|GZIPInputStream|Inflater|deflat|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0)	-	-
logging-contention	LoggingContention	(log-writer|BLOCKED|monitor.*conten|log.*lock|synchronized.*log|contend|lock.*holder)	-	-
cache-stampede	CacheStampede	(cache-stampeder|thundering.*herd|cache.*miss|stampede|BLOCKED|monitor.*conten|synchronized.*cache|lock.*holder)	-	-
db-connection-storm	DatabaseConnectionStorm	(db-connector|connection.*storm|storm.*connect|socket.*connect|many.*connect|connect.*flood|RUNNABLE|socketRead0)	-	-
http-client-timeout	HttpClientTimeout	(http-requester|socketRead0|socket.*read|blocked.*io|http.*block|http.*wait|stuck.*read|readBytes)	-	-
batch-job-stall	BatchJobStall	(batch-worker|batch-coordinator|Future\.get|await.*result|worker.*block|coordinator.*wait|future.*never)	-	-
message-queue-lag	MessageQueueConsumerLag	(mq-producer|mq-consumer|consumer.*lag|queue.*grow|backlog|slow.*consumer|producer.*fast)	-	-
metric-contention	MicrometerMetricContention	(metric-recorder|BLOCKED|monitor.*conten|synchronized.*metric|counter.*lock|metric.*lock|contend)	-	-
leaky-async-callback	LeakyAsyncCallback	(async-leaker|CompletableFuture|future.*leak|callback.*leak|heap.*grow|alloc.*spike|memory.*leak)	-	-
spring-batch-step	SpringBatchHeavyStep	(step-executor|CyclicBarrier|barrier|cpu.*hot|hot.*spot|batch.*step|await.*barrier|step.*sync)	-	-
grpc-stub-hang	GrpcStubHang	(grpc-call|CountDownLatch|latch.*await|grpc.*hang|rpc.*wait|await.*response|WAITING)	-	-
reactor-stall	ReactorProjectStall	(reactor-subscriber|CompletableFuture|publisher.*never|subscriber.*wait|Mono.*stall|future.*get|WAITING)	-	-
websocket-conn-leak	WebSocketConnectionLeak	(ws-client|ws-server|thread.*count|too many threads|connection.*leak|socket.*open|thread.*leak|50.*thread)	-	-
thread-local-mem-pressure	ThreadLocalMemoryPressure	(tl-transient|tl-spawner|ThreadLocal|thread.*local.*leak|heap.*grow|memory.*leak|heap.*pressure)	-	-
soft-ref-eviction	SoftReferenceEviction	(sr-filler|SoftReference|soft.*ref|GC.*evict|heap.*pressure|alloc.*rate|gc.*pressure)	-	-
direct-buffer-exhaust	DirectBufferExhaust	(direct-buffer-user|direct-allocator|direct.*memory|ByteBuffer|off-heap|Unsafe.*setMemory|cpu.*hot|hot.*spot|99\.|100\.0)	-	-
metaspace-exhaust	MetaspaceExhaust	(cl-loader|metaspace|Metaspace|classload|URLClassLoader|class.*space|meta.*space|class.*leak|I\/O.*read|FileInputStream)	-	-
classloader-leak	ClassLoaderLeak	(cl-leak-spawner|URLClassLoader|classload.*leak|class.*loader.*leak|metaspace|Metaspace)	-	-
trylock-livelock	TryLockLivelock	(trylock-thread|tryLock|livelock|spin.*lock|lock.*spin|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0)	-	-
thread-group-idle	ThreadGroupIdle	(idle-member|idle-group|ThreadGroup|80.*thread|thread.*count|many.*idle|large.*pool)	-	-
rw-upgrade-deadlock	ReadWriteUpgradeDeadlock	(rw-upgrader|rw-reader|ReadWriteLock|read.*lock|write.*lock|deadlock|upgrade.*dead|BLOCKED)	-	-
heap-fragmentation	HeapFragmentation	(heap-fragmenter|fragment|gc.*pressure|alloc.*rate|heap.*pressure|heap.*churn|GC overhead)	-	-
jni-monitor-block	JniMonitorBlock	(jni-native-holder|jni-waiter|BLOCKED|monitor.*conten|holding.*lock|native.*spin|jni.*monitor)	-	-
semaphore-convoy	SemaphoreConvoy	(convoy-holder|convoy-waiter|semaphore|Semaphore|convoy|fair.*lock|FIFO.*queue|permit.*wait)	-	-
shutdown-hook-stall	ShutdownHookStall	(shutdown-hook-stalled|app-worker-stuck|shutdown.*hook|shutdownHook|stuck.*shutdown|non-daemon|TIMED_WAITING.*worker)	-	-
coroutine-scheduler-stall	CoroutineSchedulerStall	(coroutine-scheduler|fiber-|fiber.*stall|scheduler.*block|coroutine.*wait|SynchronousQueue|WAITING)	-	-
map-reduce-backpressure	MapReduceBackpressure	(mr-mapper|mr-reducer|backpressure|slow.*reducer|channel.*full|mapper.*block|ArrayBlockingQueue)	-	-
wait-notify-ordering	WaitNotifyOrdering	(wn-consumer|wn-producer|Object\.wait|missed.*signal|notify.*before|signal.*lost|WAITING)	-	-
broken-barrier	BrokenBarrier	(bb-party|bb-breaker|BrokenBarrierException|broken.*barrier|barrier.*break|CyclicBarrier|WAITING)	-	-
interrupt-swallowed	InterruptSwallowed	(interrupt-swallower|interrupt.*swallow|swallow.*interrupt|interrupt.*loop|TIMED_WAITING|cpu.*hot|hot.*spot)	-	-
structured-concurrency-stall	StructuredConcurrencyStall	(scope-stuck-task|scope-owner|structured.*concurrent|scope.*stall|future.*stuck|virtual|WAITING)	-	-
busy-wait-double-spin	BusyWaitDoubleSpin	(busy-spin-a|busy-spin-b|spin.*wait|busy.*wait|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0|RUNNABLE)	-	-
false-sharing	FalseSharing	(false-share-writer|false.*shar|cache.*line|cpu.*hot|hot.*spot|cpu.*100|99\.|100\.0|RUNNABLE)	-	-
thread-factory-leak	ThreadFactoryLeak	(spawned-thread|thread.*leak|too many threads|unbounded.*pool|thread.*count|200.*thread|thread.*factory)	-	-
lock-timeout-thunder	LockTimeoutThunder	(timeout-thunder|timeout-lock-holder|thundering.*herd|tryLock|timeout.*lock|TIMED_WAITING|lock.*wait)	-	-
pubsub-slow-subscriber	PubSubSlowSubscriber	(pubsub-publisher|pubsub-slow-sub|pubsub-fast-sub|slow.*subscriber|publisher.*block|backpressure|queue.*full)	-	-
four-thread-deadlock	FourThreadDeadlock	(deadlock|4.*thread|four.*thread|thread-a|thread-b|thread-c|thread-d|cycle)	-	-
semaphore-deadlock	SemaphoreDeadlock	(deadlock|sem-a|sem-b|sem-c|Semaphore.*deadlock|circular.*acquire|cycle)	-	-
lock-ordering-violation	LockOrderingViolation	(deadlock|lock.*order|order.*lock|lock-alpha|lock-beta|lock-gamma|lock-delta|cycle)	-	-
database-lock-emulation	DatabaseLockEmulation	(deadlock|db-tx|db.*lock|db-row|transaction.*lock|row.*lock|cycle)	-	-
mixed-lock-deadlock	MixedLockDeadlock	(deadlock|mixed.*lock|sync.*lock|reentrant.*lock|mixed-sync|mixed-reentrant|cycle)	-	-
thread-pool-deadlock	ThreadPoolDeadlock	(deadlock|pool.*dead|pool.*stall|pool-task|task.*pool|thread.*pool.*dead|pool.*full.*dead)	-	-
executor-saturated	ExecutorSaturated	(saturat|exec-worker|executor.*full|pool.*full|task.*reject|thread.*pool.*exhaust)	-	-
work-queue-poison	WorkQueuePoison	(poison|poison.*pill|queue-consumer|poison.*task|queue.*drain|shutdown.*queue)	-	-
deadline-miss	DeadlineMiss	(deadline|timeout|missed.*deadline|deadline.*miss|late.*task|overdue|behind.*schedule)	-	-
bulk-timeout-stall	BulkTimeoutStall	(timeout|batch.*timeout|bulk.*timeout|batch-task|all.*timeout|deadline.*miss)	-	-
scheduled-overload	ScheduledOverload	(scheduled|overload|sched-slow-task|schedule.*pile|task.*backlog|behind.*schedule|slow.*task)	-	-
completable-join-chain	CompletableJoinChain	(CompletableFuture|cf-chain|join.*chain|completable.*stall|future.*stall|WAITING.*future)	-	-
parallel-stream-stall	ParallelStreamStall	(parallel.*stream|ForkJoin|stream-task|commonPool|parallel.*stall|stream.*stall)	-	-
coarse-lock-bottleneck	CoarseLockBottleneck	(coarse.*lock|lock.*bottleneck|coarse-worker|BLOCKED.*lock|heavy.*contention|single.*lock.*all)	-	-
monitor-wait-forever	MonitorWaitForever	(monitor.*wait|Object\.wait|wait-forever|monitor-waiter|WAITING.*monitor|wait.*never|signal.*never)	-	-
striped-lock-collision	StripedLockCollision	(striped.*lock|stripe.*collide|stripe-worker|hash.*collision|stripe.*hash|BLOCKED)	-	-
condition-timeout-loop	ConditionTimeoutLoop	(condition.*timeout|await.*timeout|cond-waiter|await.*loop|timeout.*condition|TIMED_WAITING.*cond)	-	-
reentrant-write-starvation	ReentrantWriteStarvation	(write.*starv|reader.*starv.*write|rw-reader|rw-writer|read.*lock.*starv|WAITING.*write)	-	-
lock-convoy	LockConvoy	(convoy|lock.*convoy|convoy-worker|BLOCKED.*convoy|lock.*queue.*long|monitor.*queue)	-	-
fair-lock-queue	FairLockQueue	(fair.*lock|fair-waiter|fair.*queue|FIFO.*lock|fair.*reentrant|WAITING.*fair)	-	-
lock-free-spin	LockFreeSpin	(lock.?free|CAS.*spin|compare.*swap|lf-worker|compareAndSet|spin.*CAS|cpu.*hot|RUNNABLE)	-	-
socket-read-stall	SocketReadStall	(socket.*read|socketRead0|sock-reader|blocked.*socket|network.*read|WAITING.*socket)	-	-
print-stream-contend	PrintStreamContend	(PrintStream|System\.out|println.*contend|print-writer|BLOCKED.*print|synchronized.*print)	-	-
network-retry-storm	NetworkRetryStorm	(retry.*storm|net-retry|retry.*loop|connection.*retry|retry.*flood|storm.*retry|cpu.*hot)	-	-
buffered-reader-stall	BufferedReaderStall	(BufferedReader|buffered.*read|buf-reader|blocking.*read|read.*block|WAITING.*read)	-	-
random-access-cpu	RandomAccessCpu	(RandomAccessFile|random.*access|raf-cpu|file.*random.*cpu|seek.*read.*cpu|RUNNABLE)	-	-
pipe-writer-stall	PipeWriterStall	(PipedOutputStream|pipe.*write|pipe-writer|pipe.*full|write.*pipe.*block|WAITING.*pipe)	-	-
young-gen-storm	YoungGenStorm	(young.*gen|young.*alloc|eden|survivor|gc.*young|alloc.*storm|young-gen-alloc)	-	-
off-heap-leak	OffHeapLeak	(off.?heap|ByteBuffer.allocateDirect|direct.*buffer|native.*memory|off.*heap.*leak|direct.*alloc)	-	-
large-object-alloc	LargeObjectAlloc	(large.*object|large.*alloc|humongous|old.*gen|large-alloc|tenured|large.*array)	-	-
string-concat-pressure	StringConcatPressure	(string.*concat|concat.*pressure|StringBuilder|string.*alloc|string-concat|gc.*string)	-	-
object-pool-churn	ObjectPoolChurn	(object.*pool|pool.*churn|pool-churn|object.*reuse|pool.*alloc|RUNNABLE.*pool)	-	-
gc-pressure-loop	GcPressureLoop	(gc.*pressure|gc-pressure|alloc.*loop|gc.*loop|allocation.*pressure|gc.*hot)	-	-
copy-on-write-storm	CopyOnWriteWriteStorm	(CopyOnWrite|COW.*write|copy.*write.*storm|cow-writer|write.*storm.*copy|RUNNABLE.*cow)	-	-
priority-queue-drain-stall	PriorityQueueDrainStall	(PriorityQueue|priority.*drain|pq-drainer|drain.*stall|queue.*drain.*stall|WAITING.*priority)	-	-
linked-transfer-stall	LinkedTransferStall	(LinkedTransferQueue|transfer.*stall|transfer-producer|lt-producer|lt-consumer|WAITING.*transfer)	-	-
blocking-deque-full	BlockingDequeFull	(BlockingDeque|deque.*full|deque-producer|blocking.*deque.*full|WAITING.*deque|put.*deque)	-	-
concurrent-map-stall	ConcurrentMapStall	(ConcurrentHashMap|computeIfAbsent.*stall|cmap-worker|map.*stall|concurrent.*map.*lock|WAITING.*map)	-	-
exchanger-timeout	ExchangerTimeout	(Exchanger|exchanger.*timeout|exchange.*timeout|exc-partner|TIMED_WAITING.*exchange|exchanger.*wait)	-	-
timer-cancel-race	TimerCancelRace	(timer.*cancel|cancel.*race|timer-scheduler|Timer.*cancel|race.*cancel|timer.*task)	-	-
rate-limited-herd	RateLimitedHerd	(rate.*limit|rate-limited|rate-waiter|throttle|permit.*wait|RateLimiter|TIMED_WAITING.*rate)	-	-
recurring-task-overrun	RecurringTaskOverrun	(recurring.*overrun|overrun.*task|task.*overrun|sched-overrunner|fixed.*rate.*overrun|behind.*schedule)	-	-
scheduled-cancel-leak	ScheduledCancelLeak	(scheduled.*cancel|cancel.*leak|sched-leak|ScheduledExecutor.*cancel|cancel.*thread|thread.*leak)	-	-
timer-thread-stall	TimerThreadStall	(Timer.*thread|timer.*stall|timer-waiter|Timer.*WAITING|timer.*block|WAITING.*Timer)	-	-
class-init-deadlock	ClassInitDeadlock	(clinit|class.*init.*dead|static.*init.*dead|clinit-thread|initialization.*dead|Class.*lock)	-	-
reflection-cpu-loop	ReflectionCpuLoop	(reflection.*cpu|Method\.invoke|reflect.*loop|reflection-cpu|invoke.*loop|cpu.*reflect)	-	-
gc-finalizer-backlog	GcFinalizerBacklog	(finalizer.*backlog|Finalizer.*queue|finalize.*backlog|finalizer-alloc|finalizer.*queue.*full|GC.*finalizer)	-	-
jit-warmup-storm	JitWarmupStorm	(JIT|jit.*warm|compilation.*thread|C2.*compiler|C1.*compiler|jit-caller|cpu.*hot|RUNNABLE)	-	-
class-load-storm	ClassLoadStorm	(class.*load.*storm|URLClassLoader|classload-worker|class.*load.*contend|BLOCKED.*classload|classloader.*lock)	-	-
completable-allof-stall	CompletableAllOfStall	(allOf|allof.*stall|cf-allof-waiter|CompletableFuture\.allOf|all.*future.*wait|WAITING.*allOf)	-	-
async-callback-loop	AsyncCallbackLoop	(async.*callback|callback.*loop|async-callback|re.?enqueue|callback.*re.*submit|no.*progress)	-	-
future-get-timeout	FutureGetTimeout	(future.*get.*timeout|future-waiter|Future\.get.*timeout|TIMED_WAITING.*future|get.*timeout)	-	-
executor-await-stall	ExecutorAwaitStall	(executor.*await|awaitTermination|executor-shutdown-waiter|await.*termination|TIMED_WAITING.*await)	-	-
completable-timeout-chain	CompletableTimeoutChain	(timeout.*chain|orTimeout|cf-timeout-chain|completable.*timeout|future.*timeout.*chain|TIMED_WAITING.*cf)	-	-
request-handler-timeout	RequestHandlerTimeout	(request.*handler.*timeout|request-handler|handler.*timeout|socket.*read.*timeout|WAITING.*handler)	-	-
circuit-breaker-spin	CircuitBreakerSpin	(circuit.*breaker|circuit.*open|cb-probe|half.?open.*spin|breaker.*spin|cpu.*hot|RUNNABLE)	-	-
bulkhead-stall	BulkheadStall	(bulkhead|bulkhead-request|bulkhead.*full|bulkhead.*stall|permit.*bulkhead|WAITING.*bulkhead)	-	-
cache-populate-stall	CachePopulateStall	(cache.*stampede|cache.*populate|cache-loader|cold.*cache.*contend|BLOCKED.*cache|cache.*stall)	-	-
slow-consumer-backpressure	SlowConsumerBackpressure	(slow.*consumer|backpressure|slow-producer|slow-consumer|producer.*block|queue.*full.*back)	-	-
connection-lease-stall	ConnectionLeaseStall	(connection.*lease|conn-lease-waiter|lease.*stall|pool.*lease|connection.*wait.*pool|WAITING.*conn)	-	-
daemon-thread-storm	DaemonThreadStorm	(daemon.*thread.*storm|thread.*leak|daemon-leaked|500.*thread|many.*daemon|too many threads)	-	-
stack-overflow-retry	StackOverflowRetry	(StackOverflow.*retry|soe-retry|stack.*overflow.*retry|catch.*StackOverflow|cpu.*hot|RUNNABLE)	-	-
unsafe-park-stall	UnsafeParkStall	(LockSupport.park|park-stall|unsafe.*park|park.*stall|WAITING.*park|parked.*forever)	-	-
sleep-negative-spin	SleepNegativeSpin	(sleep.*zero|sleep.*spin|sleep-zero-spin|Thread\.sleep.*0.*spin|busy.*wait.*sleep|cpu.*hot)	-	-
thread-name-mismatch	ThreadNameMismatch	(idle.*worker.*block|idle-worker.*BLOCKED|mislead.*name|idle.*lock|BLOCKED.*idle|name.*mismatch)	-	-
phantom-ref-stall	PhantomRefStall	(phantom.*ref|PhantomReference|phantom-ref-drainer|ReferenceQueue.*block|reference.*queue.*wait|TIMED_WAITING.*ref)	-	-
interrupt-ignored	InterruptIgnored	(interrupt.*ignored|interrupt-ignored|swallow.*interrupt|interrupt.*swallow|InterruptedException.*loop|TIMED_WAITING.*interrupt)	-	-
object-wait-spurious	ObjectWaitSpurious	(spurious.*wait|spurious-wait|Object\.wait.*no.*loop|wait.*no.*condition|TIMED_WAITING.*wait)	-	-
semaphore-release-bug	SemaphoreReleaseBug	(semaphore.*release.*bug|sem-over-release|sem-spin|over.*release|extra.*permit|Semaphore.*overflow)	-	-
volatile-spin-check	VolatileSpinCheck	(volatile.*spin|volatile-spin|spin.*volatile|busy.*wait.*volatile|cpu.*hot.*volatile|RUNNABLE.*spin)	-	-
EOF
}

SUMMARY="$RESULTS_DIR/summary.txt"
: > "$SUMMARY"
echo "Run: $RUN_TAG" >> "$SUMMARY"
echo "Model: ${EVAL_MODEL:-<default>}" >> "$SUMMARY"
echo "Base URL: ${EVAL_BASE_URL:-<default>}" >> "$SUMMARY"
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
    timeout 360 java -jar "$JSTALL_JAR" "${AI_ARGS[@]}" "$APP_PID" \
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
