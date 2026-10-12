package com.redactado.noray.kernel.core;

import com.redactado.noray.kernel.api.KernelModule;
import com.redactado.noray.kernel.exception.KernelException;

import java.util.*;

public final class DependencyResolver {

    public static List<KernelModule> resolveExecutionOrder(List<KernelModule> modules) {
        if (modules == null || modules.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, KernelModule> moduleMap = new HashMap<>();
        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (KernelModule module : modules) {
            if (moduleMap.putIfAbsent(module.id(), module) != null) {
                throw new KernelException("Duplicate module ID detected: " + module.id());
            }
            graph.put(module.id(), new HashSet<>());
            inDegree.put(module.id(), 0);
        }

        for (KernelModule module : modules) {
            for (String depId : module.dependencies()) {
                KernelModule depModule = moduleMap.get(depId);
                if (depModule == null) {
                    throw new KernelException(
                            "Unresolved dependency: Module [" + module.id() + "] requires missing module [" + depId + "]"
                    );
                }
                if (depModule.getInitLevel().getPriority() > module.getInitLevel().getPriority()) {
                    throw new KernelException(
                            "Invalid dependency inversion: Module [" + module.id() + "] (Level " + module.getInitLevel() +
                                    ") cannot depend on later level module [" + depId + "] (Level " + depModule.getInitLevel() + ")"
                    );
                }
                graph.get(depId).add(module.id());
                inDegree.put(module.id(), inDegree.get(module.id()) + 1);
            }
        }

        Comparator<String> modulePriorityComparator = Comparator
                .comparingInt((String id) -> moduleMap.get(id).getInitLevel().getPriority())
                .thenComparingInt(id -> moduleMap.get(id).getOrderWithinLevel())
                .thenComparing(id -> id);

        PriorityQueue<String> readyQueue = new PriorityQueue<>(modulePriorityComparator);
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                readyQueue.offer(entry.getKey());
            }
        }

        List<KernelModule> sorted = new ArrayList<>(modules.size());
        while (!readyQueue.isEmpty()) {
            String current = readyQueue.poll();
            sorted.add(moduleMap.get(current));

            for (String dependent : graph.get(current)) {
                int updatedInDegree = inDegree.get(dependent) - 1;
                inDegree.put(dependent, updatedInDegree);
                if (updatedInDegree == 0) {
                    readyQueue.offer(dependent);
                }
            }
        }

        if (sorted.size() != moduleMap.size()) {
            throw new KernelException("Circular dependency detected in Kernel modules!");
        }

        return Collections.unmodifiableList(sorted);
    }
}