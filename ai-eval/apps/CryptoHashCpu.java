// Cryptographic hashing CPU hot-spot: threads spin computing SHA-256.
import java.security.*;
public class CryptoHashCpu {
    public static void main(String[] args) throws Exception {
        byte[] data = new byte[65536];
        new java.util.Random(42).nextBytes(data);

        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                try {
                    var md = MessageDigest.getInstance("SHA-256");
                    while (!Thread.currentThread().isInterrupted()) {
                        md.reset();
                        md.update(data);
                        md.digest();
                    }
                } catch (Exception e) {}
            }, "crypto-hasher-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
