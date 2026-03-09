package me.bechberger.jstall.provider.requirement;

import me.bechberger.jstall.util.JMXDiagnosticHelper;

import java.io.IOException;

public interface IntervalWindowRequirement extends DataRequirement {

    CollectedData collectWindow(JMXDiagnosticHelper helper, int sampleIndex, long windowMs) throws IOException;
}
