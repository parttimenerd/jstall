// Threads blocked on BufferedReader.readLine() on PipedInputStream that never produces
import java.io.*;
public class BufferedReaderStall {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 6; i++) {
            PipedOutputStream out = new PipedOutputStream();
            PipedInputStream in = new PipedInputStream(out);
            Thread t = new Thread(() -> {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in));
                    r.readLine(); // blocks forever
                } catch (Exception e) {}
            }, "brs-reader-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
