package com.redactado.noray.filesystem.replica;

import com.redactado.noray.filesystem.volume.VolumeManager;
import com.redactado.noray.kernel.api.InitLevel;
import com.redactado.noray.kernel.api.KernelContext;
import com.redactado.noray.kernel.api.KernelModule;

import java.util.Set;

public class ReplicaModule implements KernelModule {
    private RepairService repairService;

    @Override
    public String id() {
        return "noray.fs.repl";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("noray.fs");
    }

    @Override
    public InitLevel getInitLevel() {
        return InitLevel.DRIVER;
    }

    @Override
    public int getOrderWithinLevel() {
        return 0;
    }

    @Override
    public boolean isCritical() {
        return false;
    }

    @Override
    public void init(KernelContext context) throws Exception {
        int n = context.getConfiguration().getInt("noray.fs.replica.n", 3);
        int w = context.getConfiguration().getInt("noray.fs.replica.w", 2);
        int r = context.getConfiguration().getInt("noray.fs.replica.r", 2);
        boolean allowW1 = context.getConfiguration().getBoolean("noray.fs.replica.allow_w1", false);

        QuorumSpec spec = new QuorumSpec(n, w, r);
        VolumeManager volumeManager = context.getServiceRegistry().require(VolumeManager.class);

        DefaultReplicaPlanner planner = new DefaultReplicaPlanner(volumeManager, spec, allowW1);
        context.getServiceRegistry().register(ReplicaPlanner.class, planner);

        RepairService repair = new RepairService(context.getEventBus(), volumeManager, planner);
        context.registerResource(id(), repair);
        this.repairService = repair;
    }

    @Override
    public void shutdown(KernelContext context) throws Exception {
        if (repairService != null) {
            repairService.close();
            repairService = null;
        }
    }
}
