package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.util.CompilerQueueParser;
import me.bechberger.jstall.util.CompilerQueueParser.CompilerQueueSnapshot;
import me.bechberger.jstall.util.CompilerQueueParser.CompileTask;
import me.bechberger.jstall.util.TablePrinter;

import java.time.Instant;
import java.util.*;

/**
 * Analyzes compiler queue state over time using Compiler.queue jcmd output.
 * <p>
 * Shows full trend across all collected samples: active compilations and queued tasks per queue type.
 */
public class CompilerQueueAnalyzer implements Analyzer {

    @Override
    public String name() {
        return "compiler-queue";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of("samples", "interval");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.ANY;
    }

    @Override
    public DataRequirements getDataRequirements(Map<String, Object> options) {
        int samples = getIntOption(options, "samples", 3);
        long intervalMs = getLongOption(options, "interval", 2000L);
        
        return DataRequirements.builder()
                .addThreadDump()
                .addJcmd("Compiler.queue", samples, intervalMs)
                .build();
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        List<CollectedData> samples = data.collectedData("compiler-queue");
        if (samples.isEmpty()) {
            return AnalyzerResult.nothing();
        }

        String output = formatCompilerQueueTrend(samples);
        if (output.isEmpty()) {
            return AnalyzerResult.ok("Compiler queue information not available (JVM may not support Compiler.queue)");
        }

        return AnalyzerResult.ok(output);
    }

    private int getIntOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        if (value instanceof Integer i) {
            return i;
        } else if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    private long getLongOption(Map<String, Object> options, String key, long defaultValue) {
        Object value = options.get(key);
        if (value instanceof Long l) {
            return l;
        } else if (value instanceof Number n) {
            return n.longValue();
        }
        return defaultValue;
    }

    private String formatCompilerQueueTrend(List<CollectedData> samples) {
        List<TimestampedSnapshot> parsed = new ArrayList<>();
        
        for (CollectedData sample : samples) {
            CompilerQueueSnapshot snapshot = CompilerQueueParser.parse(sample.rawData());
            if (snapshot != null) {
                parsed.add(new TimestampedSnapshot(Instant.ofEpochMilli(sample.timestamp()), snapshot));
            }
        }

        if (parsed.isEmpty()) {
            return "";
        }

        StringBuilder output = new StringBuilder();
        output.append(String.format("Compiler queue trend (%d samples):\n\n", parsed.size()));

        // Summary statistics
        int maxActive = parsed.stream().mapToInt(ts -> ts.snapshot.totalActiveCount()).max().orElse(0);
        int minActive = parsed.stream().mapToInt(ts -> ts.snapshot.totalActiveCount()).min().orElse(0);
        int maxQueued = parsed.stream().mapToInt(ts -> ts.snapshot.totalQueuedCount()).max().orElse(0);
        int minQueued = parsed.stream().mapToInt(ts -> ts.snapshot.totalQueuedCount()).min().orElse(0);
        
        TimestampedSnapshot latest = parsed.get(parsed.size() - 1);
        output.append("Summary:\n");
        output.append(String.format("  Active compilations: %d (range: %d-%d)\n", 
            latest.snapshot.totalActiveCount(), minActive, maxActive));
        output.append(String.format("  Queued tasks: %d (range: %d-%d)\n\n", 
            latest.snapshot.totalQueuedCount(), minQueued, maxQueued));

        // Per-sample trend table
        output.append("Per-sample breakdown:\n");
        TablePrinter table = new TablePrinter()
                .addColumn("Time", TablePrinter.Alignment.LEFT)
                .addColumn("Active", TablePrinter.Alignment.RIGHT)
                .addColumn("Queued", TablePrinter.Alignment.RIGHT)
                .addColumn("Queues Detail", TablePrinter.Alignment.LEFT);

        for (TimestampedSnapshot ts : parsed) {
            String time = formatTimestamp(ts.timestamp);
            String active = String.valueOf(ts.snapshot.totalActiveCount());
            String queued = String.valueOf(ts.snapshot.totalQueuedCount());
            String detail = formatQueueDetail(ts.snapshot);
            table.addRow(time, active, queued, detail);
        }

        output.append(table.render());

        // Latest snapshot details
        if (latest.snapshot.totalActiveCount() > 0 || latest.snapshot.totalQueuedCount() > 0) {
            output.append("\n\nLatest snapshot details:\n");
            output.append(formatSnapshotDetails(latest.snapshot));
        }

        return output.toString();
    }

    private String formatTimestamp(Instant timestamp) {
        if (timestamp.getEpochSecond() == 0) {
            return "T+0";
        }
        return timestamp.toString().substring(11, 19); // HH:MM:SS
    }

    private String formatQueueDetail(CompilerQueueSnapshot snapshot) {
        if (snapshot.queuesByName().isEmpty()) {
            return "-";
        }
        
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<CompileTask>> entry : snapshot.queuesByName().entrySet()) {
            String queueName = entry.getKey();
            int count = entry.getValue().size();
            if (count > 0) {
                parts.add(String.format("%s:%d", queueName, count));
            }
        }
        
        return parts.isEmpty() ? "all empty" : String.join(", ", parts);
    }

    private String formatSnapshotDetails(CompilerQueueSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();

        // Active compilations
        if (!snapshot.activeCompiles().isEmpty()) {
            sb.append("Active compilations:\n");
            for (CompileTask task : snapshot.activeCompiles()) {
                sb.append(String.format("  [%d] %s %s\n",
                        task.compileId(),
                        formatTaskFlags(task),
                        formatMethodDesc(task)));
            }
        }

        // Queued tasks by queue
        for (Map.Entry<String, List<CompileTask>> entry : snapshot.queuesByName().entrySet()) {
            String queueName = entry.getKey();
            List<CompileTask> tasks = entry.getValue();
            
            if (tasks.isEmpty()) {
                sb.append(String.format("\n%s compile queue: Empty\n", queueName));
            } else {
                sb.append(String.format("\n%s compile queue: %d task(s)\n", queueName, tasks.size()));
                int shown = Math.min(5, tasks.size());
                for (int i = 0; i < shown; i++) {
                    CompileTask task = tasks.get(i);
                    sb.append(String.format("  [%d] %s %s\n",
                            task.compileId(),
                            formatTaskFlags(task),
                            formatMethodDesc(task)));
                }
                if (tasks.size() > shown) {
                    sb.append(String.format("  ... and %d more\n", tasks.size() - shown));
                }
            }
        }

        return sb.toString();
    }

    private String formatTaskFlags(CompileTask task) {
        StringBuilder sb = new StringBuilder();
        if (task.tier() != null) {
            sb.append("T").append(task.tier()).append(" ");
        }
        if (task.isOsr()) sb.append("OSR ");
        if (task.isBlocking()) sb.append("BLOCK ");
        if (task.isSynchronized()) sb.append("SYNC ");
        return sb.toString().trim();
    }

    private String formatMethodDesc(CompileTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append(task.methodName());
        
        if (task.osrBci() != null) {
            sb.append(" @ ").append(task.osrBci());
        }
        
        if (task.isNativeMethod()) {
            sb.append(" (native)");
        } else if (task.bytes() != null) {
            sb.append(" (").append(task.bytes()).append(" bytes)");
        }
        
        return sb.toString();
    }

    private record TimestampedSnapshot(Instant timestamp, CompilerQueueSnapshot snapshot) {
    }
}