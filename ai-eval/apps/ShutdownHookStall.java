// Non-daemon thread blocks JVM shutdown: app-worker-stuck is a non-daemon
// thread sleeping indefinitely — prevents clean exit. Plus a stalled shutdown hook.
public class ShutdownHookStall {
    public static void main(String[] args) throws Exception {
        // Register a slow shutdown hook (visible as "shutdown-hook-stalled" thread)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {}
        }, "shutdown-hook-stalled"));

        // 3 non-daemon app threads — these keep the JVM alive
        for (int i = 0; i < 3; i++) {
            Thread worker = new Thread(() -> {
                try { Thread.sleep(Long.MAX_VALUE); }
                catch (InterruptedException e) {}
            }, "app-worker-stuck-" + i);
            worker.setDaemon(false);
            worker.start();
        }

        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
