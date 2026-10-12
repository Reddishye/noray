package com.redactado.noray.sandbox;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * A seccomp-BPF filter in the format bubblewrap loads ({@code struct sock_filter[]}): wrong architecture kills the
 * process, a list of dangerous system calls fails with EPERM, everything else is allowed. It is a deny list on top
 * of what the namespaces and the absence of capabilities already take away, so it narrows the kernel surface the
 * program can reach; it is not the main barrier. x86-64 and aarch64 are covered; on any other architecture no filter
 * is installed (the namespaces and capabilities still apply).
 */
final class SeccompFilter {

    private static final int AUDIT_ARCH_X86_64 = 0xC000003E;
    private static final int AUDIT_ARCH_AARCH64 = 0xC00000B7;
    private static final int RET_KILL_PROCESS = 0x80000000;
    private static final int RET_ALLOW = 0x7fff0000;
    private static final int RET_ERRNO_EPERM = 0x00050000 | 1;

    /** Mount, namespaces, modules, tracing of other processes, kernel keyrings, bpf/perf, io_uring, clocks... */
    static final List<Integer> X86_64_DENIED = List.of(
            101,        // ptrace
            103,        // syslog
            134, 156,   // uselib, _sysctl
            153, 154,   // vhangup, modify_ldt
            155, 161,   // pivot_root, chroot
            159, 163, 164, 227, 305, // adjtimex, acct, settimeofday, clock_settime, clock_adjtime
            165, 166, 167, 168, 169, // mount, umount2, swapon, swapoff, reboot
            172, 173,   // iopl, ioperm
            175, 176, 313, // init_module, delete_module, finit_module
            179,        // quotactl
            212,        // lookup_dcookie
            246, 320,   // kexec_load, kexec_file_load
            248, 249, 250, // add_key, request_key, keyctl
            272, 308,   // unshare, setns
            298,        // perf_event_open
            303, 304,   // name_to_handle_at, open_by_handle_at
            310, 311, 312, // process_vm_readv/writev, kcmp
            321,        // bpf
            323,        // userfaultfd
            425, 426, 427, // io_uring_setup/enter/register
            428, 429, 430, 431, 432, 433, 442, // open_tree, move_mount, fsopen, fsconfig, fsmount, fspick, mount_setattr
            440, 448,   // process_madvise, process_mrelease
            443         // quotactl_fd
    );

    /** The same set in the asm-generic numbering used by aarch64 (calls that do not exist there are left out). */
    static final List<Integer> AARCH64_DENIED = List.of(
            117,        // ptrace
            116,        // syslog
            58,         // vhangup
            41, 51,     // pivot_root, chroot
            171, 89, 170, 112, 266, // adjtimex, acct, settimeofday, clock_settime, clock_adjtime
            40, 39, 224, 225, 142, // mount, umount2, swapon, swapoff, reboot
            105, 106, 273, // init_module, delete_module, finit_module
            60,         // quotactl
            18,         // lookup_dcookie
            104, 294,   // kexec_load, kexec_file_load
            217, 218, 219, // add_key, request_key, keyctl
            97, 268,    // unshare, setns
            241,        // perf_event_open
            264, 265,   // name_to_handle_at, open_by_handle_at
            270, 271, 272, // process_vm_readv/writev, kcmp
            280,        // bpf
            282,        // userfaultfd
            425, 426, 427, // io_uring_setup/enter/register
            428, 429, 430, 431, 432, 433, 442, // open_tree, move_mount, fsopen, fsconfig, fsmount, fspick, mount_setattr
            440, 448,   // process_madvise, process_mrelease
            443         // quotactl_fd
    );

    private SeccompFilter() {
    }

    static boolean supported() {
        return forCurrentArch() != null;
    }

    /** The filter for the architecture this JVM runs on, or {@code null} if there is none. */
    static byte[] forCurrentArch() {
        return switch (System.getProperty("os.arch", "")) {
            case "amd64", "x86_64" -> x86_64();
            case "aarch64", "arm64" -> aarch64();
            default -> null;
        };
    }

    static byte[] x86_64() {
        return build(AUDIT_ARCH_X86_64, X86_64_DENIED);
    }

    static byte[] aarch64() {
        return build(AUDIT_ARCH_AARCH64, AARCH64_DENIED);
    }

    private static byte[] build(int auditArch, List<Integer> denied) {
        int m = denied.size();
        ByteBuffer b = ByteBuffer.allocate((4 + m + 2) * 8).order(ByteOrder.LITTLE_ENDIAN);
        ins(b, 0x20, 0, 0, 4);                       // A = arch
        ins(b, 0x15, 1, 0, auditArch);               // if arch matches skip the kill
        ins(b, 0x06, 0, 0, RET_KILL_PROCESS);
        ins(b, 0x20, 0, 0, 0);                       // A = syscall number
        for (int i = 0; i < m; i++) {
            ins(b, 0x15, m - i, 0, denied.get(i));   // match: jump to the EPERM return
        }
        ins(b, 0x06, 0, 0, RET_ALLOW);
        ins(b, 0x06, 0, 0, RET_ERRNO_EPERM);
        return b.array();
    }

    private static void ins(ByteBuffer b, int code, int jt, int jf, int k) {
        b.putShort((short) code).put((byte) jt).put((byte) jf).putInt(k);
    }
}
