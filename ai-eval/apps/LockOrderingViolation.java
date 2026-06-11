// ABBA deadlock with 3 objects, multiple contenders
public class LockOrderingViolation {
    static final Object LA = new Object(), LB = new Object(), LC = new Object();
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 8; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                Object first = (n % 3 == 0) ? LA : (n % 3 == 1) ? LB : LC;
                Object second = (n % 3 == 0) ? LB : (n % 3 == 1) ? LC : LA;
                synchronized(first) {
                    try { Thread.sleep(100); } catch (Exception e) {}
                    synchronized(second) { try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {} }
                }
            }, "lov-thread-" + n);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
