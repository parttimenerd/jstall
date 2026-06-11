// LockSupport.unpark called BEFORE park — the first park returns immediately but
// subsequent parks in the loop block forever (unpark token is consumed).
public class ParkUnparkMissed {
    public static void main(String[] args) throws Exception {
        Thread[] waiters = new Thread[4];
        for (int i = 0; i < 4; i++) {
            final int id = i;
            waiters[i] = new Thread(() -> {
                // First loop iteration: unpark was sent before we started, so park returns fast.
                // Second iteration: no more unpark — blocks forever.
                for (int round = 0; round < 2; round++) {
                    java.util.concurrent.locks.LockSupport.park();
                }
                // Never reaches here
            }, "park-waiter-" + id);
            waiters[i].setDaemon(true);
        }

        // Send unpark to each thread BEFORE it starts (missed signal).
        for (Thread t : waiters) {
            java.util.concurrent.locks.LockSupport.unpark(t); // consumed by first park
            t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
