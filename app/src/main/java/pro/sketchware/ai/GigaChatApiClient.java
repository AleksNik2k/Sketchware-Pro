package pro.sketchware.ai;

import chat.giga.client.GigaChatClient;
import chat.giga.client.auth.AuthClientBuilder;
import chat.giga.http.client.HttpClient;
import chat.giga.model.Scope;
import chat.giga.model.completion.CompletionRequest;
import chat.giga.model.completion.CompletionResponse;

public final class GigaChatApiClient implements AutoCloseable {
    private static final String API_URL = "https://api.giga.chat/v1";

    private final GigaChatClient client;
    private final GigaChatOkHttpTransport httpClient;

    public GigaChatApiClient(String credentials, String scope) {
        httpClient = new GigaChatOkHttpTransport();
        AuthClientBuilder.OAuthBuilder oauth = AuthClientBuilder.OAuthBuilder.builder()
                .authKey(credentials)
                .scope(Scope.valueOf(scope))
                .httpClient(httpClient)
                .build();
        client = GigaChatClient.builder()
                .authClient(AuthClientBuilder.builder().withOAuth(oauth).build())
                .apiHttpClient(httpClient)
                .apiUrl(API_URL)
                .build();
    }

    public CompletionResponse complete(CompletionRequest request) {
        return client.completions(request);
    }

    @Override
    public void close() {
        httpClient.close();
    }
}