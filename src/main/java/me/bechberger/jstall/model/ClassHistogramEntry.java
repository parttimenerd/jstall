package me.bechberger.jstall.model;

/**
 * One row/entry of a class histogram as produced by {@code jmap -histo} or
 * {@code jcmd <pid> GC.class_histogram}.
 *
 * @param num row number (1-based) as printed in the histogram
 * @param instances number of instances
 * @param bytes shallow size in bytes
 * @param className class name / array descriptor (as printed)
 * @param module optional module information if present, otherwise {@code null}
 */
public record ClassHistogramEntry(int num, long instances, long bytes, String className, String module) {
}