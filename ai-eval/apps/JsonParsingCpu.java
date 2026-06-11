// JSON parsing CPU hot-spot using only JDK (no external lib).
// Threads spin parsing and re-serializing a large JSON-like string manually.
public class JsonParsingCpu {
    // Generate a large JSON string
    static String makeJson() {
        var sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 5000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(i).append(",\"name\":\"item-").append(i)
              .append("\",\"value\":").append(i * 3.14).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String json = makeJson();
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    // Manual scan — simulates parser overhead
                    int count = 0;
                    for (int j = 0; j < json.length(); j++) {
                        if (json.charAt(j) == '{') count++;
                    }
                }
            }, "json-parser-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
