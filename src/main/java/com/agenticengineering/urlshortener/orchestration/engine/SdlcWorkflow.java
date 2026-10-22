package com.agenticengineering.urlshortener.orchestration.engine;

/**
 * The SDLC stage graph of plan section 3 (FR-020, FR-043). N03 clarification and N04 impact analysis are conditional
 * on N02; N09 testing and N10 documentation run in parallel after N08 and join at N11 (SYNC); N13 release gate is
 * conditional on N12 policy evaluation, while N14 summary follows N12 directly, so a failed mandatory check still
 * ends in the NOT READY summary without passing the release gate.
 */
public final class SdlcWorkflow {

    private static final StageGraph GRAPH = StageGraph.builder()
            .node("N01").node("N02").node("N03").node("N04").node("N05").node("N06").node("N07").node("N08").node("N09")
            .node("N10").node("N11").node("N12").node("N13").node("N14")
            .edge("N01", "N02", EdgeKind.DEPENDENCY)
            .edge("N02", "N03", EdgeKind.CONDITIONAL)
            .edge("N02", "N04", EdgeKind.CONDITIONAL)
            .edge("N02", "N05", EdgeKind.DEPENDENCY)
            .edge("N03", "N05", EdgeKind.DEPENDENCY)
            .edge("N04", "N05", EdgeKind.DEPENDENCY)
            .edge("N05", "N06", EdgeKind.DEPENDENCY)
            .edge("N06", "N07", EdgeKind.DEPENDENCY)
            .edge("N07", "N08", EdgeKind.DEPENDENCY)
            .edge("N08", "N09", EdgeKind.DEPENDENCY)
            .edge("N08", "N10", EdgeKind.DEPENDENCY)
            .edge("N09", "N11", EdgeKind.SYNC)
            .edge("N10", "N11", EdgeKind.SYNC)
            .edge("N11", "N12", EdgeKind.DEPENDENCY)
            .edge("N12", "N13", EdgeKind.CONDITIONAL)
            .edge("N12", "N14", EdgeKind.DEPENDENCY)
            .edge("N13", "N14", EdgeKind.DEPENDENCY)
            .build();

    /** The node that runs the decomposed tasks; the engine provides its executor. */
    public static final String IMPLEMENTATION_NODE = "N08";

    private SdlcWorkflow() {
    }

    public static StageGraph graph() {
        return GRAPH;
    }
}
