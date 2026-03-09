package me.bechberger.jstall.provider.requirement;

import me.bechberger.jstall.model.SystemEnvironment;
import me.bechberger.jstall.util.JMXDiagnosticHelper;
import me.bechberger.jstall.util.json.JsonPrinter;
import me.bechberger.jstall.util.json.JsonValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Collects system environment (process list with CPU times).
 * Supports intervals for tracking CPU usage over time (needed by SystemProcessAnalyzer).
 */
public class SystemEnvironmentRequirement implements DataRequirement {
    
    private static final String TYPE = "system-environment";
    private static final String SUBDIR = "system-environment/";
    
    private final CollectionSchedule schedule;
    
    public SystemEnvironmentRequirement(CollectionSchedule schedule) {
        this.schedule = schedule;
    }
    
    @Override
    public String getType() {
        return TYPE;
    }
    
    @Override
    public CollectionSchedule getSchedule() {
        return schedule;
    }
    
    @Override
    public CollectedData collect(JMXDiagnosticHelper helper, int sampleIndex) throws IOException {
        long timestamp = System.currentTimeMillis();
        
        // Collect system environment (process list)
        SystemEnvironment env = SystemEnvironment.createCurrent();
        
        // Convert to JSON for storage
        String jsonData = systemEnvironmentToJson(env);
        
        return new CollectedData(timestamp, jsonData, java.util.Map.of());
    }
    
    private String systemEnvironmentToJson(SystemEnvironment env) {
        // Convert SystemEnvironment to JSON manually
        List<JsonValue> processes = env.processes().stream()
            .map(p -> {
                Map<String, JsonValue> pMap = new java.util.HashMap<>();
                pMap.put("pid", new JsonValue.JsonNumber(p.pid()));
                pMap.put("command", JsonValue.primitive(p.command()));
                if (p.info().startInstant().isPresent()) {
                    pMap.put("startTimeMillis", JsonValue.primitive(p.info().startInstant().get().toEpochMilli()));
                }
                if (p.info().user().isPresent()) {
                    pMap.put("user", JsonValue.primitive(p.info().user().get()));
                }
                if (p.cpuTime() != null) {
                    pMap.put("cpuTimeNanos", new JsonValue.JsonNumber(p.cpuTime().toNanos()));
                }
                return new JsonValue.JsonObject(pMap);
            })
            .collect(Collectors.toList());

        Map<String, JsonValue> root = Map.of("processes", new JsonValue.JsonArray(processes));
        return JsonPrinter.print(new JsonValue.JsonObject(root));
    }
    
    @Override
    public void persist(ZipOutputStream zipOut, String pidPath, List<CollectedData> samples) throws IOException {
        for (int i = 0; i < samples.size(); i++) {
            CollectedData sample = samples.get(i);
            String entryName = String.format("%s%s%03d-%d.json", pidPath, SUBDIR, i, sample.timestamp());
            
            ZipEntry entry = new ZipEntry(entryName);
            zipOut.putNextEntry(entry);
            zipOut.write(sample.rawData().getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
    }
    
    @Override
    public List<CollectedData> load(ZipFile zipFile, String pidPath) throws IOException {
        List<CollectedData> result = new ArrayList<>();
        String prefix = pidPath + SUBDIR;
        
        zipFile.stream()
            .filter(entry -> entry.getName().startsWith(prefix) && entry.getName().endsWith(".json"))
            .sorted((e1, e2) -> e1.getName().compareTo(e2.getName()))
            .forEach(entry -> {
                try {
                    String content = new String(
                        zipFile.getInputStream(entry).readAllBytes(),
                        StandardCharsets.UTF_8
                    );
                    
                    // Extract timestamp from filename: 000-1234567890.json
                    String filename = entry.getName().substring(prefix.length());
                    long timestamp = Long.parseLong(
                        filename.substring(filename.indexOf('-') + 1, filename.lastIndexOf('.'))
                    );
                    
                    result.add(new CollectedData(timestamp, content, java.util.Map.of()));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load system environment: " + entry.getName(), e);
                }
            });
        
        return result;
    }
}