// Thread catches StackOverflowError and retries infinitely — CPU hot with deep stacks.
// soe-retry thread keeps triggering StackOverflow and retrying, staying RUNNABLE.
public class StackOverflowRetry {
    static int recurse(int depth) {
        return recurse(depth + 1) + depth; // intentional infinite recursion
    }
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    recurse(0);
                } catch (StackOverflowError e) {
                    // caught and retried — deliberately ignores the error
                }
            }
        }, "soe-retry");
        t.setDaemon(true);
        t.start();
        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
