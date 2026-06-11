// Modern app using virtual threads — many mounted on carrier threads, all healthy sleeping.
import java.util.concurrent.*;

public class VirtualThreadsHealthy {
    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                try { Thread.sleep(120_000); } catch (InterruptedException e) {}
            });
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
