// Many threads loading classes in parallel via URLClassLoader — lock contention on classloader.
// classload-worker-N threads all call loadClass() on the same loader concurrently.
import java.net.*;
import java.util.concurrent.*;
public class ClassLoadStorm {
    public static void main(String[] args) throws Exception {
        URL[] urls = { ClassLoadStorm.class.getProtectionDomain().getCodeSource().getLocation() };
        // Use a single shared loader so threads contend on its lock
        URLClassLoader loader = new URLClassLoader(urls, null);
        String[] classNames = {
            "java.lang.String", "java.util.ArrayList", "java.util.HashMap",
            "java.util.LinkedList", "java.util.TreeMap", "java.util.HashSet",
            "java.util.LinkedHashMap", "java.util.TreeSet", "java.util.PriorityQueue",
            "java.util.ArrayDeque"
        };
        ExecutorService ex = Executors.newFixedThreadPool(10, r -> {
            Thread t = new Thread(r); t.setDaemon(true);
            t.setName("classload-worker-" + t.getId()); return t;
        });
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            ex.submit(() -> {
                try {
                    start.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        // Force re-loading by creating new loaders repeatedly
                        URLClassLoader l = new URLClassLoader(urls, null);
                        l.loadClass(classNames[idx % classNames.length]);
                    }
                } catch (Exception e) {}
            });
        }
        start.countDown();
        Thread.sleep(1500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
