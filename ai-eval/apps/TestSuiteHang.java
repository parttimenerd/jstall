// JUnit-style test suite: many short-lived test threads, one of them hangs in synchronized.
// Simulates "test suite running" — most threads healthy, one stuck.
public class TestSuiteHang {
    static final Object DB_LOCK = new Object();

    static void runTest(String name, boolean hangs) {
        synchronized (DB_LOCK) {
            if (hangs) {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
            } else {
                long s = System.nanoTime();
                while (System.nanoTime() - s < 10_000_000) {}
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // First, launch the hung test that holds DB_LOCK forever.
        Thread hang = new Thread(() -> runTest("HangingTest.testThatHangs", true), "test-HangingTest.testThatHangs");
        hang.setDaemon(true);
        hang.start();
        Thread.sleep(50);  // ensure it acquires the lock

        // Then 6 sibling tests trying to acquire DB_LOCK — they pile up BLOCKED.
        String[] testNames = {
            "UserRepoTest.testFind",
            "UserRepoTest.testSave",
            "OrderServiceTest.testCheckout",
            "OrderServiceTest.testRefund",
            "AuthControllerTest.testLogin",
            "AuthControllerTest.testLogout",
        };
        for (String n : testNames) {
            Thread t = new Thread(() -> runTest(n, false), "test-" + n);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
