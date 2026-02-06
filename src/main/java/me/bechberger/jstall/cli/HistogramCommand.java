package me.bechberger.jstall.cli;

import me.bechberger.jstall.model.ClassHistogram;
import me.bechberger.jstall.parser.ClassHistogramParser;
import me.bechberger.jstall.util.TablePrinter;
import me.bechberger.jstall.util.JMXDiagnosticHelper;
import me.bechberger.minicli.Spec;
import me.bechberger.minicli.annotations.Command;
import me.bechberger.minicli.annotations.Option;
import me.bechberger.minicli.annotations.Parameters;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "histogram",
    description = "Parse and analyze a jmap/jcmd class histogram"
)
public class HistogramCommand implements Callable<Integer> {

    @Parameters(index = "0..1", description = "Histogram file path(s) or '-' for stdin")
    private List<String> inputs;

    @Option(names = "--top", description = "Number of top rows (default: 10)")
    private int top = 10;

    @Option(names = "--sort", description = "Sort by 'bytes' or 'instances' (default: bytes)")
    private String sort = "bytes";

    @Option(names = "--pid", description = "Capture histogram(s) directly from this PID (instead of reading files)")
    private Long pid;

    @Option(names = "--delay", description = "Delay between two captures when using --pid (default: 0s)")
    private Duration delay = Duration.ZERO;

    Spec spec;

    @Override
    public Integer call() throws Exception {
        // Convenience: if user passes a single numeric argument (and no --pid), treat it as a PID.
        if (pid == null && inputs != null && inputs.size() == 1) {
            String only = inputs.get(0);
            if (only != null && only.matches("\\d+") && !Files.exists(Path.of(only))) {
                pid = Long.parseLong(only);
                inputs = List.of();
            }
        }

        if (pid != null) {
            return runPidMode();
        }

        if (inputs == null || inputs.isEmpty()) {
            spec.usage();
            return 2;
        }
        if (inputs.size() > 2) {
            System.err.println("Error: Expected at most 2 inputs (before/after), got " + inputs.size());
            return 2;
        }

        String resolved0 = resolveInput(inputs.get(0));
        if (resolved0 == null) {
            return 1;
        }

        ClassHistogram before;
        try {
            before = readAndParse(resolved0);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        if (before.entries().isEmpty()) {
            System.err.println("Error: No histogram entries found in first input (unrecognized format?)");
            return 1;
        }

        if (inputs.size() == 1) {
            return renderSingle(before);
        }

        String resolved1 = resolveInput(inputs.get(1));
        if (resolved1 == null) {
            return 1;
        }

        ClassHistogram after;
        try {
            after = readAndParse(resolved1);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        if (after.entries().isEmpty()) {
            System.err.println("Error: No histogram entries found in second input (unrecognized format?)");
            return 1;
        }

        return renderDiff(before, after);
    }

    private Integer runPidMode() {
        try {
            ClassHistogram before = getClassHistogram("Error: No histogram entries found in capture from PID ");
            if (before == null) return 1;

            if (delay == null) {
                delay = Duration.ZERO;
            }

            // If there's no delay, still do the typical workflow: capture twice and diff.
            // This matches the user's expectation of "compare/what allocated most between two histograms".
            if (delay.isZero()) {
                delay = Duration.ofSeconds(1);
            }

            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Error: Interrupted while waiting between captures");
                return 1;
            }

            ClassHistogram after = getClassHistogram("Error: No histogram entries found in second capture from PID ");
            if (after == null) return 1;

            return renderDiff(before, after);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private @Nullable ClassHistogram getClassHistogram(String x) throws IOException {
        String afterText = JMXDiagnosticHelper.executeCommand(pid, "gcClassHistogram", "GC.class_histogram");
        ClassHistogram after = ClassHistogramParser.parse(afterText);
        if (after.entries().isEmpty()) {
            System.err.println(x + pid);
            return null;
        }
        return after;
    }

    private Integer renderSingle(ClassHistogram histogram) {
        var rows = switch (sort) {
            case "bytes" -> histogram.topByBytes(top);
            case "instances" -> histogram.topByInstances(top);
            default -> {
                System.err.println("Error: Unknown --sort value: " + sort + " (expected 'bytes' or 'instances')");
                yield null;
            }
        };
        if (rows == null) {
            return 2;
        }

        NumberFormat nf = NumberFormat.getIntegerInstance();
        long totalBytes = histogram.totalBytes();

        TablePrinter tp = new TablePrinter()
            .addColumn("#", TablePrinter.Alignment.RIGHT)
            .addColumn("instances", TablePrinter.Alignment.RIGHT)
            .addColumn("bytes", TablePrinter.Alignment.RIGHT)
            .addColumn("%", TablePrinter.Alignment.RIGHT)
            .addColumn("class", TablePrinter.Alignment.LEFT)
            .addColumn("module", TablePrinter.Alignment.LEFT);

        for (var e : rows) {
            double pct = totalBytes == 0 ? 0.0 : (100.0 * e.bytes() / totalBytes);
            tp.addRow(
                String.valueOf(e.num()),
                nf.format(e.instances()),
                nf.format(e.bytes()),
                String.format("%.2f", pct),
                e.className(),
                e.module() == null ? "" : e.module()
            );
        }

        System.out.println(tp.render());
        System.out.println();
        System.out.println("Total instances: " + nf.format(histogram.totalInstances()));
        System.out.println("Total bytes:     " + nf.format(totalBytes));

        return 0;
    }

    private Integer renderDiff(ClassHistogram before, ClassHistogram after) {
        Map<String, Agg> b = aggregate(before);
        Map<String, Agg> a = aggregate(after);

        Map<String, Delta> deltas = new HashMap<>();
        for (var e : b.entrySet()) {
            String key = e.getKey();
            Agg bb = e.getValue();
            Agg aa = a.getOrDefault(key, Agg.ZERO);
            deltas.put(key, new Delta(key, bb.module, bb.instances, bb.bytes, aa.instances, aa.bytes));
        }
        for (var e : a.entrySet()) {
            String key = e.getKey();
            if (deltas.containsKey(key)) {
                continue;
            }
            Agg aa = e.getValue();
            deltas.put(key, new Delta(key, aa.module, 0, 0, aa.instances, aa.bytes));
        }

        var sorted = deltas.values().stream()
            .sorted(switch (sort) {
                case "instances" -> java.util.Comparator.comparingLong(Delta::deltaInstances).reversed();
                case "bytes" -> java.util.Comparator.comparingLong(Delta::deltaBytes).reversed();
                default -> {
                    System.err.println("Error: Unknown --sort value: " + sort + " (expected 'bytes' or 'instances')");
                    yield null;
                }
            });

        if (sorted == null) {
            return 2;
        }

        List<Delta> topGrowers = sorted.limit(top).toList();
        List<Delta> topShrinkers = deltas.values().stream()
            .sorted(switch (sort) {
                case "instances" -> java.util.Comparator.comparingLong(Delta::deltaInstances);
                case "bytes" -> java.util.Comparator.comparingLong(Delta::deltaBytes);
                default -> java.util.Comparator.comparingLong(Delta::deltaBytes);
            })
            .limit(top)
            .toList();

        NumberFormat nf = NumberFormat.getIntegerInstance();

        System.out.println("Top " + top + " deltas by " + sort + " (growth):");
        System.out.println(renderDeltaTable(topGrowers, nf));
        System.out.println();
        System.out.println("Top " + top + " deltas by " + sort + " (shrinkage):");
        System.out.println(renderDeltaTable(topShrinkers, nf));
        System.out.println();

        long bytesBefore = before.totalBytes();
        long bytesAfter = after.totalBytes();
        long instBefore = before.totalInstances();
        long instAfter = after.totalInstances();
        System.out.println("Total instances: " + nf.format(instBefore) + " -> " + nf.format(instAfter) + " (Δ " + nf.format(instAfter - instBefore) + ")");
        System.out.println("Total bytes:     " + nf.format(bytesBefore) + " -> " + nf.format(bytesAfter) + " (Δ " + nf.format(bytesAfter - bytesBefore) + ")");

        return 0;
    }

    private static String renderDeltaTable(List<Delta> deltas, NumberFormat nf) {
        TablePrinter tp = new TablePrinter()
            .addColumn("Δinstances", TablePrinter.Alignment.RIGHT)
            .addColumn("Δbytes", TablePrinter.Alignment.RIGHT)
            .addColumn("instances", TablePrinter.Alignment.RIGHT)
            .addColumn("bytes", TablePrinter.Alignment.RIGHT)
            .addColumn("class", TablePrinter.Alignment.LEFT)
            .addColumn("module", TablePrinter.Alignment.LEFT);

        for (var d : deltas) {
            tp.addRow(
                nf.format(d.deltaInstances()),
                nf.format(d.deltaBytes()),
                nf.format(d.afterInstances),
                nf.format(d.afterBytes),
                d.className,
                d.module == null ? "" : d.module
            );
        }
        return tp.render();
    }

    private static Map<String, Agg> aggregate(ClassHistogram h) {
        Map<String, Agg> map = new HashMap<>();
        for (var e : h.entries()) {
            // class name is the stable identifier in histogram output.
            map.merge(e.className(), new Agg(e.module(), e.instances(), e.bytes()), Agg::merge);
        }
        return map;
    }

    private String resolveInput(String input) {
        if ("-".equals(input)) {
            return input;
        }

        // If the argument is numeric and no file exists with that name, it's almost certainly a PID.
        if (input.matches("\\d+") && !Files.exists(Path.of(input))) {
            pid = Long.parseLong(input);
            return null;
        }

        return input;
    }

    private static ClassHistogram readAndParse(String input) throws IOException {
        if ("-".equals(input)) {
            try (Reader r = new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)) {
                return ClassHistogramParser.parse(r);
            }
        }

        Path p = Path.of(input);
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return ClassHistogramParser.parse(r);
        }
    }

    private record Agg(String module, long instances, long bytes) {
        static final Agg ZERO = new Agg(null, 0, 0);

        Agg merge(Agg other) {
            // if module differs, keep the non-null value (best-effort)
            String mod = module != null ? module : other.module;
            return new Agg(mod, instances + other.instances, bytes + other.bytes);
        }
    }

    private record Delta(String className, String module,
                         long beforeInstances, long beforeBytes,
                         long afterInstances, long afterBytes) {
        long deltaInstances() {
            return afterInstances - beforeInstances;
        }

        long deltaBytes() {
            return afterBytes - beforeBytes;
        }
    }
}