package com.redactado.noray.pkg;

import com.redactado.noray.kernel.core.NorayKernel;

/**
 * Applies package changes to the running node (hot load, PK2). Registered by the bootstrap; sensitive, like the
 * package manager itself.
 */
public final class PackageRuntime {

    private final NorayKernel kernel;
    private final PackageManager packages;

    public PackageRuntime(NorayKernel kernel, PackageManager packages) {
        this.kernel = kernel;
        this.packages = packages;
    }

    /** Unloads the package's module if loaded and loads its active JVM version, if any. */
    public String apply(String id) {
        return PackageLoader.apply(kernel, packages, id);
    }
}
