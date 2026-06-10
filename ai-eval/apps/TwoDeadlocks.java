// Two parallel deadlocks: pair-X and pair-Y each hold their own ABBA cycle.
// Tests that the model reports BOTH cycles, not just the first.
public class TwoDeadlocks {
    static final Object X1 = new Object();
    static final Object X2 = new Object();
    static final Object Y1 = new Object();
    static final Object Y2 = new Object();

    public static void main(String[] args) throws Exception {
        Thread x1 = new Thread(() -> {
            synchronized (X1) {
                try { Thread.sleep(200); } catch (InterruptedException e) {}
                synchronized (X2) {}
            }
        }, "pair-x-t1");
        Thread x2 = new Thread(() -> {
            synchronized (X2) {
                try { Thread.sleep(200); } catch (InterruptedException e) {}
                synchronized (X1) {}
            }
        }, "pair-x-t2");
        Thread y1 = new Thread(() -> {
            synchronized (Y1) {
                try { Thread.sleep(200); } catch (InterruptedException e) {}
                synchronized (Y2) {}
            }
        }, "pair-y-t1");
        Thread y2 = new Thread(() -> {
            synchronized (Y2) {
                try { Thread.sleep(200); } catch (InterruptedException e) {}
                synchronized (Y1) {}
            }
        }, "pair-y-t2");
        x1.start(); x2.start(); y1.start(); y2.start();
        Thread.sleep(1500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
