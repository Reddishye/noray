package com.redactado.noray.command;

/**
 * Categorization of commands for help organization and discoverability.
 * Mirrors the Linux manual page sections philosophy.
 */
public enum CommandCategory {
    /**
     * Core system commands (help, exit, version, uname).
     */
    SYSTEM("System", 1),

    /**
     * Kernel and module introspection (modules, journalctl, sysctl).
     */
    KERNEL("Kernel", 2),

    /**
     * Service lifecycle management (service, systemctl).
     */
    SERVICE("Service", 3),

    /**
     * File system and path operations (cd, pwd, ls, cat - if VFS is added).
     */
    FILESYSTEM("Filesystem", 4),

    /**
     * Process and resource inspection (ps, top, kill, free, uptime).
     */
    PROCESS("Process", 5),

    /**
     * Network and connectivity (ping, netstat, ss, ip - future).
     */
    NETWORK("Network", 6),

    /**
     * User and permission management (whoami, id, su, sudo - future).
     */
    USER("User", 7),

    /**
     * Development and debugging (debug, trace, profile - future).
     */
    DEVELOPMENT("Development", 8),

    /**
     * Miscellaneous or uncategorized commands.
     */
    OTHER("Other", 99);

    private final String displayName;
    private final int section;

    CommandCategory(String displayName, int section) {
        this.displayName = displayName;
        this.section = section;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getSection() {
        return section;
    }
}