package games.strategy.triplea.ai.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.annotation.Nullable;

/**
 * The HTTP side of the bridge: one POST per attempt to the agent's {@code /v1/purchase}, JSON both
 * ways (contract: {@code docs/purchase-agent-spec.md} in the triplea-llm-agent repo).
 *
 * <p>Never throws. Every failure -- agent not running, timeout, non-200 status, a body that is not
 * a JSON object with a {@code buy} object -- comes back as a {@link Reply} with a reason, which the
 * caller turns into ProAi's fallback.
 */
final class LlmAgentClient {
  static final String PURCHASE_PATH = "/v1/purchase";

  /** Explicit nulls: the contract sends {@code "previousError": null} on a first attempt. */
  private static final Gson REQUEST_GSON =
      new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  private LlmAgentClient() {}

  /** One exchange with the agent: the parsed body on success, otherwise why it failed. */
  record Reply(@Nullable JsonObject body, @Nullable String failure, long latencyMs) {
    static Reply ok(final JsonObject body, final long latencyMs) {
      return new Reply(body, null, latencyMs);
    }

    static Reply failed(final String reason, final long latencyMs) {
      return new Reply(null, reason, latencyMs);
    }

    boolean succeeded() {
      return body != null;
    }
  }

  /** How the interceptor sends a request; tests substitute their own. */
  @FunctionalInterface
  interface Transport {
    Reply post(JsonObject request);
  }

  static Transport transport(final URI agentUrl, final Duration timeout) {
    return request -> post(agentUrl, request, timeout);
  }

  static Reply post(final URI agentUrl, final JsonObject request, final Duration timeout) {
    final long start = System.nanoTime();
    try {
      final HttpRequest httpRequest =
          HttpRequest.newBuilder(URI.create(agentUrl + PURCHASE_PATH))
              .timeout(timeout)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      REQUEST_GSON.toJson(request), StandardCharsets.UTF_8))
              .build();
      final HttpResponse<String> response =
          HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      final long latencyMs = elapsedMs(start);
      if (response.statusCode() != 200) {
        return Reply.failed(
            "agent returned HTTP " + response.statusCode() + ": " + abbreviate(response.body()),
            latencyMs);
      }
      final JsonElement parsed;
      try {
        parsed = JsonParser.parseString(response.body());
      } catch (final RuntimeException e) {
        return Reply.failed("agent reply is not JSON: " + abbreviate(response.body()), latencyMs);
      }
      if (!parsed.isJsonObject()
          || !parsed.getAsJsonObject().has("buy")
          || !parsed.getAsJsonObject().get("buy").isJsonObject()) {
        return Reply.failed(
            "agent reply has no \"buy\" object: " + abbreviate(response.body()), latencyMs);
      }
      return Reply.ok(parsed.getAsJsonObject(), latencyMs);
    } catch (final HttpTimeoutException e) {
      return Reply.failed(
          "agent did not answer within " + timeout.toSeconds() + " s", elapsedMs(start));
    } catch (final IOException e) {
      return Reply.failed("agent unreachable at " + agentUrl + ": " + e, elapsedMs(start));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Reply.failed("interrupted while waiting for the agent", elapsedMs(start));
    } catch (final RuntimeException e) {
      return Reply.failed("could not call the agent: " + e, elapsedMs(start));
    }
  }

  private static long elapsedMs(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private static String abbreviate(@Nullable final String text) {
    if (text == null) {
      return "<empty>";
    }
    return text.length() <= 200 ? text : text.substring(0, 200) + "...";
  }
}
