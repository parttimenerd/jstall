// Thread leak: continuously spawn fresh threads that never die. Should trip thread-count-warning.
public class ThreadLeak {
    public static void main(String[] args) throws Exception {
        Thread spawner = new Thread(() -> {
            int n = 0;
            while (true) {
                final int id = n++;
                Thread leaked = new Thread(() -> {
                    // park forever
                    try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
                }, "leaked-thread-" + id);
                leaked.setDaemon(true);
                leaked.start();
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        }, "leak-spawner");
        spawner.setDaemon(true);
        spawner.start();
        // Let it create ~100 threads first
        Thread.sleep(2500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
