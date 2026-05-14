package io.github.mbbhalla.agentio.cmm.impl.adaptive

/**
 * Pure functions for priority-aware context reshuffling.
 *
 * Solves a constrained assignment problem: place highest-importance segments at
 * highest-attention positions, subject to structural constraints (anchoring,
 * adjacency groups, group ordering).
 *
 * All functions are pure — no mutable state, no side effects.
 */
object ContextReshuffler {

    /**
     * Reshuffle segments to maximize alignment between importance and attention.
     *
     * Algorithm:
     * 1. Partition into anchored (fixed position) and movable segments
     * 2. Collect available positions (those not occupied by anchored segments)
     * 3. Sort movable segments by importance descending
     * 4. Sort available positions by attention score descending
     * 5. Assign highest-importance → highest-attention via greedy matching
     * 6. Enforce adjacency constraints as a post-processing step
     *
     * @param segments The context segments to reshuffle.
     * @param heatmap The current empirical attention heatmap.
     * @return New list of segments with updated positions, sorted by position.
     */
    fun reshuffle(
        segments: List<ContextSegment>,
        heatmap: AttentionHeatmap,
    ): List<ContextSegment> {
        val (anchored, movable) = segments.partition { it.constraints.isAnchored }
        val anchoredPositions = anchored.map { it.position }.toSet()
        val allPositions = (0 until segments.size).toList()
        val availablePositions = allPositions.filter { it !in anchoredPositions }

        // Sort available positions by attention score (hottest first)
        val sortedPositions = availablePositions.sortedByDescending { heatmap.scoreAt(it) }

        // Sort movable segments by importance (most important first)
        val sortedMovable = movable.sortedByDescending { it.importanceScore }

        // Greedy assignment: highest importance → highest attention position
        val assigned = sortedMovable.zip(sortedPositions).map { (segment, position) ->
            segment.copy(position = position)
        }

        // Handle case where there are more movable segments than available positions
        // (shouldn't happen in practice, but defensive)
        val unassigned = if (sortedMovable.size > sortedPositions.size) {
            sortedMovable.drop(sortedPositions.size)
        } else {
            emptyList()
        }

        val allSegments = anchored + assigned + unassigned

        // Enforce adjacency constraints
        val result = enforceAdjacencyConstraints(allSegments)

        return result.sortedBy { it.position }
    }

    /**
     * Enforce adjacency constraints: segments in the same adjacency group must be
     * placed at consecutive positions, preserving their relative order within the group.
     *
     * Strategy: find the position of the first member of each group (by adjacencyOrder),
     * then pack the remaining group members into consecutive positions starting there.
     */
    internal fun enforceAdjacencyConstraints(
        segments: List<ContextSegment>,
    ): List<ContextSegment> {
        // Identify adjacency groups
        val groups = segments
            .filter { it.constraints.adjacencyGroupId != null }
            .groupBy { it.constraints.adjacencyGroupId!! }

        if (groups.isEmpty()) return segments

        // For each group, find the minimum position assigned to any member,
        // then reassign all members to consecutive positions starting there,
        // ordered by adjacencyOrder.
        val reassignments = mutableMapOf<String, Int>() // segment.id → new position
        val occupiedPositions = mutableSetOf<Int>()

        // First pass: collect positions occupied by non-grouped and anchored segments
        segments
            .filter { it.constraints.adjacencyGroupId == null || it.constraints.isAnchored }
            .forEach { occupiedPositions.add(it.position) }

        for ((_, groupMembers) in groups) {
            val sorted = groupMembers.sortedBy { it.constraints.adjacencyOrder }
            val startPosition = sorted.minOf { it.position }

            var pos = startPosition
            for (member in sorted) {
                // Skip positions occupied by anchored segments
                while (pos in occupiedPositions) pos++
                reassignments[member.id] = pos
                occupiedPositions.add(pos)
                pos++
            }
        }

        return segments.map { segment ->
            reassignments[segment.id]?.let { newPos ->
                segment.copy(position = newPos)
            } ?: segment
        }
    }
}
