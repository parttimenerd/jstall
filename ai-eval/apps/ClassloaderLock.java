// Classloader lock contention: many threads loading classes via reflection
// pile up on the classloader's per-class lock.
import java.util.concurrent.*;

public class ClassloaderLock {
    public static void main(String[] args) throws Exception {
        // Pre-spawn a custom classloader with a slow defineClass path.
        ClassLoader slow = new ClassLoader() {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                synchronized (this) {
                    try { Thread.sleep(60_000); } catch (InterruptedException e) {}
                }
                return super.findClass(name);
            }
        };

        // 12 threads all try to load the same not-yet-loaded class from the slow loader.
        ExecutorService pool = Executors.newFixedThreadPool(12, r -> {
            Thread t = new Thread(r, "cl-loader-" + System.nanoTime() % 10_000);
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < 12; i++) {
            pool.submit(() -> {
                try {
                    Class.forName("foo.bar.NeverExisted_" + Thread.currentThread().getId(), true, slow);
                } catch (Throwable e) {}
            });
        }

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
