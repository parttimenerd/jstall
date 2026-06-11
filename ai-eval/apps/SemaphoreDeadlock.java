// ABBA deadlock with two semaphores
import java.util.concurrent.*;
public class SemaphoreDeadlock {
    static final Semaphore semA = new Semaphore(1), semB = new Semaphore(1);
    public static void main(String[] args) throws Exception {
        Thread a = new Thread(() -> {
            try { semA.acquire(); Thread.sleep(100); semB.acquire(); }
            catch (InterruptedException e) {}
        }, "sd-thread-a");
        Thread b = new Thread(() -> {
            try { semB.acquire(); Thread.sleep(100); semA.acquire(); }
            catch (InterruptedException e) {}
        }, "sd-thread-b");
        a.setDaemon(true); b.setDaemon(true);
        a.start(); b.start();
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
