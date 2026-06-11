// Catastrophic backtracking regex: threads stuck in Pattern.matcher.matches()
// for exponentially long on a crafted input — CPU hot-spot via regex engine.
public class RegexCatastrophic {
    public static void main(String[] args) throws Exception {
        // Classic catastrophic backtracking: (a+)+ on "aaaaaaaaaaaaaaaaaaaaaaab"
        String pattern = "(a+)+";
        String input = "a".repeat(28) + "b"; // 28 chars forces exponential backtracking

        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    java.util.regex.Pattern.compile(pattern).matcher(input).matches();
                }
            }, "regex-cruncher-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
