// Threads doing random reads on byte array -- pure CPU
import java.util.Random;
public class RandomAccessCpu {
    static final byte[] DATA = new byte[64 * 1024 * 1024]; // 64MB
    public static void main(String[] args) throws Exception {
        new Random().nextBytes(DATA);
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                Random rng = new Random();
                long sum = 0;
                while (!Thread.currentThread().isInterrupted()) sum += DATA[rng.nextInt(DATA.length)];
            }, "rac-reader-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
