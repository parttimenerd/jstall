// TimerTask running, another thread cancels it -- timer thread loops
import java.util.*;
public class TimerCancelRace {
    public static void main(String[] args) throws Exception {
        Timer timer = new Timer("tcr-timer", true);
        for (int i = 0; i < 8; i++) {
            final int n = i;
            Thread canceller = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    TimerTask task = new TimerTask() { public void run() {
                        try { Thread.sleep(200); } catch (Exception e) {}
                    }};
                    timer.schedule(task, 0);
                    try { Thread.sleep(50); task.cancel(); } catch (Exception e) {}
                }
            }, "tcr-canceller-" + n);
            canceller.setDaemon(true); canceller.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
