// Continuous class loading and unloading: a new ClassLoader per request,
// each loading the same class. Old loaders never GC'd — Metaspace + heap leak.
public class ClassLoaderLeak {
    public static void main(String[] args) throws Exception {
        Thread spawner = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Each iteration creates a new isolated ClassLoader that loads a class
                    ClassLoader cl = new java.net.URLClassLoader(
                        new java.net.URL[]{ClassLoaderLeak.class.getProtectionDomain().getCodeSource().getLocation()},
                        null // no parent — isolated
                    );
                    cl.loadClass("Healthy"); // load something simple
                    // ClassLoader intentionally not closed — leaks Metaspace
                    Thread.sleep(50);
                } catch (Exception e) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                }
            }
        }, "cl-leak-spawner");
        spawner.setDaemon(true);
        spawner.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
