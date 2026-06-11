// Threads calling Exchanger.exchange() with timeout -- no partner
import java.util.concurrent.*;
public class ExchangerTimeout {
    static final Exchanger<Integer> EX = new Exchanger<>();
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                while (true) {
                    try { EX.exchange(42, 2, TimeUnit.SECONDS); }
                    catch (TimeoutException e) { /* retry */ }
                    catch (InterruptedException e) { break; }
                }
            }, "et-exchanger-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
