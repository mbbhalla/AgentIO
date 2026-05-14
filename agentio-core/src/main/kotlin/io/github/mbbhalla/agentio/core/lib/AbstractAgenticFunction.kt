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
import io.github.mbbhalla.agentio.core.model.event.Event
import io.github.mbbhalla.agentio.core.model.event.EventPayload
import io.github.mbbhalla.agentio.core.model.AgentConfiguration
import io.github.mbbhalla.agentio.core.model.Conversation
import io.github.mbbhalla.agentio.core.model.IndexedConversation
import io.github.mbbhalla.agentio.core.model.ThinkingMode
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
import kotlinx.coroutines.flow.flow
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

    suspend fun invoke(
        input: I,
    ): O
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
    final override suspend fun invoke(
        input: I,
    ): Try<AgentOutput<O>> = withContext(Dispatchers.IO) {
        val eventListener = agentConfiguration.eventListener

        eventListener?.onEvent(
            Event(
                payload = EventPayload.AgentInvocationStart(
                    agentId = agentConfiguration.agentId,
                    instructionId = input.instructionId(),
                    instruction = input.instruction(),
                ),
            ),
        )

        val (result, duration) = measureTimedValue {
            try {
                val output = coreLogic(input)
                LOG.debug("Successfully computed result for ${input.instructionId()}")
                success(output)
            } catch (e: Exception) {
                LOG.error("Failure in computing result for ${input.instructionId()}")
                failure(e)
            }
        }

        eventListener?.onEvent(
            Event(
                payload = EventPayload.AgentInvocationEnd(
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
    ): Flow<T> = flow {
        var current: T = seed
        do {
            emit(current)
            val next = nextFunction(current) ?: break
            current = next
        } while (true)
    }

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
        val additionalModelRequestFields = buildJsonObject {
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
                        this.toolChoice = ToolChoice.Auto(
                            value = AutoToolChoice.invoke { },
                        )
                        this.tools = tools
                    }
                }

                this.system = (input.systemInstruction() ?: agentConfiguration.systemPrompt) ?.let {
                    listOf(
                        SystemContentBlock.Text(
                            value = it,
                        ),
                    )
                }
            },
        )
    }

    @OptIn(InternalSerializationApi::class)
    @Suppress("MaxLineLength", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun coreLogic(
        input: I,
    ): AgentOutput<O> {
        /*
            Agent loop LLM call
         */
        val inputSchemaJson = JsonSchemaUtil.json.encodeToString(
            JsonObject.serializer(),
            JsonSchemaUtil.generateJsonSchema(getInputKClass()),
        )
        val outputSchemaJson = JsonSchemaUtil.json.encodeToString(
            JsonObject.serializer(),
            JsonSchemaUtil.generateJsonSchema(getOutputKClass()),
        )

        val tools = agentConfiguration.toolsProvider.listTools()

        LOG.debug("Got input schema: {}", inputSchemaJson)
        LOG.debug("Got output schema: {}", outputSchemaJson)
        LOG.debug("Got tools: {}", tools.map { it.value.name })

        /*
            Generate a Flow of Conversations. The last message in the previous
            Conversation is processed. It could be a Call to Bedrock, or a Tool call
            or something else. The processing produces a new Message which is appended
            to this Conversation

            Conversation object in this flow is accumulative
            i.e. subsequent objects contain previous objects data plus more

            Conversation is initialized with an initial Prompt

         */
        val conversationFlow = generateFlow(
            IndexedConversation(
                turnNumber = 0,
                conversation = Conversation.initialize(
                    texts = listOf(
                        """
                        You are an AI Agent ${
                            agentConfiguration.problemDomain?.let { problemDomain ->
                                "expert in $problemDomain"
                            }
                        }, which needs to execute the following instruction:
                        
                        "${input.instruction()}" 
                        
                        RULES TO FOLLOW:
                        
                        1. COMPUTE OUTPUT DATA JSON OBJECT FROM INPUT DATA JSON OBJECT.                                                

                        2. USE ALL THE TOOLS PROVIDED TO STEP BY STEP REASON ABOUT THE COMPUTATION.                        

                        3. Output MUST BE "JSON format" adhering to Output JSON Schema:
                        "$outputSchemaJson"

                        INPUT DETAILS:
                        
                        Input Data is:
                        ${JsonSchemaUtil.json.encodeToString(getInputKClass().serializer(), input)}
                        
                        Input Schema is:
                        $inputSchemaJson
                        
                        OUTPUT FORMAT:
                        
                        Enclose output JSON in the following strings:
                        ${JSON_TAG_START}<Output JSON here>$JSON_TAG_END

                    """.trimIndent(),
                    ) +
                        // Fetch context from storage
                        agentConfiguration.contextProviders
                            .value.map { contextProvider ->
                                contextProvider.context(input)
                            },
                ),
            ),
        ) { indexed ->
            val conversation = indexed.conversation

            LOG.debug(
                "###############################################\n{}\n###############################################",
                conversation.lastMessage().message.content,
            )

            delay(duration = agentConfiguration.delayBetweenTurns)

            val role = conversation.lastMessage().message.role
            val contentBlocks = conversation.lastMessage().message.content
            val lastContentBlock = contentBlocks.last()
            val toolUseBlocks = contentBlocks.filterIsInstance<ContentBlock.ToolUse>()
            val nextConversation = when {
                conversation.stopReason != null &&
                    (conversation.stopReason is StopReason.EndTurn ||
                        conversation.stopReason is StopReason.StopSequence ||
                        conversation.stopReason is StopReason.ContentFiltered) -> {
                    if (conversation.thinkingModeCounter < agentConfiguration.thinkingMode.maxIterations) {
                        conversation.appendUserRoleContent(
                            contentBlock = ContentBlock.Text(
                                value = ThinkingMode.THINKING_MODE_PROMPT,
                            ),
                            additionalTokenUsage = Conversation.TOKEN_USAGE_ZERO,
                            incrementThinkingModeCounter = 1,
                        )
                    } else {
                        null
                    }
                }

                role is ConversationRole.User && (lastContentBlock is ContentBlock.Text || lastContentBlock is ContentBlock.ToolResult) -> {
                    agentConfiguration.eventListener?.onEvent(
                        Event(
                            payload = EventPayload.BeforeLlmCall(
                                modelId = agentConfiguration.languageModelParameters.llm.id,
                                messageCount = conversation.messages.size,
                                turnNumber = indexed.turnNumber,
                            ),
                        ),
                    )

                    val (llmResult, llmLatency) = measureTimedValue {
                        runCatching {
                            callBedrock(
                                input = input,
                                agentConfiguration = agentConfiguration,
                                conversation = conversation,
                                tools = tools,
                            )
                        }
                    }

                    val response = llmResult.getOrElse { e ->
                        agentConfiguration.eventListener?.onEvent(
                            Event(
                                payload = EventPayload.AfterLlmCall(
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

                    agentConfiguration.eventListener?.onEvent(
                        Event(
                            payload = EventPayload.AfterLlmCall(
                                modelId = agentConfiguration.languageModelParameters.llm.id,
                                stopReason = response.stopReason,
                                inputTokens = response.usage?.inputTokens ?: 0,
                                outputTokens = response.usage?.outputTokens ?: 0,
                                latency = llmLatency,
                                error = null,
                            ),
                        ),
                    )

                    val responseMessage = response.output?.asMessageOrNull()
                    val rawContent = responseMessage?.content ?: emptyList()

                    // Filter to only content block types the framework understands.
                    val supportedContent = rawContent.filter { Conversation.isSupportedContentBlock(it) }

                    LOG.debug(
                        "Bedrock response: raw content={} blocks, supported={} blocks, types={}",
                        rawContent.size,
                        supportedContent.size,
                        rawContent.map { it.javaClass.simpleName },
                    )

                    // If no supported content blocks remain after filtering, the model
                    // returned a response the framework can't process (e.g., models with
                    // adaptive thinking returning only unsupported block types).
                    // Return the conversation unchanged — the loop will see the same User
                    // message and call Bedrock again on the next iteration. The maxTurnLimit
                    // prevents infinite retries.
                    if (supportedContent.isEmpty()) {
                        val expectedTypes = Conversation.supportedContentBlockTypeNames
                        val actualTypes = rawContent.joinToString(", ") { it.javaClass.simpleName }
                            .ifEmpty { "none" }
                        LOG.warn(
                            "Bedrock response contained no supported content blocks. " +
                                "Expected: {}. Received: {}. " +
                                "Returning conversation unchanged to retry.",
                            expectedTypes,
                            actualTypes,
                        )
                        conversation
                    } else {
                        conversation.appendAssistantRoleContent(
                            contentBlocks = supportedContent,
                            additionalTokenUsage = response.usage ?: Conversation.TOKEN_USAGE_ZERO,
                            stopReason = response.stopReason,
                        )
                    }
                }
                role is ConversationRole.Assistant && lastContentBlock is ContentBlock.Text && toolUseBlocks.isEmpty() -> {
                    LOG.debug("Got Assistant/ContentBlock.Text with Stop reason: {}", conversation.stopReason)
                    conversation
                }
                role is ConversationRole.Assistant && toolUseBlocks.isNotEmpty() -> {
                    /*
                        Handle parallel tool calls: the model may return multiple ToolUse
                        blocks in a single assistant message. Bedrock requires a ToolResult
                        for every ToolUse ID in the subsequent user message.
                     */
                    val toolResults = coroutineScope {
                        toolUseBlocks.map { toolUseBlock ->
                            async {
                                val toolName = toolUseBlock.value.name
                                val toolInput = toolUseBlock.value.input

                                agentConfiguration.eventListener?.onEvent(
                                    Event(
                                        payload = EventPayload.BeforeToolCall(
                                            toolName = toolName,
                                            toolInput = toolInput ?: Unit,
                                            turnNumber = indexed.turnNumber,
                                        ),
                                    ),
                                )

                                val (toolResult, toolLatency) = measureTimedValue {
                                    runCatching {
                                        agentConfiguration.toolsProvider.callTool(toolUseBlock)
                                    }
                                }

                                val result = toolResult.getOrElse { e ->
                                    agentConfiguration.eventListener?.onEvent(
                                        Event(
                                            payload = EventPayload.AfterToolCall(
                                                toolName = toolName,
                                                toolInput = toolInput ?: Unit,
                                                toolResult = Unit,
                                                latency = toolLatency,
                                                error = e,
                                            ),
                                        ),
                                    )
                                    throw e
                                }

                                agentConfiguration.eventListener?.onEvent(
                                    Event(
                                        payload = EventPayload.AfterToolCall(
                                            toolName = toolName,
                                            toolInput = toolInput ?: Unit,
                                            toolResult = result,
                                            latency = toolLatency,
                                            error = null,
                                        ),
                                    ),
                                )

                                result
                            }
                        }.awaitAll()
                    }
                    conversation.appendUserRoleContents(
                        contentBlocks = toolResults,
                        additionalTokenUsage = Conversation.TOKEN_USAGE_ZERO,
                        incrementThinkingModeCounter = 0,
                    )
                }
                else -> {
                    LOG.error("Invalid combination: $role, ${lastContentBlock.javaClass.simpleName}")
                    null
                }
            }
            nextConversation?.let {
                val managed = agentConfiguration.contextMemoryManagers.getContext(
                    ContextMemoryManager.ContextMemoryManagerInput(
                        conversation = it,
                        llm = agentConfiguration.languageModelParameters.llm,
                        turnNumber = indexed.turnNumber + 1,
                    ),
                )
                indexed.next(
                    IndexedConversation(
                        turnNumber = indexed.turnNumber + 1,
                        conversation = managed,
                    ),
                )
            }
        } // end Flow

        val outputConversation = conversationFlow.take(agentConfiguration.maxTurnLimit).last().conversation

        val outputConversationTextContent = outputConversation.converseMessages().flatMap { it.content }
            .filterIsInstance<ContentBlock.Text>().joinToString("\n") { contentBlock -> contentBlock.value }

        val extractedJson = REGEX_JSON_EXTRACT.toRegex(RegexOption.DOT_MATCHES_ALL)
            .findAll(outputConversationTextContent)
            .lastOrNull {
                Try { JsonString(it.groupValues[1]) }.isSuccess
            }
            ?.groupValues?.get(1)
            ?: throw IllegalStateException(
                "No valid JSON output found in conversation. " +
                    "Expected <JSON>...</JSON> tags in model response. " +
                    "Conversation text length: ${outputConversationTextContent.length} chars.",
            )

        val jsonString = JsonString(
            value = extractedJson,
        )

        val errors = JsonSchemaUtil.validateJsonWithSchema(
            json = jsonString,
            schema = JsonString(value = outputSchemaJson),
        ).map { it.error }
        check(errors.isEmpty()) { errors.joinToString { ", " } }

        // Persist conversation to storage
        agentConfiguration.contextWriters.value
            .forEach { writer ->
                writer.write(input, outputConversation)
            }

        return AgentOutput(
            instructionId = input.instructionId(),

            /*
                the last Conversation object holds the entire conversation
                including all messages and total tokens used in conversation
             */
            conversation = outputConversation,

            // derive output object from JSON
            output = JsonSchemaUtil.json.decodeFromString(
                getOutputKClass().serializer(),
                jsonString.value,
            ),
        )
    }
}
