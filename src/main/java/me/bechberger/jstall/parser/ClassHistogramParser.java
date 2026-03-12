package me.bechberger.jstall.parser;

import me.bechberger.jstall.model.ClassHistogram;
import me.bechberger.jstall.model.ClassHistogramEntry;
import me.bechberger.jstall.util.JcmdOutputParsers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for class histograms in the format produced by {@code jmap -histo} or
 * {@code jcmd <pid> GC.class_histogram}.
 * <p>
 * Uses the generic jcmd table parser for robust parsing.
 */
public final class ClassHistogramParser {

    private ClassHistogramParser() {
    }

    public static ClassHistogram parse(String text) {
        JcmdOutputParsers.Table table = JcmdOutputParsers.parseTable(text);
        if (table.isEmpty()) {
            return new ClassHistogram(List.of());
        }

        List<ClassHistogramEntry> entries = new ArrayList<>();
        for (JcmdOutputParsers.TableRow row : table.getRows()) {
            // Expected columns: num, #instances, #bytes, class name (module)
            // Row numbers are extracted and appear as first column
            Integer num = row.getInt(0);  // row number: "1", "2", etc.
            Long instances = row.getLong(1);  // #instances
            Long bytes = row.getLong(2);  // #bytes
            String classNameWithModule = row.get(3);  // class name (module)

            if (num == null || instances == null || bytes == null || classNameWithModule == null) {
                continue;  // Skip malformed rows
            }

            // Parse class name and module
            // Example: "[B (java.base@21.0.9)" or "java.lang.String"
            String className;
            String module = null;

            int openParen = classNameWithModule.lastIndexOf('(');
            if (openParen >= 0 && classNameWithModule.endsWith(")")) {
                className = classNameWithModule.substring(0, openParen).trim();
                module = classNameWithModule.substring(openParen + 1, classNameWithModule.length() - 1).trim();
                if (module.isEmpty()) {
                    module = null;
                }
            } else {
                className = classNameWithModule.trim();
            }

            if (!className.isEmpty()) {
                entries.add(new ClassHistogramEntry(num, instances, bytes, className, module));
            }
        }

        return new ClassHistogram(List.copyOf(entries));
    }

    public static ClassHistogram parse(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return parse(sb.toString());
    }
}