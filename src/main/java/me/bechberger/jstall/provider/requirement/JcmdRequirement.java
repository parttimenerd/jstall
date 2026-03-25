package me.bechberger.jstall.provider.requirement;

import me.bechberger.jstall.util.JMXDiagnosticHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Generic requirement for any jcmd diagnostic command.
 * Supports intervals for tracking changes over time (e.g., GC.heap_info, VM.native_memory).
 * <p>
 * Examples:
 * - new JcmdRequirement("GC.heap_info", null, CollectionSchedule.intervals(5, 1000))
 * - new JcmdRequirement("VM.native_memory", new String[]{"summary"}, CollectionSchedule.once())
 */
public class JcmdRequirement implements DataRequirement {
    
    private final String command;
    private final String[] args;
    private final CollectionSchedule schedule;
    
    
    public JcmdRequirement(String command, String[] args, CollectionSchedule schedule) {
        this.command = command;
        this.args = args;
        this.schedule = schedule;
    }
    
    @Override
    public String getType() {
        // Use standardized names for common commands
        return switch (command) {
            case "Thread.print" -> "thread-dumps";
            case "VM.system_properties" -> "system-properties";
            case "VM.flags" -> "vm-flags";
            case "VM.command_line" -> "vm-command-line";
            case "GC.heap_info" -> "gc-heap-info";
            case "GC.class_histogram" -> "gc-class-histogram";
            case "GC.finalizer_info" -> "gc-finalizer-info";
            case "VM.classes" -> "vm-classes";
            case "VM.class_hierarchy" -> "vm-class-hierarchy";
            case "VM.classloader_stats" -> "vm-classloader-stats";
            case "VM.classloaders" -> "vm-classloaders";
            case "VM.metaspace" -> "vm-metaspace";
            case "VM.native_memory" -> "vm-native-memory";
            case "VM.uptime" -> "vm-uptime";
            case "VM.info" -> "vm-info";
            case "Compiler.queue" -> "compiler-queue";
            case "Compiler.codecache" -> "compiler-codecache";
            default -> sanitizeCommandName(command);
        };
    }
    
    @Override
    public CollectionSchedule getSchedule() {
        return schedule;
    }
    
    @Override
    public CollectedData collect(JMXDiagnosticHelper helper, int sampleIndex) throws IOException {
        long timestamp = System.currentTimeMillis();
        
        String result = helper.executeCommand(command, args);
        return new CollectedData(timestamp, result, java.util.Map.of());
    }
    
    @Override
    public void persist(ZipOutputStream zipOut, String pidPath, List<CollectedData> samples) throws IOException {
        // VM.flags, VM.command_line, and VM.uptime are metadata-only, not persisted as separate files
        if ("VM.flags".equals(command) || "VM.command_line".equals(command) || "VM.uptime".equals(command)) {
            return;
        }
        
        String subdir = getType() + "/";
        String extension = "txt";
        
        if (schedule.isMultiple()) {
            // Multiple samples: numbered files
            for (int i = 0; i < samples.size(); i++) {
                CollectedData sample = samples.get(i);
                String entryName = String.format("%s%s%03d-%d.%s", pidPath, subdir, i, sample.timestamp(), extension);
                
                ZipEntry entry = new ZipEntry(entryName);
                zipOut.putNextEntry(entry);
                zipOut.write(sample.rawData().getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        } else {
            // Single sample: data.txt
            if (!samples.isEmpty()) {
                CollectedData sample = samples.get(0);
                String entryName = pidPath + subdir + "data." + extension;
                
                ZipEntry entry = new ZipEntry(entryName);
                zipOut.putNextEntry(entry);
                zipOut.write(sample.rawData().getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }
    }

    @Override
    public List<CollectedData> load(ZipFile zipFile, String pidPath) throws IOException {
        List<CollectedData> result = new ArrayList<>();
        String prefix = pidPath + getType() + "/";
        String extension = "txt";
        
        zipFile.stream()
            .filter(entry -> entry.getName().startsWith(prefix) && entry.getName().endsWith("." + extension))
            .sorted(Comparator.comparing((ZipEntry e) -> e.getName()))
            .forEach(entry -> {
                try {
                    String content = new String(
                        zipFile.getInputStream(entry).readAllBytes(),
                        StandardCharsets.UTF_8
                    );
                    
                    long timestamp = 0;
                    String filename = entry.getName().substring(prefix.length());
                    
                    // Try to extract timestamp from filename
                    // Try to extract timestamp from filename: 000-1234567890.txt or data.txt
                    if (filename.contains("-") && !filename.startsWith("data.")) {
                        try {
                            timestamp = Long.parseLong(
                                filename.substring(filename.indexOf('-') + 1, filename.lastIndexOf('.'))
                            );
                        } catch (NumberFormatException ignored) {
                            // Use 0 if parsing fails
                        }
                    }
                    
                    result.add(new CollectedData(timestamp, content, java.util.Map.of()));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load jcmd data: " + entry.getName(), e);
                }
            });
        
        return result;
    }
    
    /**
     * Sanitizes command name for use in filesystem paths.
     * Replaces dots and special characters with dashes.
     */
    private String sanitizeCommandName(String cmd) {
        return cmd.replaceAll("[^a-zA-Z0-9-]", "_");
    }
    
    public String getCommand() {
        return command;
    }
    
    public String[] getArgs() {
        return args;
    }

    @Override
    public String getDirectoryDescription() {
        return switch (command) {
            case "Thread.print" -> "thread dump snapshots";
            case "VM.system_properties" -> "JVM system properties";
            default -> "jcmd " + command + " diagnostic data";
        };
    }

    @Override
    public List<String> getExpectedFiles(List<CollectedData> samples) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        String subdir = getType() + "/";
        String extension = "txt";
        List<String> files = new ArrayList<>();
        if (schedule.isMultiple()) {
            for (int i = 0; i < samples.size(); i++) {
                CollectedData sample = samples.get(i);
                files.add(String.format("%s%03d-%d.%s", subdir, i, sample.timestamp(), extension));
            }
        } else if (!samples.isEmpty()) {
            files.add(subdir + "data." + extension);
        }
        return files;
    }
}