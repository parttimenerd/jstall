package me.bechberger.jstall.provider.requirement;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Collection of data requirements for a recording session.
 * Provides a builder API for declaring data needs.
 * <p>
 * Example usage:
 * <pre>{@code
 * DataRequirements requirements = DataRequirements.builder()
 *     .addThreadDumps(3, 1000)
 *     .addSystemProps()
 *     .addSystemEnv(3, 1000)
 *     .addJcmd("GC.heap_info", 5, 1000)
 *     .build();
 * }</pre>
 */
public class DataRequirements {
    
    private final Set<DataRequirement> requirements;
    
    private DataRequirements(Set<DataRequirement> requirements) {
        this.requirements = requirements;
    }
    
    /**
     * Returns all requirements in this collection.
     */
    public Set<DataRequirement> getRequirements() {
        return requirements;
    }
    
    /**
     * Returns the maximum duration needed to collect all requirements.
     */
    public long getDuration() {
        return requirements.stream()
            .mapToLong(r -> r.getSchedule().totalDurationMs())
            .max()
            .orElse(0L);
    }
    
    /**
     * Merges this requirements with another, taking the maximum count
     * and minimum interval for each requirement type.
     */
    public DataRequirements merge(DataRequirements other) {
        Set<DataRequirement> merged = new HashSet<>(this.requirements);
        
        for (DataRequirement otherReq : other.requirements) {
            DataRequirement existing = findByType(merged, otherReq.getType());
            
            if (existing == null) {
                merged.add(otherReq);
            } else {
                // Merge schedules: max count, min interval
                CollectionSchedule existingSched = existing.getSchedule();
                CollectionSchedule otherSched = otherReq.getSchedule();
                
                int maxCount = Math.max(existingSched.count(), otherSched.count());
                long minInterval = existingSched.intervalMs() == 0 ? otherSched.intervalMs() :
                                  otherSched.intervalMs() == 0 ? existingSched.intervalMs() :
                                  Math.min(existingSched.intervalMs(), otherSched.intervalMs());
                
                CollectionSchedule mergedSched = new CollectionSchedule(
                    maxCount, minInterval, true
                );
                
                // Remove old, add new with merged schedule
                merged.remove(existing);
                merged.add(createWithNewSchedule(otherReq, mergedSched));
            }
        }
        
        return new DataRequirements(merged);
    }
    
    private DataRequirement findByType(Set<DataRequirement> reqs, String type) {
        return reqs.stream()
            .filter(r -> r.getType().equals(type))
            .findFirst()
            .orElse(null);
    }
    
    private DataRequirement createWithNewSchedule(DataRequirement req, CollectionSchedule schedule) {
        if (req instanceof SystemEnvironmentRequirement) {
            return new SystemEnvironmentRequirement(schedule);
        } else if (req instanceof JcmdRequirement jcmd) {
            return new JcmdRequirement(jcmd.getCommand(), jcmd.getArgs(), schedule);
        } else if (req instanceof AsyncProfilerWindowRequirement profileRequirement) {
            return new AsyncProfilerWindowRequirement(schedule, profileRequirement.getEvent());
        }
        // Keep original for requirements that don't support schedule changes
        return req;
    }
    
    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates an empty requirements set.
     */
    public static DataRequirements empty() {
        return new DataRequirements(Set.of());
    }
    
    /**
     * Builder for DataRequirements.
     */
    public static class Builder {
        private final Set<DataRequirement> requirements = new HashSet<>();
        private int defaultCount = 3;
        private long defaultIntervalMs = 1000;
        
        /**
         * Sets default count and interval for interval-based requirements.
         * These defaults are typically derived from CLI options like --count and --interval.
         * 
         * @param count Default number of samples (from --count option)
         * @param intervalMs Default interval in milliseconds (from --interval option)
         */
        public Builder withDefaults(int count, long intervalMs) {
            this.defaultCount = count;
            this.defaultIntervalMs = intervalMs;
            return this;
        }
        
        /**
         * Adds thread dump collection using default schedule.
         * Thread dumps are collected via Thread.print jcmd command.
         */
        public Builder addThreadDumps() {
            return addThreadDumps(defaultCount, defaultIntervalMs);
        }
        
        /**
         * Adds thread dump collection at intervals.
         * Thread dumps are collected via Thread.print jcmd command.
         * 
         * @param count Number of dumps to collect
         * @param intervalMs Interval between dumps in milliseconds
         */
        public Builder addThreadDumps(int count, long intervalMs) {
            requirements.add(new JcmdRequirement(
                "Thread.print", null, CollectionSchedule.intervals(count, intervalMs)
            ));
            return this;
        }
        
        /**
         * Adds a single thread dump collection.
         */
        public Builder addThreadDump() {
            requirements.add(new JcmdRequirement("Thread.print", null, CollectionSchedule.once()));
            return this;
        }
        
        /**
         * Adds system properties collection (always collected once).
         * Properties are collected via VM.system_properties jcmd command.
         */
        public Builder addSystemProps() {
            requirements.add(new JcmdRequirement("VM.system_properties", null, CollectionSchedule.once()));
            return this;
        }
        
        /**
         * Adds system environment collection using default schedule.
         */
        public Builder addSystemEnv() {
            return addSystemEnv(defaultCount, defaultIntervalMs);
        }
        
        /**
         * Adds system environment collection at intervals.
         * 
         * @param count Number of samples to collect
         * @param intervalMs Interval between samples in milliseconds
         */
        public Builder addSystemEnv(int count, long intervalMs) {
            requirements.add(new SystemEnvironmentRequirement(
                CollectionSchedule.intervals(count, intervalMs)
            ));
            return this;
        }
        
        /**
         * Adds a single system environment collection.
         */
        public Builder addSystemEnvOnce() {
            requirements.add(new SystemEnvironmentRequirement(CollectionSchedule.once()));
            return this;
        }

        public Builder addAsyncProfilerWindows() {
            if (defaultCount > 1) {
                requirements.add(AsyncProfilerWindowRequirement.forSampling(defaultCount, defaultIntervalMs));
            }
            return this;
        }

        /**
         * Adds fast/inexpensive jcmd commands that are safe to collect at startup.
         * These commands typically complete in &lt; 300ms and provide useful diagnostic info.
         * Commands included: VM.info, GC.heap_info, Compiler.queue, 
         * VM.classloader_stats, VM.metaspace (VM.flags, VM.command_line, and VM.uptime go to metadata.json instead).
         */
        public Builder addFastVmInfo() {
            // Single collection of these info commands at start
            addJcmdOnce("VM.info");
            addJcmdOnce("GC.heap_info");
            addJcmdOnce("Compiler.queue");
            addJcmdOnce("VM.classloader_stats");
            addJcmdOnce("VM.metaspace");
            // Note: VM.flags, VM.command_line, and VM.uptime are stored in metadata.json
            // Note: VM.native_memory only collected if NMT is enabled (added conditionally)
            return this;
        }
        
        /**
         * Conditionally adds VM.native_memory (with summary option) if native memory tracking is enabled.
         * This is called during recording setup based on JVM flags.
         */
        public Builder addNativeMemoryIfEnabled(boolean trackingEnabled) {
            if (trackingEnabled) {
                addJcmd("VM.native_memory", new String[]{"summary"}, 1, 0);
            }
            return this;
        }
        
        /**
         * Adds a jcmd command collection using default schedule.
         * This is the typical way to add jcmd commands in recording scenarios.
         * <p>
         * Example: builder.addJcmd("GC.class_histogram")
         * 
         * @param command jcmd command name (e.g., "GC.class_histogram", "VM.native_memory")
         */
        public Builder addJcmd(String command) {
            return addJcmd(command, defaultCount, defaultIntervalMs);
        }
        
        /**
         * Adds a jcmd command collection with specific arguments using default schedule.
         * <p>
         * Example: builder.addJcmd("VM.native_memory", new String[]{"summary"})
         * 
         * @param command jcmd command name
         * @param args Command arguments
         */
        public Builder addJcmd(String command, String[] args) {
            return addJcmd(command, args, defaultCount, defaultIntervalMs);
        }
        
        /**
         * Adds a generic jcmd command collection at intervals.
         * 
         * @param command jcmd command name (e.g., "GC.heap_info")
         * @param count Number of samples to collect
         * @param intervalMs Interval between samples in milliseconds
         */
        public Builder addJcmd(String command, int count, long intervalMs) {
            requirements.add(new JcmdRequirement(
                command, null, CollectionSchedule.intervals(count, intervalMs)
            ));
            return this;
        }
        
        /**
         * Adds a generic jcmd command collection with arguments at intervals.
         * 
         * @param command jcmd command name
         * @param args Command arguments
         * @param count Number of samples to collect
         * @param intervalMs Interval between samples in milliseconds
         */
        public Builder addJcmd(String command, String[] args, int count, long intervalMs) {
            requirements.add(new JcmdRequirement(
                command, args, CollectionSchedule.intervals(count, intervalMs)
            ));
            return this;
        }
        
        /**
         * Adds a single jcmd command collection (no intervals).
         * 
         * @param command jcmd command name (e.g., "VM.flags")
         */
        public Builder addJcmdOnce(String command) {
            requirements.add(new JcmdRequirement(command, null, CollectionSchedule.once()));
            return this;
        }
        
        /**
         * Builds the DataRequirements.
         */
        public DataRequirements build() {
            return new DataRequirements(new HashSet<>(requirements));
        }
    }
    
    @Override
    public String toString() {
        return requirements.stream()
            .map(DataRequirement::getDescription)
            .collect(Collectors.joining(", ", "DataRequirements[", "]"));
    }
}