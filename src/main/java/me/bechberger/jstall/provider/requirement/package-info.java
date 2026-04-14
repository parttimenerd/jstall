/**
 * Data requirement system for recording and replaying JVM diagnostics.
 * 
 * <h2>Overview</h2>
 * This package provides a plugin-style architecture for declaring, collecting, and persisting
 * different types of diagnostic data from JVMs. New data types can be added without modifying
 * the recording/replay framework.
 * 
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link me.bechberger.jstall.provider.requirement.DataRequirement} - Interface for pluggable data types</li>
 *   <li>{@link me.bechberger.jstall.provider.requirement.CollectionSchedule} - Defines sampling strategy</li>
 *   <li>{@link me.bechberger.jstall.provider.requirement.DataRequirements} - Collection of requirements with builder API</li>
 *   <li>{@link me.bechberger.jstall.provider.requirement.CollectedData} - Container for timestamped data</li>
 * </ul>
 * 
 * <h2>Built-in Data Types</h2>
 * <ul>
 *   <li>{@link me.bechberger.jstall.provider.requirement.JcmdRequirement} - Thread dumps (Thread.print), system properties (VM.system_properties), 
 *       and any other jcmd diagnostic command</li>
 *   <li>{@link me.bechberger.jstall.provider.requirement.SystemEnvironmentRequirement} - Process list with CPU times</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * 
 * <h3>Basic Recording (with CLI options)</h3>
 * <pre>{@code
 * // In RecordCommand, count and interval come from --dump-count and --interval options:
 * int count = 5;              // from --dump-count option
 * long intervalMs = 1000;     // from --interval option
 * 
 * DataRequirements requirements = DataRequirements.builder()
 *     .withDefaults(count, intervalMs)  // Set defaults from CLI
 *     .addThreadDumps()                  // Uses defaults: 5 samples @ 1000ms
 *     .addSystemEnv()                    // Uses defaults: 5 samples @ 1000ms
 *     .addSystemProps()                  // Always once
 *     .build();
 * }</pre>
 * 
 * <h3>Custom jcmd Commands</h3>
 * <pre>{@code
 * // User runs: jstall record --include GC.class_histogram --dump-count 10 --interval 500ms
 * 
 * DataRequirements requirements = DataRequirements.builder()
 *     .withDefaults(10, 500)           // From CLI options
 *     .addThreadDumps()                 // 10 dumps @ 500ms
 *     .addJcmd("GC.class_histogram")   // 10 samples @ 500ms (uses defaults)
 *     .addJcmd("VM.native_memory", new String[]{"summary"})  // With args
 *     .build();
 * }</pre>
 * 
 * <h3>Analyzer Declaring Requirements</h3>
 * <pre>{@code
 * public class MyAnalyzer extends BaseAnalyzer {
 *     @Override
 *     public DataRequirements getDataRequirements(Map<String, Object> options) {
 *         int count = getIntOption(options, "dump-count", 3);
 *         long interval = getLongOption(options, "interval", 1000);
 *         
 *         return DataRequirements.builder()
 *             .withDefaults(count, interval)
 *             .addThreadDumps()              // Respects user's --dump-count and --interval
 *             .addSystemProps()
 *             .build();
 *     }
 * }
 * }</pre>
 * 
 * <h3>Adding New Data Types</h3>
 * To add a new data type (e.g., JFR recordings):
 * 
 * <pre>{@code
 * public class JfrRecordingRequirement implements DataRequirement {
 *     private final CollectionSchedule schedule;
 *     private final Duration duration;
 *     
 *     public JfrRecordingRequirement(Duration duration) {
 *         this.schedule = CollectionSchedule.once();  // JFR creates one recording
 *         this.duration = duration;
 *     }
 *     
 *     @Override
 *     public String getType() {
 *         return "jfr-recording";
 *     }
 *     
 *     @Override
 *     public CollectionSchedule getSchedule() {
 *         return schedule;
 *     }
 *     
 *     @Override
 *     public CollectedData collect(JMXDiagnosticHelper helper, int sampleIndex) throws IOException {
 *         // Start JFR, wait duration, dump to temp file, read bytes
 *         // Return CollectedData with JFR file content
 *     }
 *     
 *     @Override
 *     public void persist(ZipOutputStream zipOut, String pidPath, List<CollectedData> samples) {
 *         // Write to <pid>/jfr/recording.jfr
 *     }
 *     
 *     @Override
 *     public List<CollectedData> load(ZipFile zipFile, String pidPath) {
 *         // Read from <pid>/jfr/recording.jfr
 *     }
 * }
 * }</pre>
 * 
 * <h3>Then add builder method:</h3>
 * <pre>{@code
 * // In DataRequirements.Builder:
 * public Builder addJfrRecording(Duration duration) {
 *     requirements.add(new JfrRecordingRequirement(duration));
 *     return this;
 * }
 * }</pre>
 * 
 * <h2>Interval-Based Collection</h2>
 * All data types support interval-based collection, not just thread dumps. This enables:
 * <ul>
 *   <li>Tracking heap growth over time (GC.heap_info)</li>
 *   <li>Monitoring native memory leaks (VM.native_memory)</li>
 *   <li>Observing class loading patterns (GC.class_histogram)</li>
 *   <li>Watching CPU usage of system processes (SystemEnvironment)</li>
 * </ul>
 * 
 * Example:
 * <pre>{@code
 * // Track heap usage every second for 30 seconds
 * jstall record 12345 -o heap-tracking.zip --include GC.heap_info --dump-count 30 --interval 1s
 * 
 * // Monitor native memory every 100ms for 10 seconds
 * jstall record 12345 -o native-mem.zip --include VM.native_memory --dump-count 100 --interval 100ms
 * }</pre>
 * 
 * <h2>Recording Format</h2>
 * Data is stored in ZIP files with structure:
 * <pre>
 * recording.zip
 * └── recording/
 *     ├── metadata.json               # JVM list, jstall version, timestamp
 *     ├── README                      # Short format guide and project link
 *     └── &lt;pid&gt;/
 *         ├── manifest.json           # Requirements used, collection timestamps
 *         ├── thread-dumps/
 *         │   ├── 000-1234567890.txt  # Sample 0 at timestamp
 *         │   ├── 001-1234567891.txt
 *         │   └── 002-1234567892.txt
 *         ├── system-properties/
 *         │   └── data.txt
 *         ├── system-environment/
 *         │   ├── 000-1234567890.json
 *         │   └── 001-1234567891.json
 *         └── jcmd-GC_heap_info/      # Command name sanitized
 *             ├── 000-1234567890.txt
 *             └── 001-1234567891.txt
 * </pre>
 * 
 * @see me.bechberger.jstall.util.JcmdCommands for list of supported jcmd commands
 */
package me.bechberger.jstall.provider.requirement;
