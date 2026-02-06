package me.bechberger.jstall.parser;

import me.bechberger.jstall.model.ClassHistogram;
import me.bechberger.jstall.model.ClassHistogramEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for class histograms in the format produced by {@code jmap -histo} or
 * {@code jcmd <pid> GC.class_histogram}.
 */
public final class ClassHistogramParser {

    private ClassHistogramParser() {
    }

    // Example row:
    //    1:       2438780      332349064  [B (java.base@21.0.9)
    private static final Pattern ROW = Pattern.compile(
        "^\\s*(\\d+):\\s+(\\d+)\\s+(\\d+)\\s+(.+?)\\s*$");

    public static ClassHistogram parse(String text) {
        try {
            return parse(new java.io.StringReader(text));
        } catch (IOException e) {
            // StringReader shouldn't throw in practice
            throw new RuntimeException(e);
        }
    }

    public static ClassHistogram parse(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        List<ClassHistogramEntry> entries = new ArrayList<>();

        String line;
        while ((line = br.readLine()) != null) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // skip header-ish lines
            if (trimmed.startsWith("num") || trimmed.startsWith("-") || trimmed.contains("#instances") || trimmed.contains("class name")) {
                continue;
            }

            Matcher m = ROW.matcher(line);
            if (!m.matches()) {
                continue; // ignore unknown lines (e.g. leading timestamps)
            }

            int num = Integer.parseInt(m.group(1));
            long instances = Long.parseLong(m.group(2));
            long bytes = Long.parseLong(m.group(3));
            String rest = m.group(4);

            String className;
            String module = null;

            int openParen = rest.lastIndexOf("(");
            if (openParen >= 0 && rest.endsWith(")")) {
                className = rest.substring(0, openParen).trim();
                module = rest.substring(openParen + 1, rest.length() - 1).trim();
                if (module.isEmpty()) {
                    module = null;
                }
            } else {
                className = rest.trim();
            }

            if (!className.isEmpty()) {
                entries.add(new ClassHistogramEntry(num, instances, bytes, className, module));
            }
        }

        return new ClassHistogram(List.copyOf(entries));
    }
}