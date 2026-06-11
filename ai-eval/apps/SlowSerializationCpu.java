// Serialization CPU hot-spot: repeated Java object serialization/deserialization
// in a tight loop — produces high CPU on ser-worker threads.
import java.io.*;
import java.util.*;
public class SlowSerializationCpu {
    static class BigObject implements Serializable {
        List<String> data = new ArrayList<>(Collections.nCopies(500, "payload-string-data"));
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                var obj = new BigObject();
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        var bos = new ByteArrayOutputStream(65536);
                        new ObjectOutputStream(bos).writeObject(obj);
                        var bytes = bos.toByteArray();
                        new ObjectInputStream(new ByteArrayInputStream(bytes)).readObject();
                    } catch (Exception e) { break; }
                }
            }, "ser-worker-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
