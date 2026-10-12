package com.redactado.noray.sandbox;

/**
 * @param exitCode  exit status of the program (137-ish when it was killed)
 * @param timedOut  killed because it exceeded the wall time
 * @param overBudget killed because it moved more data through the bridge than allowed
 * @param stderr    what it wrote to its error output (truncated)
 * @param requests  filesystem calls it made
 * @param ioBytes   bytes moved through the filesystem bridge
 */
public record SandboxResult(int exitCode, boolean timedOut, boolean overBudget, String stderr, long requests,
                            long ioBytes) {
    public boolean ok() {
        return exitCode == 0 && !timedOut && !overBudget;
    }

    /** Which limit stopped the program, if any. */
    public enum Limit {
        NONE, WALL_TIME, IO_BUDGET, CPU_TIME, HEAP, MEMORY, FILE_SIZE, KILLED, FAILED;

        public String describe() {
            return switch (this) {
                case NONE -> "finished normally";
                case WALL_TIME -> "killed: wall-time limit";
                case IO_BUDGET -> "killed: filesystem I/O budget exhausted";
                case CPU_TIME -> "killed: CPU-time limit (SIGXCPU)";
                case HEAP -> "stopped: Java heap limit (OutOfMemoryError)";
                case MEMORY -> "killed: memory limit (SIGKILL, usually the cgroup memory cap)";
                case FILE_SIZE -> "killed: file-size limit (SIGXFSZ)";
                case KILLED -> "killed by a signal";
                case FAILED -> "the program failed (non-zero exit)";
            };
        }
    }

    /** Decided from the flags, the exit status (128 + signal) and the program's error output. */
    public Limit limitHit() {
        if (timedOut) return Limit.WALL_TIME;
        if (overBudget) return Limit.IO_BUDGET;
        if (exitCode == 0) return Limit.NONE;
        // the child JVM runs with -XX:+ExitOnOutOfMemoryError, which exits with 3 and does not always print the error
        if (exitCode == 3 || stderr != null && stderr.contains("java.lang.OutOfMemoryError")) return Limit.HEAP;
        return switch (exitCode) {
            case 128 + 24 -> Limit.CPU_TIME;   // SIGXCPU
            case 128 + 25 -> Limit.FILE_SIZE;  // SIGXFSZ
            case 128 + 9 -> Limit.MEMORY;      // SIGKILL: the cgroup OOM killer, or the hard CPU limit
            default -> exitCode > 128 && exitCode < 128 + 65 ? Limit.KILLED : Limit.FAILED;
        };
    }
}
