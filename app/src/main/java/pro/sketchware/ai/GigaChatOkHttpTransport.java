package pro.sketchware.ai;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import chat.giga.http.client.HttpClient;
import chat.giga.http.client.HttpClientException;
import chat.giga.http.client.HttpRequest;
import chat.giga.http.client.HttpResponse;
import chat.giga.http.client.sse.SseEventListener;
import chat.giga.http.client.sse.SseEventParser;
import chat.giga.http.client.sse.SseListener;
import chat.giga.http.client.sse.SseParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class GigaChatOkHttpTransport implements HttpClient {
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    public HttpResponse execute(HttpRequest request) {
        try (Response response = client.newCall(toRequest(request)).execute()) {
            byte[] body = response.body() == null ? new byte[0] : response.body().bytes();
            if (!response.isSuccessful()) throw new HttpClientException(response.code(), body);
            return HttpResponse.builder()
                    .statusCode(response.code())
                    .headers(response.headers().toMultimap())
                    .body(body)
                    .build();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public void execute(HttpRequest request, SseListener listener) {
        try (Response response = client.newCall(toRequest(request)).execute()) {
            if (!response.isSuccessful()) {
                listener.onError(error(response));
                return;
            }
            if (response.body() == null) {
                listener.onError(new IOException("GigaChat returned an empty stream."));
                return;
            }
            new SseParser(listener).parse(response.body().byteStream());
        } catch (IOException e) {
            listener.onError(e);
        }
    }

    @Override
    public void execute(HttpRequest request, SseEventListener listener,
                        BiConsumer<Integer, Map<String, List<String>>> onSuccessfulStreamResponseHeaders) {
        try (Response response = client.newCall(toRequest(request)).execute()) {
            if (!response.isSuccessful()) {
                listener.onError(error(response));
                return;
            }
            if (onSuccessfulStreamResponseHeaders != null) {
                onSuccessfulStreamResponseHeaders.accept(response.code(), response.headers().toMultimap());
            }
            if (response.body() == null) {
                listener.onError(new IOException("GigaChat returned an empty stream."));
                return;
            }
            new SseEventParser(listener).parse(response.body().byteStream());
        } catch (IOException e) {
            listener.onError(e);
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> execute(request));
    }

    public void close() {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }

    private static Request toRequest(HttpRequest request) {
        Request.Builder builder = new Request.Builder().url(request.url());
        request.headers().forEach((name, values) -> {
            if (values != null) values.forEach(value -> builder.addHeader(name, value));
        });

        RequestBody body = null;
        if (request.body() != null) {
            body = RequestBody.create(request.body(), mediaType(request));
        }
        builder.method(request.method().name(), body);
        return builder.build();
    }

    private static okhttp3.MediaType mediaType(HttpRequest request) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("content-type"))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .map(okhttp3.MediaType::parse)
                .orElse(null);
    }

    private static HttpClientException error(Response response) throws IOException {
        byte[] body = response.body() == null ? new byte[0] : response.body().bytes();
        return new HttpClientException(response.code(), body);
    }
}