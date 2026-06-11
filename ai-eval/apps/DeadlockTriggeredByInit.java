// Class initialization deadlock: two classes each wait for the other to initialize.
public class DeadlockTriggeredByInit {
    static class A {
        static final int VAL;
        static {
            try { Thread.sleep(200); } catch (InterruptedException e) {}
            VAL = B.VAL + 1;
        }
    }
    static class B {
        static final int VAL;
        static {
            try { Thread.sleep(200); } catch (InterruptedException e) {}
            VAL = A.VAL + 1;
        }
    }

    public static void main(String[] args) throws Exception {
        Thread t1 = new Thread(() -> System.out.println("A=" + A.VAL), "clinit-t1");
        Thread t2 = new Thread(() -> System.out.println("B=" + B.VAL), "clinit-t2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        Thread.sleep(50);
        t2.start();
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
