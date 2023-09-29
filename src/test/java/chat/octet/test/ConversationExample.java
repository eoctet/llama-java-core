package chat.octet.test;

import chat.octet.model.Model;
import chat.octet.model.parameters.GenerateParameter;
import chat.octet.model.parameters.ModelParameter;
import chat.octet.model.utils.PromptBuilder;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ConversationExample {
    private static final String MODEL_PATH = "/Users/william/development/llm/tools/zh-models/chinese-alpaca-2-7b/ggml-model-7b-q6_k.gguf";

    public static void main(String[] args) {
        ModelParameter modelParams = ModelParameter.builder()
                .modelPath(MODEL_PATH)
                .threads(8)
                .contextSize(4096)
                .verbose(true)
                .build();

        boolean chatMode = true;

        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             Model model = new Model(modelParams)) {

            GenerateParameter generateParams = GenerateParameter.builder().build();
            String system = "Answer the questions.";

            while (true) {
                System.out.print("\nQuestion: ");
                String input = bufferedReader.readLine();
                if (StringUtils.trimToEmpty(input).equalsIgnoreCase("exit")) {
                    break;
                }
                if (StringUtils.trimToEmpty(input).equalsIgnoreCase("CHAT_MODE")) {
                    chatMode = !chatMode;
                    System.err.println("\n=> CHAT_MODE: " + chatMode);
                    continue;
                }
                if (StringUtils.trimToEmpty(input).equalsIgnoreCase("FORGET_ME")) {
                    model.removeConversationMemory();
                    System.err.println("\n=> DONE!");
                    continue;
                }
                if (chatMode) {
                    model.chat(generateParams, system, input).forEach(e -> System.out.print(e.getText()));
                } else {
                    String text = PromptBuilder.toPrompt(system, input);
                    model.generate(generateParams, text).forEach(e -> System.out.print(e.getText()));
                }
                System.out.print("\n");
                model.metrics();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e);
            System.exit(1);
        }
    }
}