// Semaphore exhaustion: all permits taken, many acquirers blocked.
import java.util.concurrent.*;

public class SemaphoreExhaust {
    public static void main(String[] args) throws Exception {
        Semaphore sem = new Semaphore(2);

        // 2 holders take all permits and never release.
        for (int i = 0; i < 2; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try { sem.acquire(); } catch (InterruptedException e) { return; }
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
            }, "sem-holder-" + n);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(100);

        // 10 acquirers block waiting for a permit.
        for (int i = 0; i < 10; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try { sem.acquire(); } catch (InterruptedException e) {}
            }, "sem-acquirer-" + n);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
