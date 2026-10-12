package com.redactado.noray.pkg;

import com.redactado.noray.kernel.api.KernelModule;
import com.redactado.noray.kernel.core.NorayKernel;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers the active, approved JVM packages as sandboxed modules before the kernel boots. Each one is re-verified,
 * copied to a private directory and loaded from there (so a file swapped in the store after the check is never loaded),
 * must declare the same module id as its package id, and gets exactly the approved grants: a
 * {@code noray.module.<id>.grants} line in the configuration cannot widen them. A package that fails any of this is
 * skipped and recorded; it never stops the node from booting.
 */
public final class PackageLoader {

    private static final System.Logger LOG = System.getLogger("noray.pkg");

    private PackageLoader() {
    }

    /** @return the ids registered */
    public static List<String> registerActive(NorayKernel kernel, PackageManager packages) {
        List<String> loaded = new ArrayList<>();
        for (String id : packages.activeJvmPackages()) {
            try {
                KernelModule module = prepare(kernel, packages, id);
                kernel.registerSandboxedModule(module);
                loaded.add(id);
            } catch (Exception | LinkageError e) {
                LOG.log(System.Logger.Level.ERROR, "package " + id + " not loaded: " + e.getMessage());
                packages.audit().record("load-refused", id, String.valueOf(e.getMessage()));
            }
        }
        return loaded;
    }

    /**
     * Brings a running kernel in line with the package's current state: unloads the module if it is loaded, then loads
     * the active version (if there is one, it is a JVM package and it verifies). Used after approve, activate, rollback
     * and deactivate, so no restart is needed.
     *
     * @return what happened, for the operator
     */
    public static String apply(NorayKernel kernel, PackageManager packages, String id) {
        boolean wasLoaded = kernel.unloadModule(id);
        if (!packages.activeJvmPackages().contains(id)) {
            return wasLoaded ? "unloaded" : "nothing to load";
        }
        try {
            KernelModule module = prepare(kernel, packages, id);
            kernel.loadSandboxedModuleLive(module);
            return wasLoaded ? "reloaded" : "loaded";
        } catch (Exception | LinkageError e) {
            packages.audit().record("load-refused", id, String.valueOf(e.getMessage()));
            throw new PackageException(PackageException.Reason.CONFLICT, id + " could not be loaded: "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    /** Verifies, copies and instantiates the active version; files the class loader and copies as the module's resources. */
    private static KernelModule prepare(NorayKernel kernel, PackageManager packages, String id) throws Exception {
        Path work = null;
        try {
            PackageManager.Loadable l = packages.loadActive(id);
            work = Files.createTempDirectory("noray-module-");
            StoreFiles.restrict(work);
            List<URL> urls = new ArrayList<>();
            urls.add(copy(work.resolve("payload.jar"), l.payload()));
            for (int i = 0; i < l.dependencyPayloads().size(); i++) {
                urls.add(copy(work.resolve("dep" + i + ".jar"), l.dependencyPayloads().get(i)));
            }
            URLClassLoader cl = new URLClassLoader("pkg:" + id, urls.toArray(URL[]::new), PackageLoader.class.getClassLoader());
            Object instance;
            try {
                instance = Class.forName(l.manifest().main(), true, cl).getDeclaredConstructor().newInstance();
            } catch (Exception | LinkageError e) {
                cl.close();
                throw e;
            }
            if (!(instance instanceof KernelModule module)) {
                cl.close();
                throw new PackageException(PackageException.Reason.CONFLICT, l.manifest().main() + " is not a KernelModule");
            }
            if (!module.id().equals(id)) {
                cl.close();
                throw new PackageException(PackageException.Reason.CONFLICT, "module id '" + module.id()
                        + "' differs from package id '" + id + "'");
            }
            String key = "noray.module." + id + ".grants";
            String approved = l.grants().toString();
            String configured = kernel.getContext().getConfiguration().get(key, null);
            if (configured != null && !configured.equals(approved)) {
                LOG.log(System.Logger.Level.WARNING, key + " in the configuration is ignored for packages; the approved "
                        + "grants apply: " + approved);
            }
            kernel.getContext().getConfiguration().set(key, approved);
            Path dir = work;
            kernel.registerModuleResource(id, () -> {
                cl.close();
                PackageManager.deleteQuietly(dir);
            });
            work = null; // owned by the resource now
            packages.audit().record("load", l.manifest().toString(), "grants=" + approved);
            return module;
        } finally {
            PackageManager.deleteQuietly(work);
        }
    }

    private static URL copy(Path p, byte[] data) throws IOException {
        Files.write(p, data);
        StoreFiles.restrict(p);
        return p.toUri().toURL();
    }
}
