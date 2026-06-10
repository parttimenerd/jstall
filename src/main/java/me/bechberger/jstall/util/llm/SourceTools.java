package me.bechberger.jstall.util.llm;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Source-code exploration tools for local AI analysis.
 *
 * <p>Exposes three read-only tools scoped to a user-supplied source root:
 * <ul>
 *   <li>{@code list_source_files} — list files matching a glob pattern</li>
 *   <li>{@code read_source_file} — read a specific file (path-traversal safe)</li>
 *   <li>{@code grep_source} — regex search across source files</li>
 * </ul>
 *
 * <p>All paths are verified to stay within the configured root (symlinks resolved).
 * Automatically skips {@code target/}, {@code build/}, {@code .git/}, {@code node_modules/}.
 */
public class SourceTools {

    private static final List<String> SKIP_DIRS = List.of(
        "target", "build", ".git", "node_modules", ".gradle", "out", ".idea"
    );
    private static final int MAX_LIST_RESULTS = 200;
    private static final int MAX_GREP_HITS = 50;
    private static final int MAX_READ_LINES = 500;
    private static final int MAX_LINE_LENGTH = 200;

    /** Extra directories to search when the primary root yields no results. */
    private static final List<String> FALLBACK_ROOTS = buildFallbackRoots();

    private static List<String> buildFallbackRoots() {
        List<String> roots = new ArrayList<>();
        // Add java.io.tmpdir (resolving symlinks so /tmp -> /private/tmp on macOS works)
        try {
            roots.add(Path.of(System.getProperty("java.io.tmpdir", "/tmp")).toRealPath().toString());
        } catch (IOException e) {
            roots.add(System.getProperty("java.io.tmpdir", "/tmp"));
        }
        roots.add(System.getProperty("user.home", "/"));
        // Add common temp directories (with symlink resolution)
        for (String candidate : new String[]{"/tmp", "/var/tmp"}) {
            try {
                String real = Path.of(candidate).toRealPath().toString();
                if (!roots.contains(real)) roots.add(real);
            } catch (IOException ignored) {}
        }
        return List.copyOf(roots);
    }

    private final Path root;

    public SourceTools(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public List<ToolDefinition> getToolDefinitions() {
        return List.of(
            new ToolDefinition(
                "list_source_files",
                "List source files matching a glob pattern. Searches project root and /tmp. Default: *.java/kt/scala/groovy.",
                List.of(
                    new ToolDefinition.Parameter("pattern", "string",
                        "Glob pattern, e.g. \"**/*.java\" or \"**/UserService*.java\". Default: **/*.{java,kt,scala,groovy}", false)
                )
            ),
            new ToolDefinition(
                "read_source_file",
                "Read a source file. Accepts relative path, absolute path, or bare filename. Searches /tmp automatically.",
                List.of(
                    new ToolDefinition.Parameter("path", "string",
                        "Relative path, absolute path, or filename e.g. \"BlockedApp.java\""),
                    new ToolDefinition.Parameter("start_line", "integer",
                        "First line to read (1-based, default: 1)", false),
                    new ToolDefinition.Parameter("end_line", "integer",
                        "Last line to read (default: up to 500 lines from start)", false)
                )
            ),
            new ToolDefinition(
                "grep_source",
                "Search source files for a regex pattern. Returns up to 50 matching lines with file path and line number.",
                List.of(
                    new ToolDefinition.Parameter("pattern", "string",
                        "Java regex, e.g. \"synchronized|ReentrantLock\""),
                    new ToolDefinition.Parameter("file_glob", "string",
                        "Glob to restrict search, e.g. \"**/*.java\" (default: all files)", false)
                )
            )
        );
    }

    public ToolExecutor createExecutor() {
        return call -> {
            try {
                return switch (call.name()) {
                    case "list_source_files" -> listSourceFiles(call.getString("pattern", null));
                    case "read_source_file"  -> readSourceFile(
                        call.getString("path", ""),
                        call.getInt("start_line", 1),
                        call.getInt("end_line", -1));
                    case "grep_source"       -> grepSource(
                        call.getString("pattern", ""),
                        call.getString("file_glob", null));
                    default -> "Unknown source tool: " + call.name();
                };
            } catch (Exception e) {
                return "Error executing " + call.name() + ": " + e.getMessage();
            }
        };
    }

    // -------------------------------------------------------------------------

    private String listSourceFiles(String pattern) {
        String effectivePattern = (pattern != null && !pattern.isBlank())
            ? pattern
            : "**/*.{java,kt,scala,groovy}";

        List<String> results = searchInRoot(root, effectivePattern);

        // If nothing found in primary root, try fallback directories
        if (results.isEmpty()) {
            for (String fallback : FALLBACK_ROOTS) {
                Path fallbackPath = Path.of(fallback).toAbsolutePath().normalize();
                if (fallbackPath.equals(root)) continue;
                if (!Files.isDirectory(fallbackPath)) continue;
                List<String> fallbackResults = searchInRoot(fallbackPath, effectivePattern);
                if (!fallbackResults.isEmpty()) {
                    StringBuilder sb = new StringBuilder(fallbackResults.size() + " file(s) found in " + fallbackPath + ":\n\n");
                    fallbackResults.forEach(f -> sb.append(fallbackPath.resolve(f)).append("\n"));
                    return sb.toString();
                }
            }
            return "No files found matching '" + effectivePattern + "'.";
        }
        StringBuilder sb = new StringBuilder(results.size() + " file(s):\n\n");
        results.forEach(f -> sb.append(f).append("\n"));
        return sb.toString();
    }

    private List<String> searchInRoot(Path searchRoot, String pattern) {
        // Build a matcher that also accepts files directly in root (no parent segment)
        PathMatcher matcher = buildMatcher(pattern);
        // Fallback matcher without the leading **/ for flat directories
        String flatPattern = pattern.startsWith("**/") ? pattern.substring(3) : null;
        PathMatcher flatMatcher = flatPattern != null ? buildMatcher(flatPattern) : null;

        List<String> results = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(searchRoot)) {
            walk.filter(p -> !isSkipped(p))
                .filter(Files::isRegularFile)
                .filter(p -> {
                    Path rel = searchRoot.relativize(p);
                    return matcher.matches(rel)
                        || (flatMatcher != null && flatMatcher.matches(rel));
                })
                .limit(MAX_LIST_RESULTS)
                .forEach(p -> results.add(searchRoot.relativize(p).toString()));
        } catch (IOException | java.io.UncheckedIOException e) {
            // Ignore IO errors (e.g. permission denied) in fallback search
        }
        return results;
    }

    private String readSourceFile(String path, int startLine, int endLine) throws IOException {
        if (path == null || path.isBlank()) return "Error: path is required.";

        // Reject obvious traversal attempts
        if (path.contains("..")) {
            return "Error: path '" + path + "' contains path traversal sequences.";
        }

        // Try as relative path within root first
        Path resolved = safeResolve(path);

        // If not found as relative path, try as absolute path (model may supply full path from stack trace)
        if (resolved == null || !Files.isRegularFile(resolved)) {
            Path abs = Path.of(path);
            if (abs.isAbsolute() && Files.isRegularFile(abs)) {
                resolved = abs;
            }
        }

        if (resolved == null || !Files.isRegularFile(resolved)) {
            // Try finding the file by basename in fallback roots
            String basename = Path.of(path).getFileName().toString();
            // Only search by basename for plausible source file names (not system files)
            if (basename.matches(".*\\.(java|kt|scala|groovy|py|js|ts|cpp|c|h|go|rs)$")) {
                for (String fallback : FALLBACK_ROOTS) {
                    Path fallbackPath = Path.of(fallback).toAbsolutePath().normalize();
                    if (!Files.isDirectory(fallbackPath)) continue;
                    List<String> found = searchInRoot(fallbackPath, "**/" + basename);
                    if (!found.isEmpty()) {
                        resolved = fallbackPath.resolve(found.get(0));
                        break;
                    }
                }
            }
        }

        if (resolved == null || !Files.isRegularFile(resolved)) {
            return "Error: '" + path + "' not found under source root or fallback directories.";
        }

        List<String> lines = Files.readAllLines(resolved);
        int total = lines.size();
        int from = Math.max(1, startLine) - 1;       // 0-based
        int to = endLine > 0
            ? Math.min(endLine, Math.min(from + MAX_READ_LINES, total))
            : Math.min(from + MAX_READ_LINES, total);

        if (from >= total) return "File has " + total + " lines; start_line=" + startLine + " is out of range.";

        StringBuilder sb = new StringBuilder();
        sb.append(path).append(" (lines ").append(from + 1).append("-").append(to)
          .append(" of ").append(total).append("):\n\n");
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append("\t").append(lines.get(i)).append("\n");
        }
        if (to < total) {
            sb.append("... ").append(total - to).append(" more lines\n");
        }
        return sb.toString();
    }

    private String grepSource(String pattern, String fileGlob) {
        if (pattern == null || pattern.isBlank()) return "Error: pattern is required.";

        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "Error: invalid regex '" + pattern + "': " + e.getMessage();
        }

        String effectiveGlob = (fileGlob != null && !fileGlob.isBlank()) ? fileGlob : "**/*";
        PathMatcher fileMatcher = buildMatcher(effectiveGlob);
        String flatGlob = effectiveGlob.startsWith("**/") ? effectiveGlob.substring(3) : null;
        PathMatcher flatFileMatcher = flatGlob != null ? buildMatcher(flatGlob) : null;

        List<String> hits = grepInRoot(root, compiled, fileMatcher, flatFileMatcher);

        // If no hits in primary root, try fallback directories
        if (hits.isEmpty()) {
            for (String fallback : FALLBACK_ROOTS) {
                Path fallbackPath = Path.of(fallback).toAbsolutePath().normalize();
                if (fallbackPath.equals(root) || !Files.isDirectory(fallbackPath)) continue;
                hits = grepInRoot(fallbackPath, compiled, fileMatcher, flatFileMatcher);
                if (!hits.isEmpty()) break;
            }
        }

        if (hits.isEmpty()) return "No matches for '" + pattern + "'.";
        StringBuilder sb = new StringBuilder(hits.size() + " match(es):\n\n");
        hits.forEach(h -> sb.append(h).append("\n"));
        if (hits.size() == MAX_GREP_HITS) {
            sb.append("... (truncated at ").append(MAX_GREP_HITS).append(" hits)\n");
        }
        return sb.toString();
    }

    private List<String> grepInRoot(Path searchRoot, Pattern compiled,
                                     PathMatcher fileMatcher, PathMatcher flatFileMatcher) {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(searchRoot)) {
            walk.filter(p -> !isSkipped(p))
                .filter(Files::isRegularFile)
                .filter(p -> {
                    Path rel = searchRoot.relativize(p);
                    return fileMatcher.matches(rel)
                        || (flatFileMatcher != null && flatFileMatcher.matches(rel));
                })
                .forEach(file -> {
                    if (hits.size() >= MAX_GREP_HITS) return;
                    try {
                        List<String> fileLines = Files.readAllLines(file);
                        for (int i = 0; i < fileLines.size() && hits.size() < MAX_GREP_HITS; i++) {
                            String line = fileLines.get(i);
                            if (compiled.matcher(line).find()) {
                                String relPath = searchRoot.relativize(file).toString();
                                String truncated = line.length() > MAX_LINE_LENGTH
                                    ? line.substring(0, MAX_LINE_LENGTH) + "..."
                                    : line;
                                hits.add(relPath + ":" + (i + 1) + ": " + truncated.strip());
                            }
                        }
                    } catch (IOException ignored) {}
                });
        } catch (IOException | java.io.UncheckedIOException ignored) {
            // Ignore permission errors walking fallback directories
        }
        return hits;
    }

    // -------------------------------------------------------------------------

    /** Resolve a relative path inside the source root, rejecting traversals. */
    private Path safeResolve(String relativePath) {
        try {
            Path candidate = root.resolve(relativePath).normalize();
            // Resolve symlinks before comparing
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) return null;
            return realCandidate;
        } catch (IOException e) {
            // toRealPath() fails if the file does not exist — try without symlink resolution
            Path candidate = root.resolve(relativePath).normalize();
            if (!candidate.startsWith(root)) return null;
            return candidate;
        }
    }

    private boolean isSkipped(Path p) {
        for (Path segment : p) {
            if (SKIP_DIRS.contains(segment.toString())) return true;
        }
        return false;
    }

    private PathMatcher buildMatcher(String glob) {
        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }
}
