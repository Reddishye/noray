package com.redactado.noray.repo;

import com.redactado.noray.kernel.api.InitLevel;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelModule;

import java.util.Set;

public final class RepoModule implements KernelModule {

    private DefaultRepoService repoService;

    @Override
    public String id() {
        return "noray.repo";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("noray.fs");
    }

    @Override
    public InitLevel getInitLevel() {
        return InitLevel.APPLICATION;
    }

    @Override
    public boolean isCritical() {
        return false;
    }

    @Override
    public void init(KernelContext context) {
        repoService = new DefaultRepoService();
        context.getServiceRegistry().register(RepoService.class, repoService);
    }

    @Override
    public void shutdown(KernelContext context) {
        repoService = null;
    }
}
