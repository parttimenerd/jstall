package me.bechberger.jstall.analyzer;

/**
 * Specifies how many thread dumps an analyzer requires.
 */
public enum DumpRequirement {
    /**
     * Analyzer requires exactly one dump (uses only the first if more are provided).
     */
    ONE,

    /**
     * Analyzer requires multiple dumps (at least 2).
     */
    MANY,

    /**
     * Analyzer can work with any number of dumps.
     */
    ANY
}