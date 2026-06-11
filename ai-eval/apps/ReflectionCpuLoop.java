// Tight loop calling Method.invoke() — CPU-hot RUNNABLE thread.
// reflection-cpu thread stays RUNNABLE burning CPU via reflective invocations.
import java.lang.reflect.Method;
public class ReflectionCpuLoop {
    static int compute(int x) { return x * x + 1; }
    public static void main(String[] args) throws Exception {
        Method m = ReflectionCpuLoop.class.getDeclaredMethod("compute", int.class);
        Thread t = new Thread(() -> {
            int x = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try { x = (int) m.invoke(null, x); } catch (Exception e) { break; }
            }
        }, "reflection-cpu");
        t.setDaemon(true);
        t.start();
        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
