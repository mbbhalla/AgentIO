package io.github.mbbhalla.agentio.core.lib

import aws.sdk.kotlin.services.bedrockruntime.model.AutoToolChoice
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.SystemContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.Tool
import aws.sdk.kotlin.services.bedrockruntime.model.ToolChoice
import io.github.mbbhalla.agentio.core.common.JSON_TAG_END
import io.github.mbbhalla.agentio.core.common.JSON_TAG_START
import io.github.mbbhalla.agentio.core.common.JsonSchemaUtil
import io.github.mbbhalla.agentio.core.common.JsonString
import io.github.mbbhalla.agentio.core.common.REGEX_JSON_EXTRACT
import io.github.mbbhalla.agentio.core.common.toDocument
import io.github.mbbhalla.agentio.core.lib.ctx.cmm.ContextMemoryManager
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.ThinkingMode
import io.github.mbbhalla.agentio.core.model.conversation.Conversation
import io.github.mbbhalla.agentio.core.model.conversation.IndexedConversation
import io.github.mbbhalla.agentio.core.model.event.Event
import io.github.mbbhalla.agentio.core.model.event.EventPayload
import io.vavr.control.Try
import io.vavr.kotlin.Try
import io.vavr.kotlin.failure
import io.vavr.kotlin.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass
import kotlin.time.measureTimedValue

interface Instructible<I : Instructible.WithInstruction, O : Any> {
    interface WithInstruction {
        // A unique ID assigned to each instruction/Ask passed to the Agent
        fun instructionId(): String

        // The instruction or Ask to the Agent
        fun instruction(): String

        // Optional System instruction / prompt
        fun systemInstruction(): String?
    }

    suspend fun invoke(input: I): O
}

/*
    Represents the output from Agent invoke method
 */
data class AgentOutput<O>(
    val instructionId: String,
    val conversation: Conversation,
    val output: O,
)

abstract class AbstractAgenticFunction<I : Instructible.WithInstruction, O : Any>(
    private val agentConfiguration: AgentConfiguration,
) : Instructible<I, Try<AgentOutput<O>>> {
    companion object {
        private val LOG = LoggerFactory.getLogger(AbstractAgenticFunction::class.java)
    }

    abstract fun getInputKClass(): KClass<I>

    abstract fun getOutputKClass(): KClass<O>

    @Suppress("TooGenericExceptionCaught")
    final override suspend fun invoke(input: I): Try<AgentOutput<O>> =
        withContext(Dispatchers.IO) {
            val eventListeners = agentConfiguration.eventListeners

            eventListeners.dispatch(
                Event(
                    payload =
                        EventPayload.AgentInvocationStart(
                            agentId = agentConfiguration.agentId,
                            instructionId = input.instructionId(),
                            instruction = input.instruction(),
                        ),
                ),
            )

            val (result, duration) =
                measureTimedValue {
                    try {
                        val output = coreLogic(input)
                        LOG.debug("Successfully computed result for ${input.instructionId()}")
                        success(output)
                    } catch (e: Exception) {
                        LOG.error("Failure in computing result for ${input.instructionId()}")
                        failure(e)
                    }
                }

            eventListeners.dispatch(
                Event(
                    payload =
                        EventPayload.AgentInvocationEnd(
                            agentId = agentConfiguration.agentId,
                            instructionId = input.instructionId(),
                            totalTurns = result.map { it.conversation.messages.size }.getOrElse { 0 },
                            totalInputTokens = result.map { it.conversation.tokenUsage.totalInputTokens }.getOrElse { 0 },
                            totalOutputTokens = result.map { it.conversation.tokenUsage.totalOutputTokens }.getOrElse { 0 },
                            success = result.isSuccess,
                            error = if (result.isFailure) result.cause else null,
                            latency = duration,
                        ),
                ),
            )

            result
        }

    /*
        Equivalent to getSequence for supporting suspending methods
     */
    private fun <T> generateFlow(
        seed: T,
        nextFunction: suspend (T) -> T?,
    ): Flow<T> =
        io.github.mbbhalla.agentio.core.common
            .generateFlow(seed, nextFunction)

    /*
        Call Bedrock Converse API
     */
    private suspend fun callBedrock(
        input: I,
        agentConfiguration: AgentConfiguration,
        conversation: Conversation,
        tools: List<Tool.ToolSpec>,
    ): ConverseResponse {
        LOG.debug("\uD83E\uDD16 - Calling Bedrock Converse API ...")

        /*
            Models have their specific request parameters. For example Claude models have
            thinking mode or max output tokens config:

            anthropic_beta = [ "output-128k-2025-02-19" ]
            thinking = {
               "type": "enabled"/"disabled"
            }
         */
        val additionalModelRequestFields =
            buildJsonObject {
                agentConfiguration.languageModelParameters.additionalModelRequestFields
                    .forEach {
                        put(it.key, it.value)
                    }
            }.toDocument()

        return agentConfiguration.bedrockRuntimeClient.converse(
            ConverseRequest {
                this.modelId = agentConfiguration.languageModelParameters.llm.id
                this.inferenceConfig {
                    agentConfiguration.languageModelParameters.topP?.let { topP = it.value }
                    agentConfiguration.languageModelParameters.temperature?.let { temperature = it.value }
                    maxTokens = agentConfiguration.languageModelParameters.maxOutputTokens
                }
                this.additionalModelRequestFields = additionalModelRequestFields
                this.messages = conversation.converseMessages()

                if (tools.isNotEmpty()) {
                    this.toolConfig {
                        this.toolChoice =
                            ToolChoice.Auto(
                                value = AutoToolChoice.invoke { },
                            )
                        this.tools = tools
                    }
                }

                this.system =
                    (input.systemInstruction() ?: agentConfiguration.systemPrompt) ?.let {
                        listOf(
                            SystemContentBlock.Text(
                                value = it,
                            ),
                        )
                    }
            },
        )
    }

    /*
        Orchestrates one agent invocation: build the initial conversation, drive the
        turn loop to completion, then extract, validate, and persist the result.

        Each step below is delegated to a single-purpose function so this method stays
        at one level of abstraction — the "what", not the "how".
     */
    private suspend fun coreLogic(input: I): AgentOutput<O> {
        val inputSchemaJson = encodeSchema(getInputKClass())
        val outputSchemaJson = encodeSchema(getOutputKClass())
        val tools = agentConfiguration.toolsProvider.listTools()

        LOG.debug("Got input schema: {}", inputSchemaJson)
        LOG.debug("Got output schema: {}", outputSchemaJson)
        LOG.debug("Got tools: {}", tools.map { it.value.name })

        /*
            Generate a Flow of Conversations. Each element processes the last message of
            the previous Conversation (an LLM call, a tool call, or a thinking-mode
            continuation), producing a new accumulative Conversation. The Flow starts
            from the initial prompt and ends when a turn yields null or maxTurnLimit is hit.
         */
        val conversationFlow =
            generateFlow(
                IndexedConversation(
                    turnNumber = 0,
                    conversation = buildInitialConversation(input, inputSchemaJson, outputSchemaJson),
                ),
            ) { indexed ->
                LOG.debug(
                    "###############################################\n{}\n###############################################",
                    indexed.conversation
                        .lastMessage()
                        .message.content,
                )

                delay(duration = agentConfiguration.delayBetweenTurns)

                stepConversation(
                    input = input,
                    conversation = indexed.conversation,
                    tools = tools,
                    turnNumber = indexed.turnNumber,
                )?.let { nextConversation -> advanceTurn(indexed, nextConversation) }
            }

        val outputConversation = conversationFlow.take(agentConfiguration.maxTurnLimit).last().conversation

        val output = extractAndValidateOutput(outputConversation, outputSchemaJson)

        persistConversation(input, outputConversation)

        return AgentOutput(
            instructionId = input.instructionId(),
            /*
                the last Conversation object holds the entire conversation
                including all messages and total tokens used in conversation
             */
            conversation = outputConversation,
            output = output,
        )
    }

    /*
        Encode the JSON Schema of a serializable model class as a JSON string.
     */
    private fun encodeSchema(kClass: KClass<*>): String =
        JsonSchemaUtil.json.encodeToString(
            JsonObject.serializer(),
            JsonSchemaUtil.generateJsonSchema(kClass),
        )

    /*
        Build the initial Conversation: the instruction prompt followed by any context
        loaded from the configured ContextProviders (long term memory).
     */
    private fun buildInitialConversation(
        input: I,
        inputSchemaJson: String,
        outputSchemaJson: String,
    ): Conversation =
        Conversation.initialize(
            texts =
                listOf(buildInstructionPrompt(input, inputSchemaJson, outputSchemaJson)) +
                    agentConfiguration.contextProviders.value
                        .map { contextProvider -> contextProvider.context(input) },
        )

    /*
        Construct the instruction prompt that seeds the conversation, embedding the
        problem domain, the instruction, the serialized input, and the input/output schemas.
     */
    @OptIn(InternalSerializationApi::class)
    private fun buildInstructionPrompt(
        input: I,
        inputSchemaJson: String,
        outputSchemaJson: String,
    ): String {
        val domainClause = agentConfiguration.problemDomain?.let { "expert in $it" }
        val serializedInput = JsonSchemaUtil.json.encodeToString(getInputKClass().serializer(), input)
        return """
            You are an AI Agent $domainClause, which needs to execute the following instruction:

            "${input.instruction()}"

            RULES TO FOLLOW:

            1. COMPUTE OUTPUT DATA JSON OBJECT FROM INPUT DATA JSON OBJECT.

            2. USE ALL THE TOOLS PROVIDED TO STEP BY STEP REASON ABOUT THE COMPUTATION.

            3. Output MUST BE "JSON format" adhering to Output JSON Schema:
            "$outputSchemaJson"

            INPUT DETAILS:

            Input Data is:
            $serializedInput

            Input Schema is:
            $inputSchemaJson

            OUTPUT FORMAT:

            Enclose output JSON in the following strings:
            ${JSON_TAG_START}<Output JSON here>$JSON_TAG_END

            """.trimIndent()
    }

    /*
        Advance the conversation by one turn based on the last message. Returns the next
        Conversation, or null to terminate the loop. This is the turn state machine —
        each branch delegates to a dedicated handler.
     */
    private suspend fun stepConversation(
        input: I,
        conversation: Conversation,
        tools: List<Tool.ToolSpec>,
        turnNumber: Int,
    ): Conversation? {
        val role = conversation.lastMessage().message.role
        val contentBlocks = conversation.lastMessage().message.content
        val lastContentBlock = contentBlocks.last()
        val toolUseBlocks = contentBlocks.filterIsInstance<ContentBlock.ToolUse>()

        return when {
            isTerminalStopReason(conversation.stopReason) ->
                continueThinkingOrStop(conversation)

            role is ConversationRole.User &&
                (lastContentBlock is ContentBlock.Text || lastContentBlock is ContentBlock.ToolResult) ->
                callLlmAndAppend(input, conversation, tools, turnNumber)

            role is ConversationRole.Assistant && lastContentBlock is ContentBlock.Text && toolUseBlocks.isEmpty() -> {
                LOG.debug("Got Assistant/ContentBlock.Text with Stop reason: {}", conversation.stopReason)
                conversation
            }

            role is ConversationRole.Assistant && toolUseBlocks.isNotEmpty() ->
                executeToolCalls(conversation, toolUseBlocks, turnNumber)

            else -> {
                LOG.error("Invalid combination: $role, ${lastContentBlock.javaClass.simpleName}")
                null
            }
        }
    }

    /*
        A stop reason that ends the model's turn (as opposed to MaxTokens/ToolUse which
        continue the loop). Used to decide whether to trigger thinking-mode continuation.
     */
    private fun isTerminalStopReason(stopReason: StopReason?): Boolean =
        stopReason is StopReason.EndTurn ||
            stopReason is StopReason.StopSequence ||
            stopReason is StopReason.ContentFiltered

    /*
        On a terminal stop reason, either append the thinking-mode prompt for another
        reasoning iteration (if iterations remain) or return null to stop the loop.
     */
    private fun continueThinkingOrStop(conversation: Conversation): Conversation? =
        if (conversation.thinkingModeCounter < agentConfiguration.thinkingMode.maxIterations) {
            conversation.appendUserRoleContent(
                contentBlock = ContentBlock.Text(value = ThinkingMode.THINKING_MODE_PROMPT),
                additionalTokenUsage = Conversation.TOKEN_USAGE_ZERO,
                incrementThinkingModeCounter = 1,
            )
        } else {
            null
        }

    /*
        Call the Bedrock Converse API, dispatching Before/After LLM events, and append the
        model's supported content to the conversation.

        If the response contains no framework-supported content blocks (e.g. models with
        adaptive thinking returning only unsupported block types), the conversation is
        returned unchanged — the loop re-sends the same User message on the next iteration,
        bounded by maxTurnLimit to prevent infinite retries.
     */
    private suspend fun callLlmAndAppend(
        input: I,
        conversation: Conversation,
        tools: List<Tool.ToolSpec>,
        turnNumber: Int,
    ): Conversation {
        agentConfiguration.eventListeners.dispatch(
            Event(
                payload =
                    EventPayload.BeforeLlmCall(
                        modelId = agentConfiguration.languageModelParameters.llm.id,
                        messageCount = conversation.messages.size,
                        turnNumber = turnNumber,
                    ),
            ),
        )

        val (llmResult, llmLatency) =
            measureTimedValue {
                runCatching {
                    callBedrock(
                        input = input,
                        agentConfiguration = agentConfiguration,
                        conversation = conversation,
                        tools = tools,
                    )
                }
            }

        val response =
            llmResult.getOrElse { e ->
                agentConfiguration.eventListeners.dispatch(
                    Event(
                        payload =
                            EventPayload.AfterLlmCall(
                                modelId = agentConfiguration.languageModelParameters.llm.id,
                                stopReason = null,
                                inputTokens = 0,
                                outputTokens = 0,
                                latency = llmLatency,
                                error = e,
                            ),
                    ),
                )
                throw e
            }

        agentConfiguration.eventListeners.dispatch(
            Event(
                payload =
                    EventPayload.AfterLlmCall(
                        modelId = agentConfiguration.languageModelParameters.llm.id,
                        stopReason = response.stopReason,
                        inputTokens = response.usage?.inputTokens ?: 0,
                        outputTokens = response.usage?.outputTokens ?: 0,
                        latency = llmLatency,
                        error = null,
                    ),
            ),
        )

        val rawContent = response.output?.asMessageOrNull()?.content ?: emptyList()

        // Filter to only content block types the framework understands.
        val supportedContent = rawContent.filter { Conversation.isSupportedContentBlock(it) }

        LOG.debug(
            "Bedrock response: raw content={} blocks, supported={} blocks, types={}",
            rawContent.size,
            supportedContent.size,
            rawContent.map { it.javaClass.simpleName },
        )

        if (supportedContent.isEmpty()) {
            val actualTypes =
                rawContent
                    .joinToString(", ") { it.javaClass.simpleName }
                    .ifEmpty { "none" }
            LOG.warn(
                "Bedrock response contained no supported content blocks. " +
                    "Expected: {}. Received: {}. " +
                    "Returning conversation unchanged to retry.",
                Conversation.supportedContentBlockTypeNames,
                actualTypes,
            )
            return conversation
        }

        return conversation.appendAssistantRoleContent(
            contentBlocks = supportedContent,
            additionalTokenUsage = response.usage ?: Conversation.TOKEN_USAGE_ZERO,
            stopReason = response.stopReason,
        )
    }

    /*
        Execute the tool calls requested by the assistant and append their results.

        Handles parallel tool calls: the model may return multiple ToolUse blocks in a
        single assistant message. Bedrock requires a ToolResult for every ToolUse ID in
        the subsequent user message, so all tools are invoked concurrently and awaited.
     */
    private suspend fun executeToolCalls(
        conversation: Conversation,
        toolUseBlocks: List<ContentBlock.ToolUse>,
        turnNumber: Int,
    ): Conversation {
        val toolResults =
            coroutineScope {
                toolUseBlocks
                    .map { toolUseBlock -> async { callSingleTool(toolUseBlock, turnNumber) } }
                    .awaitAll()
            }
        return conversation.appendUserRoleContents(
            contentBlocks = toolResults,
            additionalTokenUsage = Conversation.TOKEN_USAGE_ZERO,
            incrementThinkingModeCounter = 0,
        )
    }

    /*
        Invoke a single tool, dispatching Before/After tool-call events and propagating
        any failure to the caller.
     */
    private suspend fun callSingleTool(
        toolUseBlock: ContentBlock.ToolUse,
        turnNumber: Int,
    ): ContentBlock {
        val toolName = toolUseBlock.value.name
        val toolInput = toolUseBlock.value.input ?: Unit

        agentConfiguration.eventListeners.dispatch(
            Event(
                payload =
                    EventPayload.BeforeToolCall(
                        toolName = toolName,
                        toolInput = toolInput,
                        turnNumber = turnNumber,
                    ),
            ),
        )

        val (toolResult, toolLatency) =
            measureTimedValue {
                runCatching { agentConfiguration.toolsProvider.callTool(toolUseBlock) }
            }

        val result =
            toolResult.getOrElse { e ->
                agentConfiguration.eventListeners.dispatch(
                    Event(
                        payload =
                            EventPayload.AfterToolCall(
                                toolName = toolName,
                                toolInput = toolInput,
                                toolResult = Unit,
                                latency = toolLatency,
                                error = e,
                            ),
                    ),
                )
                throw e
            }

        agentConfiguration.eventListeners.dispatch(
            Event(
                payload =
                    EventPayload.AfterToolCall(
                        toolName = toolName,
                        toolInput = toolInput,
                        toolResult = result,
                        latency = toolLatency,
                        error = null,
                    ),
            ),
        )

        return result
    }

    /*
        Apply the configured ContextMemoryManagers to the next conversation, produce the
        next IndexedConversation, and dispatch a TurnCompleted event.
     */
    private suspend fun advanceTurn(
        indexed: IndexedConversation,
        nextConversation: Conversation,
    ): IndexedConversation {
        val managed =
            agentConfiguration.contextMemoryManagers.getContext(
                ContextMemoryManager.ContextMemoryManagerInput(
                    conversation = nextConversation,
                    llm = agentConfiguration.languageModelParameters.llm,
                    turnNumber = indexed.turnNumber + 1,
                ),
            )
        val nextIndexed =
            indexed.next(
                IndexedConversation(
                    turnNumber = indexed.turnNumber + 1,
                    conversation = managed,
                ),
            )

        agentConfiguration.eventListeners.dispatch(
            Event(
                payload =
                    EventPayload.TurnCompleted(
                        agentId = agentConfiguration.agentId,
                        turnNumber = nextIndexed.turnNumber,
                        conversation = managed,
                    ),
            ),
        )

        return nextIndexed
    }

    /*
        Extract the JSON output enclosed in the <JSON>...</JSON> tags from the conversation
        text, validate it against the output schema, and decode it into the output type.
        Throws if no valid JSON is found or if schema validation fails.
     */
    @OptIn(InternalSerializationApi::class)
    private fun extractAndValidateOutput(
        conversation: Conversation,
        outputSchemaJson: String,
    ): O {
        val conversationText =
            conversation
                .converseMessages()
                .flatMap { it.content }
                .filterIsInstance<ContentBlock.Text>()
                .joinToString("\n") { contentBlock -> contentBlock.value }

        val extractedJson =
            REGEX_JSON_EXTRACT
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .findAll(conversationText)
                .lastOrNull { Try { JsonString(it.groupValues[1]) }.isSuccess }
                ?.groupValues
                ?.get(1)
                ?: throw IllegalStateException(
                    "No valid JSON output found in conversation. " +
                        "Expected <JSON>...</JSON> tags in model response. " +
                        "Conversation text length: ${conversationText.length} chars.",
                )

        val jsonString = JsonString(value = extractedJson)

        val errors =
            JsonSchemaUtil
                .validateJsonWithSchema(
                    json = jsonString,
                    schema = JsonString(value = outputSchemaJson),
                ).map { it.error }
        check(errors.isEmpty()) { errors.joinToString { ", " } }

        return JsonSchemaUtil.json.decodeFromString(
            getOutputKClass().serializer(),
            jsonString.value,
        )
    }

    /*
        Persist the completed conversation to storage via the configured ContextWriters.
     */
    private fun persistConversation(
        input: I,
        conversation: Conversation,
    ) {
        agentConfiguration.contextWriters.value
            .forEach { writer -> writer.write(input, conversation) }
    }
}
