package pro.sketchware.ai;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import chat.giga.model.completion.ChatFunction;
import chat.giga.model.completion.ChatFunctionCallEnum;
import chat.giga.model.completion.ChatFunctionParameters;
import chat.giga.model.completion.ChatFunctionParametersProperty;
import chat.giga.model.completion.ChatMessage;
import chat.giga.model.completion.ChatMessageRole;
import chat.giga.model.completion.ChoiceMessage;
import chat.giga.model.completion.ChoiceMessageFunctionCall;
import chat.giga.model.completion.CompletionRequest;
import chat.giga.model.completion.CompletionResponse;

public final class GigaChatAgent implements AutoCloseable {
    private static final int MAX_TOOL_ROUNDS = 5;
    private static final String MODEL = "GigaChat-2";

    private final GigaChatApiClient apiClient;
    private final GigaChatProjectTools projectTools;
    private final String projectName;
    private final Gson gson = new Gson();
    private boolean buildRequested;

    public GigaChatAgent(Context context, String projectId, String credentials, String scope) throws IOException {
        a.a.a.yq project = new a.a.a.yq(context, projectId);
        projectName = project.projectName;
        projectTools = new GigaChatProjectTools(context, projectId);
        apiClient = new GigaChatApiClient(credentials, scope);
    }

    public List<ChatMessage> newConversation() {
        return new java.util.ArrayList<>(List.of(ChatMessage.builder()
                .role(ChatMessageRole.SYSTEM)
                .content("You are GigaCode, a coding agent inside Sketchware Pro. "
                + "The active project is '" + projectName + "'. Use project tools to inspect code before proposing changes. "
                + "Only modify files when the user explicitly asks for a change. Never request or expose credentials. "
                + "You may only read project text files and write Java source, layout XML, or the project AndroidManifest.xml. "
                + "You may call request_project_build when the user asks to build after changes. "
                + "The user is informed that project files sent in chat are processed by GigaChat.")
                .build()));
    }

    public String reply(List<ChatMessage> messages, String userText) throws IOException {
        buildRequested = false;
        messages.add(ChatMessage.builder().role(ChatMessageRole.USER).content(userText).build());

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            CompletionRequest request = CompletionRequest.builder()
                    .model(MODEL)
                    .messages(messages)
                    .functions(functionDefinitions())
                    .functionCall(ChatFunctionCallEnum.AUTO)
                    .temperature(0.2f)
                    .maxTokens(2048)
                    .build();
            CompletionResponse response = apiClient.complete(request);
            if (response.choices() == null || response.choices().isEmpty()) {
                throw new IOException("GigaChat returned no completion choices.");
            }

            ChoiceMessage assistantMessage = response.choices().get(0).message();
            if (assistantMessage == null || assistantMessage.functionCall() == null) {
                String answer = assistantMessage == null ? "" : assistantMessage.content();
                messages.add(ChatMessage.builder()
                        .role(ChatMessageRole.ASSISTANT)
                        .content(answer)
                        .build());
                return answer == null ? "" : answer;
            }

            ChoiceMessageFunctionCall functionCall = assistantMessage.functionCall();
            messages.add(ChatMessage.builder()
                    .role(ChatMessageRole.ASSISTANT)
                    .content(assistantMessage.content())
                    .functionCall(functionCall)
                    .functionsStateId(assistantMessage.functionsStateId())
                    .build());
            String functionName = functionCall.name();
            JsonObject arguments = functionCall.arguments() == null
                    ? new JsonObject()
                    : gson.toJsonTree(functionCall.arguments()).getAsJsonObject();
            String result = executeTool(functionName, arguments);

            messages.add(ChatMessage.builder()
                    .role(ChatMessageRole.FUNCTION)
                    .name(functionName)
                    .content(gson.toJson(Map.of("result", result)))
                    .build());
            if (buildRequested) return "Returning to the editor to start the project build.";
        }
        throw new IOException("Agent reached the maximum number of tool calls for one reply.");
    }

    public boolean isBuildRequested() {
        return buildRequested;
    }

    @Override
    public void close() {
        apiClient.close();
    }

    private String executeTool(String name, JsonObject arguments) {
        try {
            return switch (name) {
                case "list_project_files" -> projectTools.listProjectFiles();
                case "read_project_file" -> projectTools.readProjectFile(stringArgument(arguments, "path"));
                case "write_project_file" -> projectTools.writeProjectFile(
                        stringArgument(arguments, "path"), stringArgument(arguments, "content"));
                case "request_project_build" -> {
                    buildRequested = true;
                    yield "Build requested.";
                }
                default -> "Unknown tool: " + name;
            };
        } catch (IOException | RuntimeException e) {
            return "Tool failed: " + e.getMessage();
        }
    }

    private static List<ChatFunction> functionDefinitions() {
        ChatFunctionParametersProperty stringParameter = ChatFunctionParametersProperty.builder()
                .type("string")
                .build();
        ChatFunctionParameters emptyParameters = ChatFunctionParameters.builder()
                .type("object")
                .build();
        ChatFunctionParameters pathParameter = ChatFunctionParameters.builder()
                .type("object")
                .property("path", stringParameter)
                .required(List.of("path"))
                .build();
        ChatFunctionParameters writeParameters = ChatFunctionParameters.builder()
                .type("object")
                .property("path", stringParameter)
                .property("content", stringParameter)
                .required(List.of("path", "content"))
                .build();

        return List.of(
                ChatFunction.builder()
                        .name("list_project_files")
                        .description("List up to 120 text source and configuration files in the active project.")
                        .parameters(emptyParameters)
                        .build(),
                ChatFunction.builder()
                        .name("read_project_file")
                        .description("Read a text source file using a path relative to the project root.")
                        .parameters(pathParameter)
                        .build(),
                ChatFunction.builder()
                        .name("request_project_build")
                        .description("Return to the Sketchware editor and run its normal build for the active project.")
                        .parameters(emptyParameters)
                        .build(),
                ChatFunction.builder()
                        .name("write_project_file")
                        .description("Write a complete file after the user explicitly requested a code change. Writes are limited to project Java, layout XML, or AndroidManifest.xml.")
                        .parameters(writeParameters)
                        .build());
    }

    private static String stringArgument(JsonObject arguments, String name) {
        return arguments.has(name) && !arguments.get(name).isJsonNull()
                ? arguments.get(name).getAsString()
                : "";
    }
}