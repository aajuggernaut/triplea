package games.strategy.triplea.ai.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LlmAgentClientTest {
  private static final JsonObject REQUEST =
      JsonParser.parseString("{\"protocol\":\"llm-bridge/v1\",\"previousError\":null}")
          .getAsJsonObject();

  private HttpServer server;
  private final AtomicReference<String> received = new AtomicReference<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void returnsTheAgentsAnswer() throws IOException {
    final URI url = serve(200, "{\"buy\":{\"infantry\":4},\"source\":\"llm\"}", 0);

    final LlmAgentClient.Reply reply = LlmAgentClient.post(url, REQUEST, Duration.ofSeconds(5));

    assertThat(reply.succeeded(), is(true));
    assertThat(reply.body().getAsJsonObject("buy").get("infantry").getAsInt(), is(4));
    // Explicit nulls survive serialisation, as the contract requires.
    assertThat(received.get(), containsString("\"previousError\":null"));
  }

  @Test
  void non200IsAFailure() throws IOException {
    final URI url = serve(500, "model crashed", 0);

    final LlmAgentClient.Reply reply = LlmAgentClient.post(url, REQUEST, Duration.ofSeconds(5));

    assertThat(reply.succeeded(), is(false));
    assertThat(reply.failure(), containsString("HTTP 500"));
  }

  @Test
  void bodyWithoutABuyObjectIsAFailure() throws IOException {
    assertThat(post(serve(200, "not json at all", 0)).failure(), containsString("not JSON"));
    stop();
    assertThat(post(serve(200, "{\"rationale\":\"x\"}", 0)).failure(), containsString("buy"));
    stop();
    assertThat(post(serve(200, "{\"buy\":[1,2]}", 0)).failure(), containsString("buy"));
  }

  @Test
  void slowAgentTimesOut() throws IOException {
    final URI url = serve(200, "{\"buy\":{}}", 3000);

    final LlmAgentClient.Reply reply = LlmAgentClient.post(url, REQUEST, Duration.ofSeconds(1));

    assertThat(reply.succeeded(), is(false));
    assertThat(reply.failure(), containsString("did not answer within 1 s"));
    assertThat(reply.latencyMs(), lessThan(2_900L));
  }

  @Test
  void agentNotRunningIsAFailure() throws IOException {
    final int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    final LlmAgentClient.Reply reply =
        LlmAgentClient.post(
            URI.create("http://127.0.0.1:" + closedPort), REQUEST, Duration.ofSeconds(5));

    assertThat(reply.succeeded(), is(false));
    assertThat(reply.failure(), containsString("unreachable"));
  }

  private LlmAgentClient.Reply post(final URI url) {
    return LlmAgentClient.post(url, REQUEST, Duration.ofSeconds(5));
  }

  private URI serve(final int status, final String body, final long delayMs) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        LlmAgentClient.PURCHASE_PATH,
        exchange -> {
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          try {
            Thread.sleep(delayMs);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          } catch (final IOException e) {
            // The client gave up (timeout test); nothing to do.
          }
        });
    server.start();
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }
}
