package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.kernel.core.NorayKernel;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Implements {@code version} — prints Noray version and build information.
 */
public final class VersionCommand implements Command {

    @Override public String name() { return "version"; }
    @Override public String description() { return "Print Noray version and build information"; }
    @Override public String synopsis() { return "version [--short|--json]"; }

    @Override
    public String longHelp() {
        return """
            Usage: version [OPTION]

            Options:
              --short       print only the version string
              --json        output as JSON
              --build-info  print detailed build information
                  --help    display this help and exit

            Examples:
              version           # Full version info
              version --short   # Just "1.0.0"
              version --json    # JSON output for scripting
            """;
    }

    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        var out = inv.out();
        var flags = inv.flags();
        var booleanFlags = inv.booleanFlags();

        if (booleanFlags.contains("help") || flags.containsKey("help")) {
            out.println(longHelp());
            return 0;
        }

        boolean shortFormat = booleanFlags.contains("short");
        boolean jsonFormat = booleanFlags.contains("json");
        boolean buildInfo = booleanFlags.contains("build-info");

        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

        if (shortFormat) {
            out.println(NorayKernel.VERSION);
            return 0;
        }

        if (jsonFormat) {
            out.println(toJson(runtime));
            return 0;
        }

        // Default: human-readable
        out.println("Noray Kernel " + NorayKernel.VERSION);
        out.println("Build time:     " + NorayKernel.BUILD_TIME);
        out.println("Java version:   " + System.getProperty("java.version"));
        out.println("Java vendor:    " + System.getProperty("java.vendor"));
        out.println("Java VM:        " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        out.println("OS:             " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        out.println("PID:            " + runtime.getPid());
        out.println("Uptime:         " + formatUptime(runtime.getUptime()));
        out.println("Class path:     " + System.getProperty("java.class.path"));

        if (buildInfo) {
            out.println();
            out.println("Build properties:");
            out.println("  noray.kernel.version=" + NorayKernel.VERSION);
            out.println("  noray.kernel.build_time=" + NorayKernel.BUILD_TIME);
        }

        return 0;
    }

    private String formatUptime(long ms) {
        long seconds = ms / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
        sb.append(secs).append("s");
        return sb.toString();
    }

    private String toJson(RuntimeMXBean runtime) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": \"").append(NorayKernel.VERSION).append("\",\n");
        sb.append("  \"build_time\": \"").append(NorayKernel.BUILD_TIME).append("\",\n");
        sb.append("  \"java\": {\n");
        sb.append("    \"version\": \"").append(escapeJson(System.getProperty("java.version"))).append("\",\n");
        sb.append("    \"vendor\": \"").append(escapeJson(System.getProperty("java.vendor"))).append("\",\n");
        sb.append("    \"vm_name\": \"").append(escapeJson(System.getProperty("java.vm.name"))).append("\",\n");
        sb.append("    \"vm_version\": \"").append(escapeJson(System.getProperty("java.vm.version"))).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"os\": {\n");
        sb.append("    \"name\": \"").append(escapeJson(System.getProperty("os.name"))).append("\",\n");
        sb.append("    \"version\": \"").append(escapeJson(System.getProperty("os.version"))).append("\",\n");
        sb.append("    \"arch\": \"").append(escapeJson(System.getProperty("os.arch"))).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"pid\": ").append(runtime.getPid()).append(",\n");
        sb.append("  \"uptime_ms\": ").append(runtime.getUptime()).append(",\n");
        sb.append("  \"class_path\": \"").append(escapeJson(System.getProperty("java.class.path"))).append("\"\n");
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}