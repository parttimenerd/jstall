package me.bechberger.jstall.model;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the system environment, including running processes.
 */
public record SystemEnvironment(List<Process> processes) {

    public record Process(long pid, ProcessHandle.Info info, @Nullable Duration cpuTime, String command) {}

    public static SystemEnvironment createCurrent() {
        List<Process> processes = ProcessHandle.allProcesses()
            .map(ph -> new Process(ph.pid(), ph.info(), ph.info().totalCpuDuration().orElse(null), ph.info().command().orElse(null)))
            .toList();
        try {
            Map<Long, PsProcessInfo> psResult = tryCallPs();
            processes = processes.stream()
                .map(p -> {
                    var psInfo = psResult.get(p.pid());
                    if (psInfo != null) {
                        return new Process(p.pid(), p.info(), psInfo.cpuTime, p.command == null ? psInfo.command : p.command);
                    }
                    return p;
                })
                .toList();
        } catch (IOException | InterruptedException e) {
            // ignore
        }
        return new SystemEnvironment(processes);
    }

    private record PsProcessInfo(long pid, Duration cpuTime, String command) {}

    private static Map<Long, PsProcessInfo> tryCallPs() throws IOException, InterruptedException {
        java.lang.Process process = new ProcessBuilder("ps", "-aeo", "pid,time,command").start();
        InputStream stream = process.getInputStream();
        StringBuilder output = new StringBuilder();
        while (process.isAlive()) {
            output.append(new String(stream.readAllBytes()));
        }
        if (process.waitFor() == 0) {
            output.append(new String(process.getInputStream().readAllBytes()));
            return parsePsOutput(output.toString());
        }
        return new HashMap<>();
    }

    private static Map<Long, PsProcessInfo> parsePsOutput(String psOutput) {
        Map<Long, PsProcessInfo> infos = new HashMap<>();
        String[] lines = psOutput.split("\n");
        for (int i = 1; i < lines.length; i++) { // skip header
            String line = lines[i].trim();
            String[] parts = line.split("\\s+");
            if (parts.length >= 3) {
                try {
                    long pid = Long.parseLong(parts[0]);
                    Duration cpuTime = parsePsTime(parts[1]);
                    infos.put(pid, new PsProcessInfo(pid, cpuTime, String.join(" ", List.of(parts).subList(2, parts.length))));
                } catch (NumberFormatException e) {
                    // ignore invalid lines
                }
            }
        }
        return infos;
    }

    private static Duration parsePsTime(String timeStr) {
        String[] hms = timeStr.split(":");
        String hourPart = "0";
        String minutePart = "0";
        String secondPart = "0";
        if (hms.length == 3) {
            hourPart = hms[0];
            minutePart = hms[1];
            secondPart = hms[2];
        } else if (hms.length == 2) {
            minutePart = hms[0];
            secondPart = hms[1];
        } else if (hms.length == 1) {
            secondPart = hms[0];
        } else {
            return Duration.ZERO;
        }
        long hours = Long.parseLong(hourPart);
        long minutes = Long.parseLong(minutePart);
        double seconds = Double.parseDouble(secondPart);
        return Duration.ofMinutes(minutes).plusSeconds((long)seconds).plusMillis((long)((seconds - (long)seconds) * 1000));
    }
}