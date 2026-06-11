// Reflection-loop micro-benchmark: thread calls Method.invoke in a tight loop.
// Should be flagged as CPU-hot, not as anything pathological.
import java.lang.reflect.Method;

public class ReflectionLoop {
    public static int compute(int x) { return x * 31 + 7; }

    public static void main(String[] args) throws Exception {
        Method m = ReflectionLoop.class.getMethod("compute", int.class);

        Thread spinner = new Thread(() -> {
            int x = 1;
            try {
                while (true) {
                    x = (Integer) m.invoke(null, x);
                }
            } catch (Exception e) {}
        }, "reflect-cruncher");
        spinner.setDaemon(true);
        spinner.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
