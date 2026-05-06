package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerOutput;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.Cell;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.TableModel;
import me.bechberger.jstall.model.ClassHistogram;
import me.bechberger.jstall.model.ClassHistogramEntry;
import me.bechberger.jstall.parser.ClassHistogramParser;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares class histograms captured alongside thread dumps and reports the biggest deltas.
 * <p>
 * Requires at least two histogram captures.
 */
public class ClassHistogramDiffAnalyzer extends BaseAnalyzer {

    @Override
    public String name() {
        return "class-histogram";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of("top", "sort", "dump-count", "interval");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.MANY;
    }
    
    @Override
    public int defaultDumpCount() {
        return 2;
    }
    
    @Override
    public long defaultIntervalMs() {
        return 5000;
    }
    
    @Override
    public DataRequirements getDataRequirements(Map<String, Object> options) {
        int count = getIntOption(options, "dump-count", defaultDumpCount());
        
        Object intervalObj = options.get("interval");
        long intervalMs = defaultIntervalMs();
        if (intervalObj instanceof Number n) {
            intervalMs = n.longValue();
        }
        
        return DataRequirements.builder()
                .addJcmd("GC.class_histogram", count, intervalMs)
                .build();
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        List<CollectedData> histogramData = data.collectedDataByType().get("gc-class-histogram");
        
        if (histogramData == null || histogramData.size() < 2) {
            return AnalyzerResult.nothing();
        }

        String beforeRaw = histogramData.get(0).rawData();
        String afterRaw = histogramData.get(histogramData.size() - 1).rawData();

        if (beforeRaw == null || beforeRaw.isBlank() || afterRaw == null || afterRaw.isBlank()) {
            return AnalyzerResult.nothing();
        }

        ClassHistogram before = ClassHistogramParser.parse(beforeRaw);
        ClassHistogram after = ClassHistogramParser.parse(afterRaw);
        if (before.entries().isEmpty() || after.entries().isEmpty()) {
            return AnalyzerResult.nothing();
        }

        int top = getIntOption(options, "top", 10);
        String sort = getStringOption(options, "sort", "bytes");

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

        Comparator<Delta> cmp = switch (sort) {
            case "instances" -> Comparator.comparingLong(Delta::deltaInstances).reversed();
            default -> Comparator.comparingLong(Delta::deltaBytes).reversed();
        };

        List<Delta> topGrowers = deltas.values().stream().sorted(cmp).limit(top).toList();

        NumberFormat nf = NumberFormat.getIntegerInstance();

        TableModel.Builder deltaTable = TableModel.builder()
            .addColumn("Δbytes", TableModel.Alignment.RIGHT)
            .addColumn("Δinst", TableModel.Alignment.RIGHT)
            .addColumn("class", TableModel.Alignment.LEFT)
            .addColumn("module", TableModel.Alignment.LEFT);

        for (var d : topGrowers) {
            deltaTable.addRow(
                Cell.number(nf.format(d.deltaBytes()), d.deltaBytes()),
                Cell.number(nf.format(d.deltaInstances()), d.deltaInstances()),
                Cell.text(d.className),
                Cell.text(d.module == null ? "" : d.module)
            );
        }

        long bytesBefore = before.totalBytes();
        long bytesAfter = after.totalBytes();
        
        // Top classes by bytes in last dump
        TableModel.Builder topClassesTable = TableModel.builder()
            .addColumn("bytes", TableModel.Alignment.RIGHT)
            .addColumn("instances", TableModel.Alignment.RIGHT)
            .addColumn("class", TableModel.Alignment.LEFT)
            .addColumn("module", TableModel.Alignment.LEFT);
        
        for (var entry : after.topByBytes(top)) {
            topClassesTable.addRow(
                Cell.number(nf.format(entry.bytes()), entry.bytes()),
                Cell.number(nf.format(entry.instances()), entry.instances()),
                Cell.text(entry.className()),
                Cell.text(entry.module() == null ? "" : entry.module())
            );
        }

        String totalLine = "Total bytes: " + nf.format(bytesBefore) + " -> " + nf.format(bytesAfter) + " (Δ " + nf.format(bytesAfter - bytesBefore) + ")";

        List<AnalyzerOutput.CompositeOutput.Section> sections = new ArrayList<>();
        sections.add(new AnalyzerOutput.CompositeOutput.Section("Delta",
            new AnalyzerOutput.TableOutput(List.of("Class histogram delta (first -> last dump)"), deltaTable.build())));
        sections.add(new AnalyzerOutput.CompositeOutput.Section("Top Classes",
            new AnalyzerOutput.TableOutput(List.of(totalLine, "", "Top " + top + " allocated classes in last dump:"), topClassesTable.build())));

        return AnalyzerResult.ok(new AnalyzerOutput.CompositeOutput(sections));
    }

    private static Map<String, Agg> aggregate(ClassHistogram h) {
        Map<String, Agg> map = new HashMap<>();
        for (ClassHistogramEntry e : h.entries()) {
            map.merge(e.className(), new Agg(e.module(), e.instances(), e.bytes()), Agg::merge);
        }
        return map;
    }

    private record Agg(String module, long instances, long bytes) {
        static final Agg ZERO = new Agg(null, 0, 0);

        Agg merge(Agg other) {
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