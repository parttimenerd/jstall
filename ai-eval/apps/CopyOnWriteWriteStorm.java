// 20 threads writing to CopyOnWriteArrayList -- expensive copy every write
import java.util.concurrent.*;
public class CopyOnWriteWriteStorm {
    static final CopyOnWriteArrayList<Integer> LIST = new CopyOnWriteArrayList<>();
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) LIST.add(1);
            }, "cows-writer-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
