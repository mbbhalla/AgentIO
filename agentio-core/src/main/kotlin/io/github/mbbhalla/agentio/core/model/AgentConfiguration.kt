package io.github.mbbhalla.agentio.core.model

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManagers
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.NoOperationContextMemoryManager
import io.github.mbbhalla.agentio.core.lib.ctx.provider.ContextProviders
import io.github.mbbhalla.agentio.core.lib.ctx.writer.ContextWriters
import io.github.mbbhalla.agentio.core.lib.event.EventListener
import io.github.mbbhalla.agentio.core.lib.tool.ToolsProvider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class AgentConfiguration(
    // A unique ID assigned to each Agent object
    val agentId: String,

    // Agent expertise domain for ex: Supply chain domain or Healthcare domain
    val problemDomain: String? = null,

    // LLM parameters
    val languageModelParameters: LanguageModelParameters,

    // LLM access
    val bedrockRuntimeClient: BedrockRuntimeClient,

    // Tools Provider to access
    val toolsProvider: ToolsProvider,

    // System Prompt
    val systemPrompt: String? = null,

    // Thinking mode Toggle
    val thinkingMode: ThinkingMode = ThinkingMode(maxIterations = 0),

    // Context (Conversation's messages) management — ordered chain of CMMs
    val contextMemoryManagers: ContextMemoryManagers = ContextMemoryManagers(
        value = listOf(NoOperationContextMemoryManager),
    ),

    // Delay between conversation turns to solve for LLMs not configured with enough
    val delayBetweenTurns: Duration = 0.milliseconds,

    // Context Providers
    val contextProviders: ContextProviders = ContextProviders(value = emptyList()),

    // Context Writers
    val contextWriters: ContextWriters = ContextWriters(value = emptySet()),

    // Event listener for observability hooks (logging, metrics, tracing)
    val eventListener: EventListener? = null,

    /*
        Max Turns Limit - To prevent unending conversations
        Max how many conversation turns should agent keep track of
        Usually the default 1000 is more than sufficient
     */
    val maxTurnLimit: Int = 1000,
)
