package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.model.SystemEnvironment;
import me.bechberger.jstall.model.ThreadDumpSnapshot;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static me.bechberger.jstall.analyzer.BaseAnalyzer.assertSortedByDate;

/**
 * Checks whether there are any processes running on the system that take a high amount of CPU.
 * <p>
 * Helpful to identify e.g. a virus scanner or other interfering processes that use more than 20% of the available CPU-time.
 * Also report if non-own processes are consuming more than 40% of CPU time.
 * In either of these cases, list all processes with a CPU usage above 1% of CPU time.
 */
public class SystemProcessAnalyzer implements Analyzer {

    private static final double PROCESS_CPU_USAGE_THRESHOLD = 0.2;
    private static final double ALL_PROCESSES_CPU_USAGE_THRESHOLD = 0.4;
    private static final double LIST_PROCESS_CPU_USAGE_THRESHOLD = 0.01;

    @Override
    public String name() {
        return "";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of();
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.MANY;
    }

    private record ProcessCpuUsage(long pid, String command, double cpuUsageSeconds) {}

    /** ... sorts them by CPU usage descendingly */
    private List<ProcessCpuUsage> processSystemProcessesIgnoreOwn(List<ThreadDumpSnapshot> dumps) {
        SystemEnvironment firstEnv = dumps.get(0).environment();
        SystemEnvironment lastEnv = dumps.get(dumps.size() - 1).environment();
        if (firstEnv == null || lastEnv == null) {
            return List.of();
        }
        long ownPid = ProcessHandle.current().pid();
        Map<Long, Double> cpuUsagesInFirstEnv = firstEnv.processes().stream()
                .filter(p -> p.cpuTime() != null && p.pid() != ownPid)
                .collect(Collectors.toMap(SystemEnvironment.Process::pid,
                        p -> p.cpuTime().toNanos() / 1_000_000_000.0));
        return lastEnv.processes().stream()
                .filter(p -> p.cpuTime() != null && p.pid() != ownPid && cpuUsagesInFirstEnv.containsKey(p.pid()))
                .map(p -> {
                    double cpuUsageInFirst = cpuUsagesInFirstEnv.get(p.pid());
                    double cpuUsageInLast = p.cpuTime().toNanos() / 1_000_000_000.0;
                    double cpuUsageDiff = cpuUsageInLast - cpuUsageInFirst;
                    return new ProcessCpuUsage(p.pid(), p.command(), cpuUsageDiff);
                })
                .filter(usage -> usage.cpuUsageSeconds() > 0)
                .sorted((u1, u2) -> Double.compare(u2.cpuUsageSeconds(), u1.cpuUsageSeconds()))
                .collect(Collectors.toList());
    }

    @Override
    public AnalyzerResult analyze(List<ThreadDumpSnapshot> dumpsWithRaw, Map<String, Object> options) {
        assertSortedByDate(dumpsWithRaw);
        // idea: take first and last dump, calculate CPU usage difference for processes
        List<ProcessCpuUsage> processUsages = processSystemProcessesIgnoreOwn(dumpsWithRaw);
        double totalCpuUsage = processUsages.stream().mapToDouble(ProcessCpuUsage::cpuUsageSeconds).sum();
        double availableCpuCores = Runtime.getRuntime().availableProcessors();
        double availableCpuTime = availableCpuCores *
                                  Duration.between(dumpsWithRaw.get(0).parsed().timestamp(), dumpsWithRaw.get(dumpsWithRaw.size() - 1).parsed().timestamp()).toNanos() / 1_000_000_000.0;
        boolean totalCPUUsageOk = totalCpuUsage / availableCpuTime < ALL_PROCESSES_CPU_USAGE_THRESHOLD;
        boolean singleProcessUsageOk = processUsages.stream()
                .allMatch(usage -> (usage.cpuUsageSeconds() / availableCpuTime) < PROCESS_CPU_USAGE_THRESHOLD);
        if (totalCPUUsageOk && singleProcessUsageOk) {
            return AnalyzerResult.nothing(); // No significant CPU usage detected
        }
        StringBuilder output = new StringBuilder();
        output.append("High CPU usage detected from other processes on the system (total CPU usage: ")
              .append(String.format("%.2f", (totalCpuUsage / availableCpuTime) * 100))
              .append("% of available CPU time).\n");
        output.append("Processes with more than ")
              .append(String.format("%.2f", LIST_PROCESS_CPU_USAGE_THRESHOLD * 100))
              .append("% CPU usage:\n");
        for (ProcessCpuUsage usage : processUsages) {
            double cpuUsagePercent = (usage.cpuUsageSeconds() / availableCpuTime) * 100;
            if (cpuUsagePercent >= LIST_PROCESS_CPU_USAGE_THRESHOLD * 100) {
                output.append(String.format(" - PID %d (%s): %.2f%% CPU usage\n",
                        usage.pid(), usage.command(), cpuUsagePercent));
            }
        }
        return AnalyzerResult.ok(output.toString());
    }
}