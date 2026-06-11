// Metaspace pressure: new URLClassLoaders loaded repeatedly without being closed.
// cl-loader-N threads each spawn a fresh ClassLoader per iteration.
public class MetaspaceExhaust {
    public static void main(String[] args) throws Exception {
        java.net.URL[] urls = {MetaspaceExhaust.class.getProtectionDomain().getCodeSource().getLocation()};
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                int n = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        var cl = new java.net.URLClassLoader(urls, null);
                        cl.loadClass("Healthy");
                        // intentionally not closed — Metaspace leak
                        n++;
                        if (n % 100 == 0) Thread.sleep(10);
                    } catch (OutOfMemoryError e) {
                        try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                    } catch (Exception e) {}
                }
            }, "cl-loader-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
