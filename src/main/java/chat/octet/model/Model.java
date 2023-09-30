package chat.octet.model;


import chat.octet.model.beans.*;
import chat.octet.model.exceptions.ModelException;
import chat.octet.model.parameters.GenerateParameter;
import chat.octet.model.parameters.ModelParameter;
import chat.octet.model.utils.PromptBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import java.io.File;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * LLama model, which provides functions for generating and chatting conversations.
 *
 * @author <a href="https://github.com/eoctet">William</a>
 */
@Slf4j
public class Model implements AutoCloseable {
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int DEFAULT_KEEP_SIZE = 5;
    private final static String LINE_CHAR = "\n";

    @Getter
    private final ModelParameter modelParams;
    @Getter
    private final String modelName;
    private final int lastTokensSize;
    private final int keepSize;
    private final Map<Integer, List<ChatMessage>> conversation;
    private int conversationTimes;
    private Generator chatGenerator;

    public Model(String modelPath) {
        this(ModelParameter.builder().modelPath(modelPath).build());
    }

    public Model(ModelParameter modelParams) {
        Preconditions.checkNotNull(modelParams, "Model parameters cannot be null");
        Preconditions.checkNotNull(modelParams.getModelPath(), "Model file path cannot be null");

        if (!Files.exists(new File(modelParams.getModelPath()).toPath())) {
            throw new ModelException("Model file is not exists, please check the file path");
        }

        this.modelParams = modelParams;
        this.modelName = modelParams.getModelName();
        this.conversation = Maps.newConcurrentMap();
        this.conversationTimes = 1;
        //setting model parameters
        LlamaModelParams llamaModelParams = getLlamaModelParameters(modelParams);
        LlamaService.loadLlamaModelFromFile(modelParams.getModelPath(), llamaModelParams);

        //apple lora from file
        if (StringUtils.isNotBlank(modelParams.getLoraPath())) {
            if (!Files.exists(new File(modelParams.getLoraPath()).toPath())) {
                throw new ModelException("Lora model file is not exists, please check the file path");
            }
            int status = LlamaService.loadLoraModelFromFile(modelParams.getLoraPath(), modelParams.getLoraScale(), modelParams.getLoraBase(), modelParams.getThreads());
            if (status != 0) {
                throw new ModelException(String.format("Failed to apply LoRA from lora path: %s to base path: %s", modelParams.getLoraPath(), modelParams.getLoraBase()));
            }
        }
        //setting context parameters
        LlamaContextParams llamaContextParams = getLlamaContextParameters(modelParams);
        LlamaService.createNewContextWithModel(llamaContextParams);
        this.keepSize = (modelParams.getKeep() > 0 && modelParams.getKeep() <= DEFAULT_KEEP_SIZE) ? modelParams.getKeep() : DEFAULT_KEEP_SIZE;
        this.lastTokensSize = modelParams.getLastNTokensSize() < 0 ? LlamaService.getContextSize() : modelParams.getLastNTokensSize();

        if (modelParams.isVerbose()) {
            log.info(MessageFormat.format("system info: {0}", LlamaService.getSystemInfo()));
        }
        log.info(MessageFormat.format("model parameters: {0}", modelParams));
    }

    private LlamaModelParams getLlamaModelParameters(ModelParameter modelParams) {
        LlamaModelParams llamaModelParams = LlamaService.getLlamaModelDefaultParams();
        llamaModelParams.gpuLayers = modelParams.getGpuLayers();
        llamaModelParams.vocabOnly = modelParams.isVocabOnly();
        boolean mmap = (StringUtils.isBlank(modelParams.getLoraPath()) && modelParams.isMmap());
        if (mmap && LlamaService.isMmapSupported()) {
            llamaModelParams.mmap = true;
        }
        boolean mlock = modelParams.isMlock();
        if (mlock && LlamaService.isMlockSupported()) {
            llamaModelParams.mlock = true;
        }
        if (modelParams.getMainGpu() != null) {
            llamaModelParams.mainGpu = modelParams.getMainGpu();
        }
        if (modelParams.getTensorSplit() != null) {
            llamaModelParams.tensorSplit = modelParams.getTensorSplit();
        }
        return llamaModelParams;
    }

    private LlamaContextParams getLlamaContextParameters(ModelParameter modelParams) {
        LlamaContextParams llamaContextParams = LlamaService.getLlamaContextDefaultParams();
        llamaContextParams.seed = modelParams.getSeed();
        llamaContextParams.ctx = modelParams.getContextSize();
        llamaContextParams.batch = modelParams.getBatchSize();
        llamaContextParams.threads = modelParams.getThreads();
        llamaContextParams.threadsBatch = modelParams.getThreadsBatch();
        llamaContextParams.ropeFreqBase = modelParams.getRopeFreqBase();
        llamaContextParams.ropeFreqScale = modelParams.getRopeFreqScale();
        llamaContextParams.mulMatQ = modelParams.isMulMatQ();
        llamaContextParams.f16KV = modelParams.isF16KV();
        llamaContextParams.logitsAll = modelParams.isLogitsAll();
        llamaContextParams.embedding = modelParams.isEmbedding();
        return llamaContextParams;
    }

    private String getPromptByConversation(GenerateParameter generateParams) {
        //get the first system prompt in the conversation
        StringBuilder systemPrompts = new StringBuilder();
        String firstSystemPrompt = StringUtils.EMPTY;
        ChatMessage firstMessage = conversation.get(1).get(0);
        if (ChatMessage.ChatRole.SYSTEM == firstMessage.getRole()) {
            firstSystemPrompt = firstMessage.getContent();
            systemPrompts.append(firstSystemPrompt).append(LINE_CHAR);
        }
        //injecting historical dialogue
        int startIndex = Math.max(conversation.size() - keepSize, 1);
        List<ChatMessage> historyMessages = Lists.newArrayList();
        conversation.forEach((index, messages) -> {
            if (index >= startIndex) {
                historyMessages.addAll(messages);
            }
        });
        ChatMessage question = historyMessages.remove(historyMessages.size() - 1);

        StringBuilder history = new StringBuilder();
        history.append(MessageFormat.format("The following is a sequentially numbered historical dialogue, where {0} is the user and {1} is you:", generateParams.getUser(), generateParams.getAssistant())).append(LINE_CHAR);
        int order = 1;
        for (ChatMessage msg : historyMessages) {
            if (ChatMessage.ChatRole.USER == msg.getRole()) {
                history.append("(").append(order).append(") ").append(generateParams.getUser()).append(": ").append(StringUtils.replaceChars(msg.getContent(), LINE_CHAR, "")).append(LINE_CHAR);
            } else if (ChatMessage.ChatRole.ASSISTANT == msg.getRole()) {
                history.append("(").append(order).append(") ").append(generateParams.getAssistant()).append(": ").append(StringUtils.replaceChars(msg.getContent(), LINE_CHAR, "")).append(LINE_CHAR);
                ++order;
            } else if (ChatMessage.ChatRole.SYSTEM == msg.getRole()) {
                if (!msg.getContent().equalsIgnoreCase(firstSystemPrompt)) {
                    systemPrompts.append(msg.getContent()).append(LINE_CHAR);
                }
            }
        }
        String now = "Current time: " + DATETIME_FORMATTER.format(ZonedDateTime.now());
        //format final prompts
        String finalSystemPrompts = systemPrompts.append(LINE_CHAR).append(history).append(LINE_CHAR).append(now).toString();
        return PromptBuilder.toPrompt(finalSystemPrompts, question.getContent());
    }

    /**
     * Generate complete text.
     *
     * @param text Input text or prompt.
     * @return CompletionResult, generated text and completion reason.
     * @see CompletionResult
     */
    public CompletionResult completions(String text) {
        return completions(GenerateParameter.builder().build(), text);
    }

    /**
     * Generate complete text.
     *
     * @param generateParams Specify a generation parameter.
     * @param text           Input text or prompt.
     * @return CompletionResult, generated text and completion reason.
     * @see CompletionResult
     */
    public CompletionResult completions(GenerateParameter generateParams, String text) {
        Iterable<Token> tokenIterable = generate(generateParams, text);
        tokenIterable.forEach(e -> {
        });
        Generator generator = (Generator) tokenIterable.iterator();
        return CompletionResult.builder().content(generator.getFullGenerateText()).finishReason(generator.getFinishReason()).build();
    }

    /**
     * Generate text in stream format.
     *
     * @param text Input text or prompt.
     * @return Iterable, Generation iterator.
     * @see Generator
     */
    public Iterable<Token> generate(String text) {
        return generate(GenerateParameter.builder().build(), text);
    }

    /**
     * Generate text in stream format.
     *
     * @param generateParams Specify a generation parameter.
     * @param text           Input text or prompt.
     * @return Iterable, Generation iterator.
     * @see Generator
     */
    public Iterable<Token> generate(GenerateParameter generateParams, String text) {
        Preconditions.checkNotNull(generateParams, "Generate parameter cannot be null");
        Preconditions.checkNotNull(text, "Text cannot be null");

        return new Iterable<Token>() {

            private Generator generator = null;

            @Nonnull
            @Override
            public Iterator<Token> iterator() {
                if (generator == null) {
                    generator = new Generator(generateParams, text, lastTokensSize);
                }
                return generator;
            }
        };
    }

    /**
     * Start a conversation and chat.
     *
     * @param question User question.
     * @return CompletionResult, generated text and completion reason.
     * @see CompletionResult
     */
    public CompletionResult chatCompletions(String question) {
        return chatCompletions(GenerateParameter.builder().build(), null, question);
    }

    /**
     * Start a conversation and chat.
     *
     * @param generateParams Specify a generation parameter.
     * @param question       User question.
     * @return CompletionResult, generated text and completion reason.
     * @see CompletionResult
     */
    public CompletionResult chatCompletions(GenerateParameter generateParams, String question) {
        return chatCompletions(generateParams, null, question);
    }

    /**
     * Start a conversation and chat.
     *
     * @param generateParams Specify a generation parameter.
     * @param system         System prompt.
     * @param question       User question.
     * @return CompletionResult, generated text and completion reason.
     * @see CompletionResult
     */
    public CompletionResult chatCompletions(GenerateParameter generateParams, String system, String question) {
        Iterable<Token> tokenIterable = chat(generateParams, system, question);
        tokenIterable.forEach(e -> {
        });
        Generator generator = (Generator) tokenIterable.iterator();
        return CompletionResult.builder().content(generator.getFullGenerateText()).finishReason(generator.getFinishReason()).build();
    }

    /**
     * Start a conversation and chat in streaming format.
     *
     * @param question User question.
     * @return Iterable, Generation iterator.
     * @see Generator
     */
    public Iterable<Token> chat(String question) {
        return chat(GenerateParameter.builder().build(), null, question);
    }

    /**
     * Start a conversation and chat in streaming format.
     *
     * @param system   System prompt.
     * @param question User question.
     * @return Iterable, Generation iterator.
     * @see Generator
     */
    public Iterable<Token> chat(String system, String question) {
        return chat(GenerateParameter.builder().build(), system, question);
    }

    /**
     * Start a conversation and chat in streaming format.
     *
     * @param generateParams Specify a generation parameter.
     * @param question       User question.
     * @return Iterable, Generation iterator.
     * @see Generator
     */
    public Iterable<Token> chat(GenerateParameter generateParams, String question) {
        return chat(generateParams, null, question);
    }

    /**
     * Start a conversation and chat in streaming format.
     *
     * @param generateParams Specify a generation parameter.
     * @param system         System prompt.
     * @param question       User question.
     * @return Iterable, Generation iterator.
     * @see Generator
     */
    public Iterable<Token> chat(GenerateParameter generateParams, String system, String question) {
        Preconditions.checkNotNull(generateParams, "Generate parameter cannot be null");
        Preconditions.checkNotNull(question, "question cannot be null");

        //append last response
        if (chatGenerator != null) {
            conversation.get(conversationTimes).add(ChatMessage.toAssistant(chatGenerator.getFullGenerateText()));
            ++conversationTimes;
            chatGenerator = null;
        }
        //create new conversation messages
        List<ChatMessage> messages = Lists.newArrayList();
        if (StringUtils.isNotBlank(system)) {
            messages.add(ChatMessage.toSystem(system));
        }
        messages.add(ChatMessage.toUser(question));
        conversation.put(conversationTimes, messages);
        //
        String prompt = getPromptByConversation(generateParams);
        //System.err.println(prompt);
        Iterable<Token> tokenIterable = generate(generateParams, prompt);
        chatGenerator = (Generator) tokenIterable.iterator();
        return tokenIterable;
    }

    /**
     * Print generation metrics.
     * <p>Require verbose parameter to be true.</p>
     */
    public void metrics() {
        if (modelParams.isVerbose()) {
            log.info("Metrics: " + LlamaService.getSamplingMetrics(true).toString());
        }
    }

    /**
     * Remove conversation memory.
     */
    public void removeConversationMemory() {
        if (conversation != null && !conversation.isEmpty()) {
            conversation.clear();
        }
    }

    /**
     * Close the model and release resources.
     */
    @Override
    public void close() {
        LlamaService.release();
    }

    @Override
    public String toString() {
        return "LlamaModel (" +
                "modelParams=" + modelParams +
                ')';
    }

}
