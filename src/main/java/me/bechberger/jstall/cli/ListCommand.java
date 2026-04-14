package me.bechberger.jstall.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.Main;
import me.bechberger.jstall.provider.ReplayProvider;
import me.bechberger.jstall.util.CommandExecutor.SSHCommandException;
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
            arity = "0..*",
        description = "Optional filter(s) - only show JVMs whose main class contains any of these texts"
    )
    private List<String> filters;

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
            boolean hasFilters = filters != null && !filters.isEmpty();
            
            if (hasFilters && filters.size() == 1) {
                // Single filter — pass through for efficient filtering
                String filter = filters.get(0);
                if (replayFile != null) {
                    jvms = new ReplayProvider(replayFile).listRecordedJvms(filter);
                } else {
                    jvms = new JVMDiscovery(main.executor()).listJVMs(filter);
                }
            } else {
                // No filter or multiple filters — get all, then filter
                if (replayFile != null) {
                    jvms = new ReplayProvider(replayFile).listRecordedJvms(null);
                } else {
                    jvms = new JVMDiscovery(main.executor()).listJVMs(null);
                }
                if (hasFilters) {
                    jvms = jvms.stream()
                        .filter(jvm -> filters.stream().anyMatch(f -> 
                            jvm.mainClass().toLowerCase().contains(f.toLowerCase())))
                        .toList();
                }
            }

            if (jvms.isEmpty()) {
                if (hasFilters) {
                    System.err.println("No JVMs found matching filter(s): " + String.join(", ", filters));
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

        } catch (SSHCommandException e) {
            System.err.println("ERROR: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("Error listing JVMs: " + e.getMessage());
            return 1;
        }
    }
}