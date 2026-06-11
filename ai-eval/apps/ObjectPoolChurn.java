// Rapid borrow-return from custom pool with lock contention
public class ObjectPoolChurn {
    static final Object LOCK = new Object();
    static final Object[] POOL = new Object[5];
    static int available = 5;
    static { for (int i = 0; i < 5; i++) POOL[i] = new Object(); }
    static Object borrow() throws InterruptedException {
        synchronized (LOCK) {
            while (available == 0) LOCK.wait();
            return POOL[--available];
        }
    }
    static void ret() { synchronized (LOCK) { available++; LOCK.notifyAll(); } }
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try { borrow(); ret(); } catch (InterruptedException e) { break; }
                }
            }, "opc-churner-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
