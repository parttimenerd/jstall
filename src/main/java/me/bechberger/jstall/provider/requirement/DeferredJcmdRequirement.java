package me.bechberger.jstall.provider.requirement;

import me.bechberger.jstall.util.JMXDiagnosticHelper;

import java.io.IOException;
import java.util.Map;

/**
 * A jcmd requirement that collects a single sample at the end of a multi-sample interval window.
 *
 * <p>This is primarily useful for historical-buffer style commands such as {@code VM.vitals}:
 * the surrounding analyzers may need multiple thread-dump samples over an interval, while the
 * jcmd output should be taken only once after the interval has elapsed so the window is covered.</p>
 */
public class DeferredJcmdRequirement extends JcmdRequirement implements IntervalWindowRequirement {

    public DeferredJcmdRequirement(String command, String[] args, CollectionSchedule schedule) {
        super(command, args, schedule);
    }

    @Override
    public CollectedData collectWindow(JMXDiagnosticHelper helper, int sampleIndex, long windowMs) throws IOException {
        if (sampleIndex < getSchedule().count() - 2) {
            return new CollectedData(System.currentTimeMillis(), "", Map.of("skip", "true"));
        }
        if (windowMs > 0) {
            try {
                Thread.sleep(windowMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to collect deferred jcmd command", e);
            }
        }
        return super.collect(helper, sampleIndex);
    }

    @Override
    public String getDescription() {
        return getType() + " (once at end of interval)";
    }
}

