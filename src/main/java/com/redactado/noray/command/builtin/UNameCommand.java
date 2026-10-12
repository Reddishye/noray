package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.kernel.core.NorayKernel;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Implements {@code uname} — prints system information.
 * Mirrors the Linux uname command with Noray-specific extensions.
 */
public final class UNameCommand implements Command {

    @Override public Set<String> switchFlags() { return Set.of("version", "all"); }

    @Override public String name() { return "uname"; }
    @Override public String description() { return "Print kernel and system information"; }
    @Override public String synopsis() { return "uname [-a|-s|-n|-r|-v|-m|-p|-i|-o]"; }

    @Override
    public String longHelp() {
        return """
            Usage: uname [OPTION]...
            Print certain system information. With no options, same as -s.

            -a, --all                print all information, in the following order,
                                     except omit -p and -i if unknown
            -s, --kernel-name        print the kernel name
            -n, --nodename           print the network node hostname
            -r, --kernel-release     print the kernel release
            -v, --kernel-version     print the kernel version
            -m, --machine            print the machine hardware name
            -p, --processor          print the processor type (non-portable)
            -i, --hardware-platform  print the hardware platform (non-portable)
            -o, --operating-system   print the operating system
                --help               display this help and exit
                --version            output version information and exit

            Noray extensions:
                --build-time         print the kernel build timestamp
                --java-version       print the Java runtime version
                --java-vendor        print the Java vendor
                --java-home          print the Java home directory
            """;
    }

    @Override public CommandCategory category() { return CommandCategory.KERNEL; }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        var out = inv.out();
        var err = inv.err();
        var flags = inv.flags();
        var booleanFlags = inv.booleanFlags();

        if (booleanFlags.contains("help") || flags.containsKey("help")) {
            out.println(longHelp());
            return 0;
        }
        if (booleanFlags.contains("version") || flags.containsKey("version")) {
            out.println("uname (Noray) 1.0");
            return 0;
        }

        boolean all = booleanFlags.contains("a") || booleanFlags.contains("all");
        boolean kernelName = all || booleanFlags.contains("s") || booleanFlags.contains("kernel-name");
        boolean nodename = all || booleanFlags.contains("n") || booleanFlags.contains("nodename");
        boolean kernelRelease = all || booleanFlags.contains("r") || booleanFlags.contains("kernel-release");
        boolean kernelVersion = all || booleanFlags.contains("v") || booleanFlags.contains("kernel-version");
        boolean machine = all || booleanFlags.contains("m") || booleanFlags.contains("machine");
        boolean processor = all || booleanFlags.contains("p") || booleanFlags.contains("processor");
        boolean hardwarePlatform = all || booleanFlags.contains("i") || booleanFlags.contains("hardware-platform");
        boolean operatingSystem = all || booleanFlags.contains("o") || booleanFlags.contains("operating-system");
        boolean buildTime = booleanFlags.contains("build-time");
        boolean javaVersion = booleanFlags.contains("java-version");
        boolean javaVendor = booleanFlags.contains("java-vendor");
        boolean javaHome = booleanFlags.contains("java-home");

        // Default to -s if no flags
        if (!all && !kernelName && !nodename && !kernelRelease && !kernelVersion &&
            !machine && !processor && !hardwarePlatform && !operatingSystem &&
            !buildTime && !javaVersion && !javaVendor && !javaHome) {
            kernelName = true;
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;

        // Kernel name
        if (kernelName) {
            if (!first) sb.append(' ');
            sb.append("Noray");
            first = false;
        }

        // Nodename (hostname)
        if (nodename) {
            if (!first) sb.append(' ');
            String hostname = System.getenv().getOrDefault("HOSTNAME", "noray");
            sb.append(hostname);
            first = false;
        }

        // Kernel release
        if (kernelRelease) {
            if (!first) sb.append(' ');
            sb.append(NorayKernel.VERSION);
            first = false;
        }

        // Kernel version
        if (kernelVersion) {
            if (!first) sb.append(' ');
            sb.append(String.format("%s %s %s",
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version"),
                System.getProperty("java.vm.info")));
            first = false;
        }

        // Machine (architecture)
        if (machine) {
            if (!first) sb.append(' ');
            sb.append(System.getProperty("os.arch"));
            first = false;
        }

        // Processor (same as machine for JVM)
        if (processor) {
            if (!first) sb.append(' ');
            sb.append(System.getProperty("os.arch"));
            first = false;
        }

        // Hardware platform
        if (hardwarePlatform) {
            if (!first) sb.append(' ');
            sb.append(System.getProperty("os.name") + " " + System.getProperty("os.version"));
            first = false;
        }

        // Operating system
        if (operatingSystem) {
            if (!first) sb.append(' ');
            sb.append("NorayOS");
            first = false;
        }

        // Build time
        if (buildTime) {
            if (!first) sb.append(' ');
            sb.append(NorayKernel.BUILD_TIME);
            first = false;
        }

        // Java version
        if (javaVersion) {
            if (!first) sb.append(' ');
            sb.append(System.getProperty("java.version"));
            first = false;
        }

        // Java vendor
        if (javaVendor) {
            if (!first) sb.append(' ');
            sb.append(System.getProperty("java.vendor"));
            first = false;
        }

        // Java home
        if (javaHome) {
            if (!first) sb.append(' ');
            sb.append(System.getProperty("java.home"));
            first = false;
        }

        out.println(sb);
        return 0;
    }
}