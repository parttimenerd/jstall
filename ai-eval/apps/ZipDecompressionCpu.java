// ZIP/GZIP decompression CPU hot-spot: threads continuously inflate data.
import java.util.zip.*;
import java.io.*;
public class ZipDecompressionCpu {
    static final byte[] COMPRESSED;
    static {
        try {
            var bos = new ByteArrayOutputStream();
            var gos = new GZIPOutputStream(bos);
            gos.write(("Hello World! ").repeat(50_000).getBytes());
            gos.close();
            COMPRESSED = bos.toByteArray();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        new GZIPInputStream(new ByteArrayInputStream(COMPRESSED))
                            .transferTo(OutputStream.nullOutputStream());
                    } catch (Exception e) { break; }
                }
            }, "zip-inflater-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
