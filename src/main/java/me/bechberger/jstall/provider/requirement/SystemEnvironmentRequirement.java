package me.bechberger.jstall.provider.requirement;

import me.bechberger.jstall.model.SystemEnvironment;
import me.bechberger.jstall.util.JMXDiagnosticHelper;
import me.bechberger.util.json.PrettyPrinter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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

        // For remote executors (e.g. SSH) we cannot use ProcessHandle, so we run ps
        // through the CommandExecutor which forwards the command to the right host.
        // For local executors we keep the richer createCurrent() path.
        SystemEnvironment env = SystemEnvironment.create(helper.getExecutor());

        String jsonData = systemEnvironmentToJson(env);
        return new CollectedData(timestamp, jsonData, java.util.Map.of());
    }
    
    private String systemEnvironmentToJson(SystemEnvironment env) {
        // Convert SystemEnvironment to JSON manually
        List<Object> processes = env.processes().stream()
            .map(p -> {
                Map<String, Object> pMap = new java.util.HashMap<>();
                pMap.put("pid", p.pid());
                pMap.put("command", p.command() == null ? "<unknown>" : p.command());
                ProcessHandle.Info info = p.info();
                if (info != null) {
                    info.startInstant().ifPresent(startInstant -> pMap.put("startTimeMillis", startInstant.toEpochMilli()));
                    info.user().ifPresent(user -> pMap.put("user", user));
                }
                if (p.cpuTime() != null) {
                    pMap.put("cpuTimeNanos", p.cpuTime().toNanos());
                }
                return pMap;
            })
            .collect(Collectors.toList());

        Map<String, Object> root = Map.of("processes", processes);
        return PrettyPrinter.prettyPrint(root);
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
            .sorted(Comparator.comparing((ZipEntry e) -> e.getName()))
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

    @Override
    public String getDirectoryDescription() {
        return "system process information";
    }

    @Override
    public List<String> getExpectedFiles(List<CollectedData> samples) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            CollectedData sample = samples.get(i);
            files.add(String.format("%s%03d-%d.json", SUBDIR, i, sample.timestamp()));
        }
        return files;
    }
}