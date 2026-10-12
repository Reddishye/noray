package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.command.CommandRegistry;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements {@code help} — displays available commands and usage information.
 * Supports categorized listing and detailed help for specific commands.
 */
public final class HelpCommand implements Command {

    private final CommandRegistry registry;

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Display available commands and usage information";
    }

    @Override
    public List<String> aliases() {
        return List.of("?");
    }

    @Override
    public String synopsis() {
        return "help [COMMAND]";
    }

    @Override
    public String longHelp() {
        return """
            Usage: help [COMMAND]

            Display help about available commands. Without arguments, lists all commands
            grouped by category. With a command name, shows detailed usage for that command.

            Options:
                  --help       display this help and exit

            Examples:
              help                  # List all commands
              help service          # Detailed help for 'service' command
              help journalctl       # Detailed help for 'journalctl' command
              ? version             # Short alias for 'help version'
            """;
    }

    @Override
    public CommandCategory category() {
        return CommandCategory.SYSTEM;
    }

    @Override
    public int execute(CommandInvocation invocation) {
        var out = invocation.out();
        var args = invocation.args();

        if (args.isEmpty()) {
            // List all commands by category
            printCategorizedHelp(out);
            return 0;
        }

        // Show detailed help for a specific command
        String cmdName = args.get(0).toLowerCase();
        return registry.find(cmdName)
                .map(cmd -> printCommandHelp(out, cmd))
                .orElseGet(() -> {
                    out.printf("noray: help: %s: command not found%n", cmdName);
                    return 1;
                });
    }

    private void printCategorizedHelp(java.io.PrintStream out) {
        // Group commands by category
        Map<CommandCategory, List<Command>> byCategory = registry.getAllCommands().stream()
                .collect(Collectors.groupingBy(
                        Command::category,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> list.stream().sorted(Comparator.comparing(Command::name)).toList()
                        )
                ));

        // Sort categories by section number
        List<CommandCategory> sortedCategories = byCategory.keySet().stream()
                .sorted(Comparator.comparingInt(CommandCategory::getSection))
                .toList();

        out.println("Available Noray System Commands:");
        out.println("==================================================");

        for (CommandCategory category : sortedCategories) {
            List<Command> cmds = byCategory.get(category);
            if (cmds.isEmpty()) continue;

            out.println();
            out.printf("%s %s:%n", category.getDisplayName(), " ".repeat(Math.max(0, 16 - category.getDisplayName().length())));
            out.println("--------------------------------------------------");

            for (Command cmd : cmds) {
                String aliases = cmd.aliases().isEmpty() ? "" : " [" + String.join(", ", cmd.aliases()) + "]";
                out.printf("  %-16s %s%n", cmd.name() + aliases, cmd.description());
                if (!cmd.synopsis().equals(cmd.name())) {
                    out.printf("  %-16s %s%n", "", cmd.synopsis());
                }
            }
        }

        out.println();
        out.println("Use 'help <command>' for detailed usage information.");
        out.println("Use '?' as an alias for 'help'.");
    }

    private int printCommandHelp(java.io.PrintStream out, Command cmd) {
        out.println(cmd.longHelp());
        return 0;
    }
}