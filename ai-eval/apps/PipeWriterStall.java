// PipedOutputStream writer blocks because reader never reads
import java.io.*;
public class PipeWriterStall {
    public static void main(String[] args) throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 1024);
        Thread slowReader = new Thread(() -> {
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
        }, "pws-slow-reader");
        slowReader.setDaemon(true); slowReader.start();
        for (int i = 0; i < 4; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    while (true) out.write(buf); // blocks when pipe buffer full
                } catch (Exception e) {}
            }, "pws-writer-" + n);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
