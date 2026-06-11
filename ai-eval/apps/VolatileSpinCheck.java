// Thread spins on while (!volatile_flag) with no sleep — pure busy-wait.
// volatile-spin thread stays RUNNABLE burning CPU polling a volatile boolean.
public class VolatileSpinCheck {
    static volatile boolean done = false;
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            long counter = 0;
            while (!done) { // no sleep — hot busy-wait on volatile
                counter++;
            }
        }, "volatile-spin");
        t.setDaemon(true);
        t.start();
        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
