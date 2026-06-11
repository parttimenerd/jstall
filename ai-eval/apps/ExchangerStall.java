// Exchanger where producers are ready but no consumers arrive — all producers park.
import java.util.concurrent.Exchanger;

public class ExchangerStall {
    static final Exchanger<String> exchanger = new Exchanger<>();

    public static void main(String[] args) throws Exception {
        // Start 5 producer threads — they all call exchange() but no consumer ever arrives
        for (int i = 0; i < 5; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                try {
                    exchanger.exchange("data-" + id);  // parks forever
                } catch (InterruptedException e) {}
            }, "ex-producer-" + id);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
