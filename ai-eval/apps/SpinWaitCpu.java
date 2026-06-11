// Thread busy-spinning waiting for a volatile flag that is never set — CPU hot-spot, no computation.
public class SpinWaitCpu {
    static volatile boolean done = false;

    public static void main(String[] args) throws Exception {
        // Two spinners so the signal is clearly visible in the table
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                while (!done) ; // pure spin: no sleep, no work, just polls the flag
            }, "spin-waiter-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
