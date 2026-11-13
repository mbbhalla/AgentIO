package io.github.mbbhalla.agentio.module.patterns.goap

import java.util.PriorityQueue
import kotlin.reflect.KClass

/**
 * Deterministic A* planner over fact-type states.
 *
 * A state is the set of fact types on the blackboard. An action is applicable when its
 * preconditions are a subset of the state and its effect is not yet present; applying it adds the
 * effect. The planner returns the cheapest action sequence whose final state contains [Plan]'s
 * goal type, or null when no sequence exists. The search space is tiny (one node per distinct
 * type set reached), so plans are found in microseconds and can be recomputed after every action.
 */
object GoapPlanner {
    data class Plan(
        val actions: List<GoapAction<*>>,
        val cost: Int,
    ) {
        val isEmpty: Boolean get() = actions.isEmpty()

        override fun toString(): String =
            if (isEmpty) {
                "Plan[goal already satisfied]"
            } else {
                actions.joinToString(
                    " -> ",
                    prefix = "Plan(cost=$cost)[",
                    postfix = "]",
                ) { it.name }
            }
    }

    private data class Node(
        val state: Set<KClass<*>>,
        val costSoFar: Int,
        val estimatedTotal: Int,
        val path: List<GoapAction<*>>,
    )

    fun plan(
        state: Set<KClass<*>>,
        goal: KClass<*>,
        actions: Collection<GoapAction<*>>,
    ): Plan? {
        if (goal in state) return Plan(actions = emptyList(), cost = 0)

        // Admissible heuristic: at least one more action is needed, and none is cheaper than the cheapest action.
        val cheapestAction = actions.minOfOrNull { it.cost } ?: return null

        fun heuristic(s: Set<KClass<*>>): Int = if (goal in s) 0 else cheapestAction

        val frontier = PriorityQueue<Node>(compareBy<Node> { it.estimatedTotal }.thenBy { it.path.size })
        frontier.add(Node(state = state, costSoFar = 0, estimatedTotal = heuristic(state), path = emptyList()))
        val bestKnownCost = mutableMapOf(state to 0)

        while (frontier.isNotEmpty()) {
            val current = frontier.poll()
            if (goal in current.state) return Plan(actions = current.path, cost = current.costSoFar)
            if (current.costSoFar > (bestKnownCost[current.state] ?: Int.MAX_VALUE)) continue

            actions
                .filter { action -> action.produces !in current.state && current.state.containsAll(action.requires) }
                .forEach { action ->
                    val nextState = current.state + action.produces
                    val nextCost = current.costSoFar + action.cost
                    if (nextCost < (bestKnownCost[nextState] ?: Int.MAX_VALUE)) {
                        bestKnownCost[nextState] = nextCost
                        frontier.add(
                            Node(
                                state = nextState,
                                costSoFar = nextCost,
                                estimatedTotal = nextCost + heuristic(nextState),
                                path = current.path + action,
                            ),
                        )
                    }
                }
        }
        return null
    }
}
