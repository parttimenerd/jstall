// Actor-style: single-threaded "actor" with a bounded mailbox.
// 10 senders flood it; actor sleeps 5s per message — mailbox backs up.
import java.util.concurrent.*;
public class ActorMailboxFull {
    static final ArrayBlockingQueue<String> MAILBOX = new ArrayBlockingQueue<>(20);

    public static void main(String[] args) throws Exception {
        // Actor thread: processes one message per 2s
        Thread actor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    MAILBOX.take();
                    Thread.sleep(2000);
                } catch (InterruptedException e) { break; }
            }
        }, "actor-dispatcher");
        actor.setDaemon(true);
        actor.start();

        // Senders: each tries to put into the full mailbox
        for (int i = 0; i < 10; i++) {
            Thread sender = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try { MAILBOX.put("msg"); } catch (InterruptedException e) { break; }
                }
            }, "actor-sender-" + i);
            sender.setDaemon(true);
            sender.start();
        }

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
