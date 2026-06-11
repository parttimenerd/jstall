// Array sorting hot-spot: threads spin sorting large arrays.
import java.util.*;
public class SortingHotLoop {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                var rng = new Random(42);
                int[] arr = new int[500_000];
                while (!Thread.currentThread().isInterrupted()) {
                    for (int j = 0; j < arr.length; j++) arr[j] = rng.nextInt();
                    Arrays.sort(arr);
                }
            }, "sort-cruncher-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
