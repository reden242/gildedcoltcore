package dev.rdbot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Groq chat-completions client (OpenAI-compatible, JSON mode).
 *
 * <p>Failure ladder: honour Retry-After and cool the key, fail over to the
 * next key with backoff + jitter, retry on the fallback model, then give up
 * with {@link AiResponse#aiUnavailable} so staff get told instead of silence.
 */
public final class GroqClient {

    private static final ObjectMapper M = new ObjectMapper();

    private final String baseUrl;
    private final KeyPool pool;
    private final String model;
    private final String fallbackModel;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GroqClient(String baseUrl, KeyPool pool, String model, String fallbackModel,
                      double temperature, int maxTokens, int timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.pool = pool;
        this.model = model;
        this.fallbackModel = fallbackModel;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Sends the conversation for one guild and returns the validated reply.
     * Never throws - every failure path returns an escalation response.
     */
    public AiResponse chat(String guildId, String systemPrompt, String userMessage) {
        if (pool.size() == 0) return AiResponse.aiUnavailable("No Groq API keys configured.");
        ApiKey key = pool.forGuild(guildId);
        long deadline = System.currentTimeMillis() + 30_000L;
        boolean triedFallbackModel = false;
        int attempts = 0;

        while (System.currentTimeMillis() < deadline && attempts < 6) {
            attempts++;
            if (!key.limiter().acquire(estimateTokens(systemPrompt, userMessage), 5_000L)) {
                key = pool.failover(key);
                continue;
            }
            String usedModel = triedFallbackModel ? fallbackModel : model;
            try {
                CallResult result = call(key, usedModel, systemPrompt, userMessage);
                if (result.status() == 200) {
                    AiResponse parsed = ResponseParser.parseOrEscalate(result.body());
                    long cost = estimateTokens(systemPrompt, userMessage) + result.usageTokens();
                    key.recordSuccess(cost);
                    return parsed;
                }
                key.recordError("HTTP " + result.status());
                if (result.status() == 429 || result.status() >= 500) {
                    key.coolDownUntil(System.currentTimeMillis() + result.retryAfterMillis());
                    key = pool.failover(key);
                    backoff(attempts);
                    if (!triedFallbackModel) triedFallbackModel = true;
                    continue;
                }
                // 400/401 on this key: rotate to the next key, keep the model.
                key = pool.failover(key);
                backoff(attempts);
            } catch (Exception e) {
                key.recordError(String.valueOf(e.getMessage()));
                key.coolDownUntil(System.currentTimeMillis() + 5_000L);
                key = pool.failover(key);
                backoff(attempts);
            }
        }
        return AiResponse.aiUnavailable("AI unavailable after retries (keys cooling down or rate limited).");
    }

    private CallResult call(ApiKey key, String model, String systemPrompt, String userMessage)
            throws Exception {
        ObjectNode body = M.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        ObjectNode responseFormat = body.putObject("response_format");
        responseFormat.put("type", "json_object");
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userMessage);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + key.secret())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        long retryAfter = 10_000L;
        var header = response.headers().firstValue("retry-after");
        if (header.isPresent()) {
            try {
                retryAfter = Long.parseLong(header.get().strip()) * 1000L;
            } catch (NumberFormatException ignored) {
            }
        }
        if (response.statusCode() != 200) {
            return new CallResult(response.statusCode(), "", 0, retryAfter);
        }
        JsonNode node = M.readTree(response.body());
        int tokens = node.path("usage").path("completion_tokens").asInt(0)
                + node.path("usage").path("prompt_tokens").asInt(0);
        String content = node.path("choices").path(0).path("message").path("content").asText("");
        return new CallResult(200, content, tokens, 0);
    }

    private static long estimateTokens(String systemPrompt, String userMessage) {
        return (systemPrompt.length() + userMessage.length()) / 4L + 64L;
    }

    private static void backoff(int attempt) {
        long base = Math.min(4_000L, 250L * (1L << Math.min(attempt, 5)));
        long jitter = (long) (Math.random() * 250.0);
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record CallResult(int status, String body, int usageTokens, long retryAfterMillis) { }
}
