// Semaphore released more times than acquired (bug); other threads spin acquiring extra permits.
// sem-over-release releases too many permits; sem-spin-acquire threads spin burning CPU.
import java.util.concurrent.Semaphore;
public class SemaphoreReleaseBug {
    public static void main(String[] args) throws Exception {
        Semaphore sem = new Semaphore(1);
        // Over-release bug: release 5 extra permits
        Thread overRelease = new Thread(() -> {
            for (int i = 0; i < 6; i++) sem.release(); // releases without acquire!
        }, "sem-over-release");
        overRelease.setDaemon(true);
        overRelease.start();
        Thread.sleep(100);
        // Spin-acquire threads: burn CPU acquiring and immediately releasing the leaked permits
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    if (sem.tryAcquire()) {
                        sem.release(); // immediately put back — tight spin
                    }
                }
            }, "sem-spin-acquire-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
