// 20 threads acquire single lock in strict sequence
public class LockConvoy {
    static final Object LOCK = new Object();
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                synchronized(LOCK) { try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {} }
            }, "lc-convoy-" + i);
            t.setDaemon(true); t.start();
            Thread.sleep(10);
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
