package me.bechberger.jstall.cli.record;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jstall.provider.ReplayProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "summary",
    description = "Print the README summary from a recording ZIP"
)
public class RecordSummaryCommand implements Callable<Integer> {

    @Parameters(description = "Recording ZIP file")
    private Path zipFile;

    @Override
    public Integer call() {
        try {
            String summary = readSummary(zipFile);
            System.out.print(summary);
            if (!summary.endsWith("\n")) {
                System.out.println();
            }
            return 0;
        } catch (IOException e) {
            System.err.println("Error reading recording summary: " + e.getMessage());
            return 1;
        }
    }

    private String readSummary(Path recordingZip) throws IOException {
        return new ReplayProvider(recordingZip).readReadme();
    }
}