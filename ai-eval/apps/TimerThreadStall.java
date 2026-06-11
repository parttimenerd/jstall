// Single Timer with one slow TimerTask that blocks all others
import java.util.*;
public class TimerThreadStall {
    public static void main(String[] args) throws Exception {
        Timer timer = new Timer("tts-timer", true);
        // Slow task blocks timer thread
        timer.schedule(new TimerTask() {
            public void run() {
                Thread.currentThread().setName("tts-slow-task");
                try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {}
            }
        }, 0);
        Thread.sleep(100);
        // These never run because slow task holds timer thread
        for (int i = 0; i < 5; i++) {
            final int n = i;
            timer.schedule(new TimerTask() {
                public void run() { System.out.println("blocked-task-" + n + " ran"); }
            }, 0);
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
