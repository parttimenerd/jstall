// ThreadGroup with many daemon threads doing nothing — tests thread group
// visibility and large idle-thread reporting.
public class ThreadGroupIdle {
    public static void main(String[] args) throws Exception {
        ThreadGroup group = new ThreadGroup("idle-group");
        // 80 idle threads in a custom group
        for (int i = 0; i < 80; i++) {
            Thread t = new Thread(group, () -> {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
            }, "idle-member-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
