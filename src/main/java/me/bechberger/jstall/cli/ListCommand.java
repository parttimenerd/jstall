package me.bechberger.jstall.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.Main;
import me.bechberger.jstall.provider.ReplayProvider;
import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.femtocli.Spec;
import me.bechberger.jstall.util.StringUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command to list running JVM processes.
 */
@Command(
    name = "list",
    description = "List running JVM processes (excluding this tool)"
)
public class ListCommand implements Callable<Integer> {

    @Parameters(
            arity = "0..1",
        description = "Optional filter - only show JVMs whose main class contains this text"
    )
    private String filter;

    @Option(
            names = "--no-truncate",
            description = "Don't truncate descriptors in the output"
    )
    private boolean noTruncate;

    private Spec spec;

    @Override
    public Integer call() throws Exception {
        try {
            List<JVMDiscovery.JVMProcess> jvms;
            Main main = spec != null ? spec.getParent(Main.class) : null;
            Path replayFile = main != null ? main.getReplayFile() : null;
            if (replayFile != null) {
                ReplayProvider provider = new ReplayProvider(replayFile);
                jvms = provider.listRecordedJvms(filter);
            } else {
                jvms = new JVMDiscovery(main.executor()).listJVMs(filter);
            }

            if (jvms.isEmpty()) {
                if (filter != null && !filter.isBlank()) {
                    System.err.println("No JVMs found matching filter: " + filter);
                } else {
                    System.out.println("No running JVMs found.");
                }
                return 1;
            }

            // Print each JVM
            for (JVMDiscovery.JVMProcess jvm : jvms) {
                if (noTruncate) {
                    System.out.println(jvm);
                } else {
                    System.out.println(StringUtil.truncate(jvm.toString(), 150));
                }
            }

            return 0;

        } catch (IOException e) {
            System.err.println("Error listing JVMs: " + e.getMessage());
            return 1;
        }
    }
}