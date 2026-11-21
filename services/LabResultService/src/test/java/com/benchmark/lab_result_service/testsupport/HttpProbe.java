package com.benchmark.lab_result_service.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Plain JDK HTTP calls against the running application's server port or management port (P6 ITs). */
public final class HttpProbe implements AutoCloseable {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(port, path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** {@code POST /graphql} with a JSON body, as k6 sends it. */
    public HttpResponse<String> graphQl(int port, String body) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(port, "/graphql")).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(int port, String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Override
    public void close() {
        client.close();
    }
}
