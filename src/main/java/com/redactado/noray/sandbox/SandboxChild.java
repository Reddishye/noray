package com.redactado.noray.sandbox;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Arrays;

/** Entry point of the process inside the sandbox: {@code SandboxChild <program class> [args...]}. */
public final class SandboxChild {

    private SandboxChild() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: SandboxChild <program class> [args]");
            System.exit(2);
        }
        // stdout carries the bridge; whatever the program prints goes to stderr instead
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)));
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(FileDescriptor.in)));
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
        int code = 0;
        try {
            Object program = Class.forName(args[0]).getDeclaredConstructor().newInstance();
            ((SandboxedProgram) program).run(new RemoteFileSystem(in, out), Arrays.copyOfRange(args, 1, args.length));
        } catch (Throwable t) {
            t.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }
}
