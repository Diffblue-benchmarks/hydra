package com.addthis.hydra.job.spawn.search;

import com.addthis.hydra.job.entity.JobMacro;
import com.addthis.hydra.util.DirectedGraph;
import com.google.common.collect.ImmutableSet;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Represents the dependencies between a set of {@link JobMacro}s
 */
public class JobMacroGraph {
    private final DirectedGraph<String> graph;
    private final Map<String, MacroIncludeLocations> includeLocations;

    public JobMacroGraph(Map<String, JobMacro> macros) {
        graph = new DirectedGraph<>();
        includeLocations = new HashMap<>();

        for (String macroName : macros.keySet()) {
            addDependenciesOf(macroName, macros.get(macroName));
        }
    }

    private void addDependenciesOf(String macroName, JobMacro jobMacro) {
        graph.addNode(macroName);
        MacroIncludeLocations locations = new MacroIncludeLocations(jobMacro.getMacro());
        includeLocations.put(macroName, locations);

        for (String depName : locations.dependencies()) {
            graph.addEdge(macroName, depName);
        }
    }

    /**
     * Returns every (recursive) dependency of `macroName`
     */
    public Set<String> getDependencies(String macroName) {
        return graph.sinksClosure(macroName);
    }

    /**
     * Returns the MacroIncludeLocations object associated w/ `macroName`
     *
     * @param macroName the name of a macro which possibly depends on another macro
     * @return all locations where the dependency was included, or an empty set if it wasn't  included at all
     */
    public MacroIncludeLocations getIncludeLocations(String macroName) {
        return includeLocations.get(macroName);
    }
}