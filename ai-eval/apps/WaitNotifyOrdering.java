// Producer-consumer with incorrect wait/notify: producer calls notify() before
// consumer calls wait() — signal is lost, consumer blocks forever.
public class WaitNotifyOrdering {
    static final Object LOCK = new Object();
    static boolean dataReady = false;

    public static void main(String[] args) throws Exception {
        // Producer fires notify FIRST (before consumers start waiting)
        Thread producer = new Thread(() -> {
            synchronized (LOCK) {
                dataReady = true;
                LOCK.notifyAll(); // signal sent before consumers are waiting
            }
        }, "wn-producer");
        producer.setDaemon(true);
        producer.start();
        producer.join();
        Thread.sleep(50); // ensure producer is fully done before consumers start

        // Consumers: check condition then wait — but condition was set, signal already gone
        // Bug: they don't re-check condition before waiting (classic missed-signal bug)
        for (int i = 0; i < 5; i++) {
            Thread consumer = new Thread(() -> {
                synchronized (LOCK) {
                    // Buggy: doesn't check dataReady — goes straight to wait
                    try { LOCK.wait(); } // blocks forever — signal was already sent
                    catch (InterruptedException e) {}
                }
            }, "wn-consumer-" + i);
            consumer.setDaemon(true);
            consumer.start();
        }

        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
