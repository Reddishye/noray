package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.service.ServiceInfo;
import com.redactado.noray.service.ServiceManager;

import java.util.List;

public final class ServiceCommand implements Command {

    private final ServiceManager serviceManager;

    public ServiceCommand(ServiceManager serviceManager) {
        this.serviceManager = serviceManager;
    }

    @Override
    public String name() {
        return "service";
    }

    @Override
    public String description() {
        return "Manage and inspect background service units (systemctl style)";
    }

    @Override
    public List<String> aliases() {
        return List.of("systemctl", "svc");
    }

    @Override
    public String synopsis() {
        return "service [list|start <unit>|stop <unit>|restart <unit>|status <unit>|enable|disable|is-active|reload|cat]";
    }

    @Override
    public int execute(CommandInvocation invocation) {
        List<String> args = invocation.args();
        String action = args.isEmpty() ? "list" : args.get(0).toLowerCase();

        switch (action) {
            case "list" -> {
                List<ServiceInfo> services = serviceManager.listServices();
                if (services.isEmpty()) {
                    invocation.out().println("No service units registered.");
                    return 0;
                }
                invocation.out().printf("%-24s %-10s %-10s %-24s %s%n", "UNIT", "STATUS", "RESTARTS", "ACTIVE SINCE", "DESCRIPTION");
                invocation.out().println("-".repeat(100));
                for (ServiceInfo info : services) {
                    String started = info.startedAt() != null ? info.startedAt().toString() : "-";
                    invocation.out().printf("%-24s %-10s %-10d %-24s %s%n",
                            info.name() + ".service", info.status(), info.restartCount(), started, info.description());
                }
                return 0;
            }
            case "start" -> {
                if (args.size() < 2) {
                    invocation.err().println("Usage: service start <unit>");
                    return 1;
                }
                String unit = args.get(1);
                serviceManager.startService(unit);
                invocation.out().printf("Started unit: %s%n", unit);
                return 0;
            }
            case "stop" -> {
                if (args.size() < 2) {
                    invocation.err().println("Usage: service stop <unit>");
                    return 1;
                }
                String unit = args.get(1);
                serviceManager.stopService(unit);
                invocation.out().printf("Stopped unit: %s%n", unit);
                return 0;
            }
            case "restart" -> {
                if (args.size() < 2) {
                    invocation.err().println("Usage: service restart <unit>");
                    return 1;
                }
                String unit = args.get(1);
                serviceManager.restartService(unit);
                invocation.out().printf("Restarted unit: %s%n", unit);
                return 0;
            }
            case "status" -> {
                if (args.size() < 2) {
                    invocation.err().println("Usage: service status <unit>");
                    return 1;
                }
                String unit = args.get(1);
                ServiceInfo info = serviceManager.getServiceInfo(unit);
                if (info == null) {
                    invocation.err().printf("Service '%s' not found%n", unit);
                    return 1;
                }
                invocation.out().printf("Unit: %s (%s)%n", info.name(), info.description());
                invocation.out().printf("Status: %s%n", info.status());
                invocation.out().printf("Restarts: %d%n", info.restartCount());
                if (info.startedAt() != null) {
                    invocation.out().printf("Active since: %s%n", info.startedAt());
                }
                if (info.lastError() != null) {
                    invocation.out().printf("Last Error: %s%n", info.lastError());
                }
                return 0;
            }
            default -> {
                invocation.err().printf("Unknown action '%s'. %s%n", action, synopsis());
                return 1;
            }
        }
    }
}