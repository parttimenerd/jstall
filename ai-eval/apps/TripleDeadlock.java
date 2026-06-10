// Three-thread cyclic deadlock: A→B, B→C, C→A. Tests that the model handles cycles longer than 2.
public class TripleDeadlock {
    static final Object A = new Object();
    static final Object B = new Object();
    static final Object C = new Object();

    public static void main(String[] args) throws Exception {
        Thread t1 = new Thread(() -> {
            synchronized (A) {
                try { Thread.sleep(300); } catch (InterruptedException e) {}
                synchronized (B) {}
            }
        }, "tri-t1");
        Thread t2 = new Thread(() -> {
            synchronized (B) {
                try { Thread.sleep(300); } catch (InterruptedException e) {}
                synchronized (C) {}
            }
        }, "tri-t2");
        Thread t3 = new Thread(() -> {
            synchronized (C) {
                try { Thread.sleep(300); } catch (InterruptedException e) {}
                synchronized (A) {}
            }
        }, "tri-t3");
        t1.start(); t2.start(); t3.start();
        Thread.sleep(2000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
