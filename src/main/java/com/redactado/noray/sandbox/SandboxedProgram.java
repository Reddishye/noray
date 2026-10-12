package com.redactado.noray.sandbox;

import com.redactado.noray.filesystem.api.FileSystemService;

/**
 * Code that runs inside the sandbox. It has no access to anything but the {@link FileSystemService} it is given,
 * which forwards to the node and only covers what the operator granted.
 */
public interface SandboxedProgram {

    void run(FileSystemService fs, String[] args) throws Exception;
}
