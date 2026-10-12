package com.redactado.noray.sandbox;

import java.time.Duration;

/**
 * Limits of one sandboxed run.
 *
 * @param heapMiB     Java heap; the rest of the virtual memory is capped relative to it
 * @param cpuSeconds  CPU time before the kernel kills the process
 * @param wallTime    elapsed time before the host kills it
 * @param maxFileMiB  largest file the program can write in its scratch space
 * @param maxOpenFiles descriptors
 * @param scratchMiB  size of the private temporary directory (in memory, gone afterwards)
 * @param maxIoMiB    data the program may move through the filesystem bridge in total
 * @param cgroup      also put it in a systemd scope with a hard memory and task limit, if the system can
 */
public record SandboxProfile(int heapMiB, int cpuSeconds, Duration wallTime, int maxFileMiB, int maxOpenFiles,
                             int scratchMiB, int maxIoMiB, boolean cgroup) {

    public SandboxProfile {
        if (heapMiB < 16 || cpuSeconds < 1 || wallTime.isNegative() || wallTime.isZero() || maxFileMiB < 1
                || maxOpenFiles < 16 || scratchMiB < 1 || maxIoMiB < 1) {
            throw new IllegalArgumentException("invalid sandbox profile");
        }
    }

    public static SandboxProfile defaults() {
        return new SandboxProfile(128, 60, Duration.ofMinutes(5), 64, 256, 64, 512, true);
    }

    public SandboxProfile withWallTime(Duration d) {
        return new SandboxProfile(heapMiB, cpuSeconds, d, maxFileMiB, maxOpenFiles, scratchMiB, maxIoMiB, cgroup);
    }

    public SandboxProfile withHeapMiB(int mib) {
        return new SandboxProfile(mib, cpuSeconds, wallTime, maxFileMiB, maxOpenFiles, scratchMiB, maxIoMiB, cgroup);
    }

    public SandboxProfile withCpuSeconds(int s) {
        return new SandboxProfile(heapMiB, s, wallTime, maxFileMiB, maxOpenFiles, scratchMiB, maxIoMiB, cgroup);
    }

    public SandboxProfile withMaxIoMiB(int mib) {
        return new SandboxProfile(heapMiB, cpuSeconds, wallTime, maxFileMiB, maxOpenFiles, scratchMiB, mib, cgroup);
    }
}
