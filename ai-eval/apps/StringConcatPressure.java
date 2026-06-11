// Tight loop using String += -- heap churn
public class StringConcatPressure {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    String s = "";
                    for (int j = 0; j < 100; j++) s += "x"; // creates 100 string objects
                }
            }, "scp-stringer-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
