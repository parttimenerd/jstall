package me.bechberger.jstall.analyzer.impl.vmvitals;

/**
 * Condition note definition: the tag (without brackets) and its description.
 * Example: tag="delta", description="values refer to the previous measurement"
 */
public record LegendCondition(
    String tag,
    String description
) {}

