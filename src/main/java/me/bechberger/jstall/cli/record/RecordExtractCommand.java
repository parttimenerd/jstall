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
        Files.createDirectories(destination);

        try (InputStream fileIn = Files.newInputStream(sourceZip);
             ZipInputStream zipIn = new ZipInputStream(fileIn)) {
            ZipEntry entry;
            String sourcePrefix = sourceZip.getFileName().toString().replace(".zip", "") + "/"; // ZIP entries are typically prefixed with the ZIP file name 
            while ((entry = zipIn.getNextEntry()) != null) {
                if (!entry.getName().startsWith(sourcePrefix)) {
                    throw new IOException("Unexpected ZIP entry outside of expected prefix: " + entry.getName());
                }
                Path target = destination.resolve(entry.getName().substring(sourcePrefix.length())).normalize();
                if (!target.startsWith(destination.normalize())) {
                    throw new IOException("ZIP entry escapes output folder: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zipIn.transferTo(out);
                    }
                }
                zipIn.closeEntry();
            }
        }
    }
}