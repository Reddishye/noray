package com.redactado.noray.command;

import java.util.Collections;
import java.util.List;

/**
 * A shell command that can be dispatched by the {@link CommandRegistry}.
 * <p>
 * Commands follow POSIX conventions: {@code execute} returns 0 on success, non-zero on failure.
 * The exit code is tracked in {@code $?} and is available to the next command in a chain.
 */
public interface Command {

    /** Primary command name (lowercase, no whitespace). */
    String name();

    /** One-line description shown in {@code help} listings. */
    String description();

    /** Alternative names that resolve to this command. */
    default List<String> aliases() {
        return Collections.emptyList();
    }

    /**
     * Short argument synopsis shown next to the command name.
     * E.g. {@code "service [ACTION] [UNIT]"}.
     */
    default String synopsis() {
        return name();
    }

    /**
     * Detailed help text shown by {@code help <command>}.
     * Should include all options, flags, and usage examples.
     */
    default String longHelp() {
        return description();
    }

    /**
     * Flags that take a value ({@code -n 20}, {@code --lines 20}). A single-letter flag not listed here is a switch, so
     * {@code sysctl -n key} keeps {@code key} as an argument. Long flags not listed in either set keep the historical
     * rule: valued when followed by a word that is not a flag.
     */
    default java.util.Set<String> valueFlags() {
        return java.util.Set.of();
    }

    /** Long flags that never take a value, so a following word stays an argument ({@code sysctl --values key}). */
    default java.util.Set<String> switchFlags() {
        return java.util.Set.of();
    }

    /** First-level actions ({@code show}, {@code set} ...), offered by tab completion. */
    default List<String> subcommands() {
        return Collections.emptyList();
    }

    /**
     * Candidates for the last of {@code args} (the word being typed; earlier ones are complete). The default offers
     * {@link #subcommands()} for the first argument. Never throw: completion must not break the prompt.
     */
    default List<String> complete(com.redactado.noray.kernel.api.KernelContext context, List<String> args) {
        return args.size() <= 1 ? subcommands() : Collections.emptyList();
    }

    /** Organisational category for grouping commands in the help listing. */
    default CommandCategory category() {
        return CommandCategory.OTHER;
    }

    /**
     * Execute the command.
     *
     * @return exit code — 0 = success, non-zero = failure (POSIX convention)
     */
    int execute(CommandInvocation invocation) throws Exception;
}
