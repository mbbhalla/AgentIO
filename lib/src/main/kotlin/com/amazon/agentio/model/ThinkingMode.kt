package com.amazon.agentio.model

/*
    Agent after many turns provide an answer. sometimes we want to nudge
    agent to think again of all the conversation. This leads to accuracy bump
    Thinking mode allows for that, where client can set this up and lib will
    put user role "thinking" message to the agent
 */
@JvmInline
value class ThinkingMode(
    /*
        how many iterations to think
     */
    val maxIterations: Int,
) {
    companion object {
        /*
            The text given to LLM to think or reason
         */
        val THINKING_MODE_PROMPT = """
            It is critically important to produce correct output.
            Reanalyze your work again to ensure 100% correctness in your response.
        """.trimIndent()
    }
}
