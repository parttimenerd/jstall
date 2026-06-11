// 30 threads writing to System.out (synchronized PrintStream)
public class PrintStreamContend {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 30; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    System.out.println(Thread.currentThread().getName() + " writing");
                }
            }, "psc-writer-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
