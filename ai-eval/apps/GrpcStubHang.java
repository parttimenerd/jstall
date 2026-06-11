// gRPC-style async stub: many calls in-flight with no server responding.
// grpc-call-N threads park in CountDownLatch waiting for response.
import java.util.concurrent.*;
public class GrpcStubHang {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10; i++) {
            final CountDownLatch responseLatch = new CountDownLatch(1);
            Thread caller = new Thread(() -> {
                try {
                    responseLatch.await(); // waits forever — no response
                } catch (InterruptedException e) {}
            }, "grpc-call-" + i);
            caller.setDaemon(true);
            caller.start();
        }
        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
