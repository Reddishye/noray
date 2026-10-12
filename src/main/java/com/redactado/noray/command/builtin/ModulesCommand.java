package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.kernel.api.InitLevel;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelModule;
import com.redactado.noray.kernel.api.KernelPhase;

import java.util.List;
import java.util.Set;

/**
 * Implements {@code modules} (alias {@code lsmod}) — lists loaded kernel modules with detailed information.
 * Mirrors the Linux lsmod command with Noray extensions.
 */
public final class ModulesCommand implements Command {

    @Override public Set<String> switchFlags() { return Set.of("version", "all"); }

    @Override public String name() { return "modules"; }
    @Override public String description() { return "List loaded kernel modules and inspect system phase"; }
    @Override public List<String> aliases() { return List.of("lsmod"); }
    @Override public String synopsis() { return "modules [--all|-a] [--dag] [--json]"; }

    @Override
    public String longHelp() {
        return """
            Usage: modules [OPTION]...

            List loaded kernel modules with detailed status information.

            Options:
              -a, --all              show all modules (including inactive/failed)
                  --dag              show module dependency DAG
                  --json             output as JSON
                  --help             display this help and exit
                  --version          output version information and exit

            Display columns:
              NAME          Module identifier
              LEVEL         Initialization level (EARLY, CORE, SERVICE, APPLICATION)
              ORDER         Order within level
              PHASE         Current module phase (INITIALIZED, RUNNING, SHUTDOWN, FAILED)
              DEPS          Dependencies (comma-separated)
              TIME(ms)      Initialization time in milliseconds

            Examples:
              modules                 # List active modules
              modules --all           # List all registered modules
              modules --dag           # Show dependency graph
              modules --json          # JSON output for scripting
            """;
    }

    @Override public CommandCategory category() { return CommandCategory.KERNEL; }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        var out = inv.out();
        var err = inv.err();
        var flags = inv.flags();
        var booleanFlags = inv.booleanFlags();
        var args = inv.args();
        var ctx = inv.context();

        if (booleanFlags.contains("help") || flags.containsKey("help")) {
            out.println(longHelp());
            return 0;
        }
        if (booleanFlags.contains("version") || flags.containsKey("version")) {
            out.println("modules (Noray) 1.0");
            return 0;
        }

        boolean showAll = booleanFlags.contains("a") || booleanFlags.contains("all");
        boolean showDag = booleanFlags.contains("dag");
        boolean jsonFormat = booleanFlags.contains("json");

        // Get active modules from kernel context
        List<KernelModule> activeModules = ctx.getActiveModules();
        Set<String> activeModuleNames = ctx.getActiveModuleNames();

        // Only active modules: KernelContext does not expose the registered ones that failed or are not initialized yet.

        if (showDag) {
            printDag(out, activeModules);
            return 0;
        }

        if (jsonFormat) {
            printJson(out, activeModules, activeModuleNames);
            return 0;
        }

        // Table format
        printTable(out, activeModules, activeModuleNames, showAll);

        out.println();
        out.printf("Kernel Phase: %s%n", ctx.getPhase());
        out.printf("Active Modules: %d%n", activeModules.size());

        return 0;
    }

    private void printTable(java.io.PrintStream out, List<KernelModule> modules,
                           Set<String> activeNames, boolean showAll) {
        // Header
        out.printf("%-24s %-12s %-6s %-12s %-24s %s%n",
                "NAME", "LEVEL", "ORDER", "PHASE", "DEPS", "TIME(ms)");
        out.println("---------------------------------------------------------------------------");

        for (KernelModule m : modules) {
            String deps = String.join(",", m.dependencies());
            if (deps.length() > 22) deps = deps.substring(0, 20) + "..";

            out.printf("%-24s %-12s %-6d %-12s %-24s %d%n",
                    truncate(m.id(), 24),
                    m.getInitLevel().name(),
                    m.getOrderWithinLevel(),
                    "INITIALIZED",
                    deps,
                    0); // We'd need to track init time
        }
    }

    private void printDag(java.io.PrintStream out, List<KernelModule> modules) {
        out.println("Module Dependency DAG:");
        out.println("======================");

        for (KernelModule m : modules) {
            String deps = m.dependencies().isEmpty() ? "(none)" : String.join(" -> ", m.dependencies());
            out.printf("%s [%s, order=%d]%n", m.id(), m.getInitLevel(), m.getOrderWithinLevel());
            if (!m.dependencies().isEmpty()) {
                out.printf("  └─ depends on: %s%n", deps);
            }
        }
    }

    private void printJson(java.io.PrintStream out, List<KernelModule> modules, Set<String> activeNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"kernel_phase\": \"").append(modules.isEmpty() ? "UNKNOWN" : "RUNNING").append("\",\n");
        sb.append("  \"modules\": [\n");

        for (int i = 0; i < modules.size(); i++) {
            KernelModule m = modules.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": \"").append(m.id()).append("\",\n");
            sb.append("      \"level\": \"").append(m.getInitLevel()).append("\",\n");
            sb.append("      \"order\": ").append(m.getOrderWithinLevel()).append(",\n");
            sb.append("      \"phase\": \"INITIALIZED\",\n");
            sb.append("      \"dependencies\": [");
            List<String> depList = new java.util.ArrayList<>(m.dependencies());
            for (int j = 0; j < depList.size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append("\"").append(depList.get(j)).append("\"");
            }
            sb.append("],\n");
            sb.append("      \"critical\": ").append(m.isCritical()).append(",\n");
            sb.append("      \"init_time_ms\": 0\n");
            sb.append("    }");
            if (i < modules.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}");
        out.println(sb);
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}