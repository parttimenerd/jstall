// Threads in object.wait() loop -- condition never true
public class MonitorWaitForever {
    static final Object COND = new Object();
    static boolean ready = false;
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(() -> {
                synchronized(COND) {
                    while (!ready) {
                        try { COND.wait(); } catch (InterruptedException e) {}
                    }
                }
            }, "mwf-waiter-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
