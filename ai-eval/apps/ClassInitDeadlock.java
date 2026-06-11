// Two classes with circular static initializers deadlock on clinit monitor.
// clinit-thread-A holds Class A's lock waiting for B; clinit-thread-B holds B's lock waiting for A.
public class ClassInitDeadlock {
    static class A {
        static final int VALUE;
        static {
            try { Thread.sleep(200); } catch (Exception e) {}
            VALUE = B.VALUE + 1; // forces B to initialize
        }
    }
    static class B {
        static final int VALUE;
        static {
            try { Thread.sleep(200); } catch (Exception e) {}
            VALUE = A.VALUE + 1; // forces A to initialize
        }
    }
    public static void main(String[] args) throws Exception {
        Thread ta = new Thread(() -> { int v = A.VALUE; }, "clinit-thread-A");
        Thread tb = new Thread(() -> { int v = B.VALUE; }, "clinit-thread-B");
        ta.setDaemon(true);
        tb.setDaemon(true);
        ta.start();
        Thread.sleep(50);
        tb.start();
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
