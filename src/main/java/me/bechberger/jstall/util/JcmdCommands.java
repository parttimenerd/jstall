package me.bechberger.jstall.util;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registry of common jcmd diagnostic commands.
 * Used for validation and documentation.
 */
public class JcmdCommands {
    
    /**
     * Information about a jcmd command.
     */
    public record CommandInfo(String name, String description, boolean supportsIntervals) {}
    
    /**
     * Common jcmd commands that are useful for recording.
     */
    private static final List<CommandInfo> COMMANDS = List.of(
        // Thread and concurrency
        new CommandInfo("Thread.print", "Thread dump with stack traces", true),
        
        // GC and memory
        new CommandInfo("GC.heap_info", "Heap configuration and usage", true),
        new CommandInfo("GC.class_histogram", "Class instance and memory histogram", true),
        new CommandInfo("GC.run", "Trigger garbage collection", false),
        new CommandInfo("GC.finalizer_info", "Information about objects pending finalization", true),
        
        // System and VM info
        new CommandInfo("VM.system_properties", "System properties", false),
        new CommandInfo("VM.flags", "VM flags and settings", false),
        new CommandInfo("VM.command_line", "VM command line arguments", false),
        new CommandInfo("VM.version", "VM version information", false),
        new CommandInfo("VM.uptime", "VM uptime", false),
        
        // Native memory tracking (requires -XX:NativeMemoryTracking=summary or detail)
        new CommandInfo("VM.native_memory", "Native memory tracking (requires NMT enabled)", true),
        
        // Classloading
        new CommandInfo("VM.classloader_stats", "Classloader statistics", true),
        new CommandInfo("VM.class_hierarchy", "Class hierarchy", false),
        
        // Compiler
        new CommandInfo("Compiler.codecache", "Code cache usage", true),
        new CommandInfo("Compiler.codelist", "Compiled methods", false),
        new CommandInfo("Compiler.queue", "Compilation queue", true),
        
        // JFR (if available)
        new CommandInfo("JFR.start", "Start JFR recording", false),
        new CommandInfo("JFR.stop", "Stop JFR recording", false),
        new CommandInfo("JFR.dump", "Dump JFR recording", false),
        
        // JVMTI
        new CommandInfo("JVMTI.agent_load", "Load JVMTI agent", false),
        new CommandInfo("JVMTI.data_dump", "Trigger JVMTI data dump", false)
    );
    
    private static final Set<String> COMMAND_NAMES = COMMANDS.stream()
        .map(CommandInfo::name)
        .collect(Collectors.toSet());
    
    private static final Map<String, CommandInfo> BY_NAME = COMMANDS.stream()
        .collect(Collectors.toMap(CommandInfo::name, c -> c));
    
    /**
     * Returns all known jcmd commands.
     */
    public static List<CommandInfo> getAllCommands() {
        return COMMANDS;
    }
    
    /**
     * Returns commands that are recommended for interval-based collection.
     */
    public static List<CommandInfo> getIntervalCommands() {
        return COMMANDS.stream()
            .filter(CommandInfo::supportsIntervals)
            .collect(Collectors.toList());
    }
    
    /**
     * Checks if a command name is known.
     */
    public static boolean isKnown(String command) {
        return COMMAND_NAMES.contains(command);
    }
    
    /**
     * Gets command info by name.
     */
    public static CommandInfo getInfo(String command) {
        return BY_NAME.get(command);
    }
    
    /**
     * Validates a command name and provides suggestions if invalid.
     * 
     * @param command Command to validate
     * @return null if valid, error message with suggestions if invalid
     */
    public static String validate(String command) {
        if (command == null || command.isBlank()) {
            return "Command cannot be empty";
        }
        
        if (isKnown(command)) {
            return null; // Valid
        }
        
        // Provide suggestions for similar commands
        String lower = command.toLowerCase();
        List<String> suggestions = COMMAND_NAMES.stream()
            .filter(c -> c.toLowerCase().contains(lower) || lower.contains(c.toLowerCase()))
            .limit(3)
            .collect(Collectors.toList());
        
        if (!suggestions.isEmpty()) {
            return String.format("Unknown command '%s'. Did you mean: %s?", 
                command, String.join(", ", suggestions));
        }
        
        return String.format("Unknown command '%s'. Use 'jcmd <pid> help' to see available commands.", 
            command);
    }
    
    /**
     * Formats command list for display.
     */
    public static String formatCommandList() {
        StringBuilder sb = new StringBuilder();
        sb.append("Common jcmd commands for recording:\n\n");
        
        // Group by category
        sb.append("Memory & GC:\n");
        COMMANDS.stream()
            .filter(c -> c.name().startsWith("GC.") || c.name().contains("heap") || c.name().contains("memory"))
            .forEach(c -> sb.append(String.format("  %-30s %s%s\n", 
                c.name(), c.description(), c.supportsIntervals() ? " [intervals]" : "")));
        
        sb.append("\nVM & System:\n");
        COMMANDS.stream()
            .filter(c -> c.name().startsWith("VM."))
            .forEach(c -> sb.append(String.format("  %-30s %s%s\n", 
                c.name(), c.description(), c.supportsIntervals() ? " [intervals]" : "")));
        
        sb.append("\nCompiler:\n");
        COMMANDS.stream()
            .filter(c -> c.name().startsWith("Compiler."))
            .forEach(c -> sb.append(String.format("  %-30s %s%s\n", 
                c.name(), c.description(), c.supportsIntervals() ? " [intervals]" : "")));
        
        return sb.toString();
    }
}