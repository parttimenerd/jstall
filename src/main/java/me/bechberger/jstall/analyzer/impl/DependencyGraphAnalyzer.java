package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.ResolvedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Compatibility wrapper that reuses DependencyTreeAnalyzer implementation
 * and forces graph rendering mode.
 */
public class DependencyGraphAnalyzer extends DependencyTreeAnalyzer {

    @Override
    public String name() {
        return "dependency-graph";
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        Map<String, Object> mergedOptions = new HashMap<>(options);
        mergedOptions.put("graph-format", true);
        return super.analyze(data, mergedOptions);
    }
}
