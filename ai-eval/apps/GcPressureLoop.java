// Threads creating/clearing large ArrayLists -- continuous GC
import java.util.*;
public class GcPressureLoop {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    List<byte[]> list = new ArrayList<>();
                    for (int j = 0; j < 500; j++) list.add(new byte[4096]);
                    list.clear();
                }
            }, "gpl-churner-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
