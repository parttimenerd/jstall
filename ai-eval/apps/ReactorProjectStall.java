// Project Reactor / RxJava style: a publisher never emits,
// multiple reactor-subscriber-N threads park waiting for the value.
import java.util.concurrent.*;
public class ReactorProjectStall {
    public static void main(String[] args) throws Exception {
        final CompletableFuture<String> neverCompletes = new CompletableFuture<>();
        for (int i = 0; i < 8; i++) {
            Thread subscriber = new Thread(() -> {
                try {
                    neverCompletes.get(); // subscriber blocks waiting for publisher
                } catch (Exception e) {}
            }, "reactor-subscriber-" + i);
            subscriber.setDaemon(true);
            subscriber.start();
        }
        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
