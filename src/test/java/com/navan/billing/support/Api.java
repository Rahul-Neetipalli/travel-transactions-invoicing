package com.navan.billing.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Thin real-HTTP client for the tests. Parses numbers as BigDecimal and keeps their scale. */
public final class Api {

    public record Resp(int status, JsonNode body, String raw, String contentType, String location, String retryAfter) {
        public String id() {
            return body.path("id").asText();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    public Api(String base) {
        this.base = base;
    }

    public Resp post(String path, Object body) {
        return send("POST", path, body);
    }

    public Resp patch(String path, Object body) {
        return send("PATCH", path, body);
    }

    public Resp get(String path) {
        return send("GET", path, null);
    }

    public Resp delete(String path) {
        return send("DELETE", path, null);
    }

    private Resp send(String method, String path, Object body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : MAPPER.writeValueAsString(body));
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .method(method, publisher).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode parsed = raw == null || raw.isBlank() ? MissingNode.getInstance() : MAPPER.readTree(raw);
            return new Resp(response.statusCode(), parsed, raw,
                    response.headers().firstValue("Content-Type").orElse(""),
                    response.headers().firstValue("Location").orElse(null),
                    response.headers().firstValue("Retry-After").orElse(null));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
