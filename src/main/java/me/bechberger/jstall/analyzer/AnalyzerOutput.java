package me.bechberger.jstall.analyzer;

import java.util.List;

/**
 * Structured output from an analyzer. Supports plain text, tables, and multi-section composites.
 */
public sealed interface AnalyzerOutput permits AnalyzerOutput.TextOutput, AnalyzerOutput.TableOutput, AnalyzerOutput.CompositeOutput {

    /** Renders this output to a CLI-friendly string. */
    String render();

    /** Plain text output (e.g. deadlock info, dependency trees). */
    record TextOutput(String text) implements AnalyzerOutput {
        @Override
        public String render() {
            return text;
        }
    }

    /** A single table with optional preamble lines above it. */
    record TableOutput(List<String> preambleLines, TableModel table) implements AnalyzerOutput {
        public TableOutput(TableModel table) {
            this(List.of(), table);
        }

        @Override
        public String render() {
            StringBuilder sb = new StringBuilder();
            for (String line : preambleLines) {
                sb.append(line).append('\n');
            }
            sb.append(table.render());
            return sb.toString().trim();
        }
    }

    /** Multiple named sections (rendered as === name === delimited blocks). */
    record CompositeOutput(List<Section> sections) implements AnalyzerOutput {
        public record Section(String name, AnalyzerOutput content) {}

        @Override
        public String render() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sections.size(); i++) {
                Section section = sections.get(i);
                if (i > 0) sb.append("\n\n");
                sb.append("=== ").append(section.name()).append(" ===\n");
                sb.append(section.content().render());
            }
            return sb.toString();
        }
    }
}
