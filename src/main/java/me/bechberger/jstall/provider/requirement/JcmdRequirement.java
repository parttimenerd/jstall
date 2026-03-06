package me.bechberger.jstall.provider.requirement;

import me.bechberger.jstall.util.JMXDiagnosticHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Generic requirement for any jcmd diagnostic command.
 * Supports intervals for tracking changes over time (e.g., GC.heap_info, VM.native_memory).
 * 
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
        // Use friendly names for common commands
        return switch (command) {
            case "Thread.print" -> "thread-dumps";
            case "VM.system_properties" -> "system-properties";
            default -> "jcmd-" + sanitizeCommandName(command);
        };
    }
    
    @Override
    public CollectionSchedule getSchedule() {
        return schedule;
    }
    
    @Override
    public CollectedData collect(JMXDiagnosticHelper helper, int sampleIndex) throws IOException {
        long timestamp = System.currentTimeMillis();

        String mbeanCommand = resolveMBeanCommand(command);
        
        String result = helper.executeCommand(mbeanCommand, command, args);
        return new CollectedData(timestamp, result, java.util.Map.of());
    }

    static String resolveMBeanCommand(String command) {
        if (command == null || command.isEmpty()) return command;
        return transformJcmdToMBeanName(command);
    }

    /**
     * Transform the original jcmd command name into the JMX operation name
     * using the same rules as DiagnosticCommandImpl:
     * - lowercase the entire first segment (before the first '.' or '_')
     * - remove '.' and '_' and uppercase the character following each separator
     * Examples:
     *  "VM.system_properties" -> "vmSystemProperties"
     *  "GC.heap_dump" -> "gcHeapDump"
     */
    static String transformJcmdToMBeanName(String cmd) {
        StringBuilder out = new StringBuilder();
        boolean inFirstSegment = true;
        boolean capitalizeNext = false;

        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '.' || c == '_') {
                // separators are removed and next character is capitalized
                inFirstSegment = false;
                capitalizeNext = true;
                continue;
            }

            if (capitalizeNext) {
                out.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else if (inFirstSegment) {
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }

        return out.toString();
    }
    
    @Override
    public void persist(ZipOutputStream zipOut, String pidPath, List<CollectedData> samples) throws IOException {
        String subdir = getType() + "/";
        String extension = getFileExtension();
        
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
    
    private String getFileExtension() {
        // Thread dumps traditionally saved as .txt
        if ("Thread.print".equals(command)) {
            return "txt";
        }
        // Everything else is also text
        return "txt";
    }
    
    @Override
    public List<CollectedData> load(ZipFile zipFile, String pidPath) throws IOException {
        List<CollectedData> result = new ArrayList<>();
        String prefix = pidPath + getType() + "/";
        String extension = getFileExtension();
        
        zipFile.stream()
            .filter(entry -> entry.getName().startsWith(prefix) && entry.getName().endsWith("." + extension))
            .sorted((e1, e2) -> e1.getName().compareTo(e2.getName()))
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
}
