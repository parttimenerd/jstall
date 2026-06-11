// Readers hold ReadLock continuously; writer starved
import java.util.concurrent.locks.*;
public class ReentrantWriteStarvation {
    static final ReentrantReadWriteLock RWL = new ReentrantReadWriteLock();
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10; i++) {
            Thread r = new Thread(() -> {
                RWL.readLock().lock();
                try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) { RWL.readLock().unlock(); }
            }, "rws-reader-" + i);
            r.setDaemon(true); r.start();
        }
        Thread.sleep(100);
        Thread w = new Thread(() -> {
            RWL.writeLock().lock();
            try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) { RWL.writeLock().unlock(); }
        }, "rws-writer");
        w.setDaemon(true); w.start();
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
