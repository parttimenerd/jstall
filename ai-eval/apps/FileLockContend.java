// Disk full / file lock: many threads waiting on FileChannel.lock() of a single file.
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

public class FileLockContend {
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempFile("filelock-eval", ".lock");
        // No deleteOnExit — we want it stable for the duration.
        File f = tmp.toFile();

        // Holder grabs the file lock and never releases. Keep raf open for life of holder.
        Thread holder = new Thread(() -> {
            try {
                RandomAccessFile raf = new RandomAccessFile(f, "rw");
                FileChannel ch = raf.getChannel();
                FileLock lock = ch.lock();
                Thread.sleep(Long.MAX_VALUE);
            } catch (Throwable e) { /* keep thread alive on error */
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ie) {}
            }
        }, "filelock-holder");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(200);

        // 6 contenders block waiting for the file lock.
        for (int i = 0; i < 6; i++) {
            final int n = i;
            Thread c = new Thread(() -> {
                try {
                    RandomAccessFile raf = new RandomAccessFile(f, "rw");
                    FileChannel ch = raf.getChannel();
                    ch.lock();  // blocks
                } catch (Throwable e) {
                    try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ie) {}
                }
            }, "filelock-contender-" + n);
            c.setDaemon(true);
            c.start();
        }

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
