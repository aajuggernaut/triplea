package games.strategy.triplea.ai.llm;

import games.strategy.engine.data.GamePlayer;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * How the bridge reaches the external agent, read from system properties at each decision (so a
 * batch run configures it on the command line and a test can change it between cases).
 *
 * @param agentUrl base URL of the agent, e.g. {@code http://127.0.0.1:8765}; {@code null} means no
 *     agent: the bridge only logs, exactly as before the override existed.
 * @param seats player names the agent decides for; empty means every LlmBridgeAi seat.
 * @param timeout how long one HTTP attempt may take before the bridge gives up on it.
 * @param maxAttempts how many answers the engine may reject before ProAi's own buy is used.
 */
@Slf4j
record LlmAgentConfig(
    @Nullable URI agentUrl, Set<String> seats, Duration timeout, int maxAttempts) {

  static final String AGENT_URL = "triplea.ai.llm.agentUrl";
  static final String SEATS = "triplea.ai.llm.seats";
  static final String TIMEOUT_SECONDS = "triplea.ai.llm.timeoutSeconds";
  static final String MAX_ATTEMPTS = "triplea.ai.llm.maxAttempts";

  static final int DEFAULT_TIMEOUT_SECONDS = 300;
  static final int DEFAULT_MAX_ATTEMPTS = 3;

  static LlmAgentConfig fromSystemProperties() {
    return new LlmAgentConfig(
        parseUrl(System.getProperty(AGENT_URL)),
        parseSeats(System.getProperty(SEATS)),
        Duration.ofSeconds(
            parsePositive(System.getProperty(TIMEOUT_SECONDS), DEFAULT_TIMEOUT_SECONDS)),
        parsePositive(System.getProperty(MAX_ATTEMPTS), DEFAULT_MAX_ATTEMPTS));
  }

  /** True if the agent, rather than ProAi, chooses this player's purchases. */
  boolean decidesFor(final GamePlayer player) {
    return agentUrl != null && (seats.isEmpty() || seats.contains(player.getName()));
  }

  @Nullable
  private static URI parseUrl(@Nullable final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      final URI uri = URI.create(value.trim().replaceAll("/+$", ""));
      if (uri.getScheme() == null || uri.getHost() == null) {
        throw new IllegalArgumentException("needs scheme and host");
      }
      return uri;
    } catch (final IllegalArgumentException e) {
      log.error("Ignoring {}={}: not a usable URL ({}); playing as ProAi", AGENT_URL, value, e);
      return null;
    }
  }

  private static Set<String> parseSeats(@Nullable final String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static int parsePositive(@Nullable final String value, final int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      final int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : fallback;
    } catch (final NumberFormatException e) {
      log.error("Ignoring non-numeric LLM bridge setting '{}'; using {}", value, fallback);
      return fallback;
    }
  }
}
