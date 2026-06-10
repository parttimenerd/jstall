// One thread spinning a tight CPU loop. AI should identify the hot thread by name.
public class HotLoop {
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            long x = 0;
            while (true) {
                x += System.nanoTime() ^ (x * 31);
                if ((x & 0x7fffffff) == 0) System.out.println(x);
            }
        }, "cpu-burner");
        t.setDaemon(true);
        t.start();
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
