package me.bechberger.jstall.cli.record;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Command(
    name = "extract",
    description = "Extract recording folder from ZIP into a folder",
    footer = """
        This command extracts the contents of a recording ZIP file into a specified output folder.
        Example usage:

            jstall record extract my-recording.zip extracted-folder
        
        This will create 'extracted-folder' containing the README, metadata, ... from the recording.
        """
)
public class RecordExtractCommand implements Callable<Integer> {

    @Parameters(description = "Recording ZIP file")
    private Path zipFile;

    @Parameters(index = "1", description = "Destination folder of the extracted recording")
    private Path outputFolder;

    @Override
    public Integer call() {
        try {
            extractZip(zipFile, outputFolder);
            System.out.println("Extracted " + zipFile + " to " + outputFolder);
            return 0;
        } catch (IOException e) {
            System.err.println("Error extracting recording: " + e.getMessage());
            return 1;
        }
    }

    private void extractZip(Path sourceZip, Path destination) throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDestination);

        try (InputStream fileIn = Files.newInputStream(sourceZip);
             ZipInputStream zipIn = new ZipInputStream(fileIn)) {
            ZipEntry entry;
            String sourcePrefix = null; // will be detected from first ZIP entry 
            while ((entry = zipIn.getNextEntry()) != null) {
                if (sourcePrefix == null) {
                    String n = entry.getName();
                    int sl = n.indexOf('/');
                    String candidate = (sl >= 0) ? n.substring(0, sl + 1) : "";
                    // Only use prefix if it is safe (no path traversal)
                    sourcePrefix = (!candidate.contains("..") && !candidate.startsWith("/")) ? candidate : "";
                }
                if (!sourcePrefix.isEmpty() && !entry.getName().startsWith(sourcePrefix)) {
                    throw new IOException("Unexpected ZIP entry outside of expected prefix: " + entry.getName());
                }
                String entryName = sourcePrefix.isEmpty() ? entry.getName() : entry.getName().substring(sourcePrefix.length());
                Path target = normalizedDestination.resolve(entryName).normalize();
                if (!target.startsWith(normalizedDestination)) {
                    throw new IOException("ZIP entry escapes output folder: " + entry.getName());
                }

                ensureNoSymlinkInPath(normalizedDestination, target);

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                        ensureNoSymlinkInPath(normalizedDestination, parent);
                    }
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zipIn.transferTo(out);
                    }
                }
                zipIn.closeEntry();
            }
        }
    }

    private void ensureNoSymlinkInPath(Path root, Path path) throws IOException {
        Path relative = root.relativize(path);
        Path current = root;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current) && Files.isSymbolicLink(current)) {
                throw new IOException("ZIP extraction blocked due to symlink in output path: " + current);
            }
        }
    }
}