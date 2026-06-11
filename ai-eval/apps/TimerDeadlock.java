// java.util.Timer task that tries to acquire a lock held by the thread scheduling it → deadlock.
import java.util.*;

public class TimerDeadlock {
    static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        Timer timer = new Timer("timer-thread", true);

        Thread holder = new Thread(() -> {
            synchronized (lock) {
                // Schedule a timer task that also wants the lock, then wait forever
                timer.schedule(new TimerTask() {
                    @Override public void run() {
                        synchronized (lock) {  // will block waiting for holder
                            System.out.println("task ran");
                        }
                    }
                }, 100);
                try { Thread.sleep(120_000); } catch (InterruptedException e) {}
            }
        }, "lock-holder");
        holder.setDaemon(true);
        holder.start();

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
