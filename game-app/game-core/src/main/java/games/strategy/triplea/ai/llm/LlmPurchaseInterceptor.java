package games.strategy.triplea.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.ProductionFrontier;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.collections.IntegerMap;

/**
 * Stands between ProAi and the real purchase delegate. ProAi plans its turn and computes its buy
 * exactly as usual; at the moment it commits that buy, the agent is asked instead, and the agent's
 * answer is committed if the engine accepts it. Every other path -- agent down, timeout, an answer
 * the engine rejects {@code maxAttempts} times, any exception -- commits ProAi's buy unchanged, so
 * a game never stalls on the agent.
 *
 * <p>When the agent's answer equals ProAi's proposal, ProAi's own map object is committed rather
 * than a rebuilt one: {@link IntegerMap} keeps insertion order, which decides the order (and so the
 * ids) of the units created, and a stub agent must reproduce a stock game exactly.
 *
 * <p>Repairs and every other delegate method pass straight through.
 */
@Slf4j
final class LlmPurchaseInterceptor implements InvocationHandler {
  static final String PROTOCOL = "llm-bridge/v1";

  private final IPurchaseDelegate real;
  private final GamePlayer player;
  private final int round;
  private final int maxAttempts;
  private final LlmAgentClient.Transport transport;
  private final Supplier<JsonObject> briefing;
  private final String gameTag;

  LlmPurchaseInterceptor(
      final IPurchaseDelegate real,
      final GamePlayer player,
      final int round,
      final int maxAttempts,
      final LlmAgentClient.Transport transport,
      final Supplier<JsonObject> briefing,
      final String gameTag) {
    this.real = real;
    this.player = player;
    this.round = round;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.transport = transport;
    this.briefing = briefing;
    this.gameTag = gameTag;
  }

  IPurchaseDelegate proxy() {
    return (IPurchaseDelegate)
        Proxy.newProxyInstance(
            IPurchaseDelegate.class.getClassLoader(),
            new Class<?>[] {IPurchaseDelegate.class},
            this);
  }

  @Override
  public Object invoke(final Object proxy, final Method method, final Object[] args)
      throws Throwable {
    if ("purchase".equals(method.getName())
        && args != null
        && args.length == 1
        && args[0] instanceof IntegerMap) {
      @SuppressWarnings("unchecked")
      final IntegerMap<ProductionRule> proposal = (IntegerMap<ProductionRule>) args[0];
      return decide(proposal);
    }
    try {
      return method.invoke(real, args);
    } catch (final InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /** Asks the agent, commits its answer or ProAi's, and returns the engine's result. */
  @Nullable
  String decide(final IntegerMap<ProductionRule> proposal) {
    final long start = System.nanoTime();
    final JsonObject decision = new JsonObject();
    final JsonArray attempts = new JsonArray();
    JsonObject briefingJson = null;
    boolean committed = false;
    String result = null;
    try {
      final JsonObject proposalJson = unitCounts(proposal);
      decision.add("proAiProposal", proposalJson);
      briefingJson = briefing.get();
      JsonElement previousAnswer = JsonNull.INSTANCE;
      JsonElement previousError = JsonNull.INSTANCE;
      for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        final JsonObject request = new JsonObject();
        request.addProperty("protocol", PROTOCOL);
        request.addProperty("gameTag", gameTag);
        request.addProperty(
            "decisionId", gameTag + "/r" + round + "/" + player.getName() + "/purchase");
        request.addProperty("attempt", attempt);
        request.add("briefing", briefingJson);
        request.add("proAiProposal", proposalJson);
        request.add("previousAnswer", previousAnswer);
        request.add("previousError", previousError);

        final LlmAgentClient.Reply reply = transport.post(request);
        final JsonObject attemptJson = new JsonObject();
        attemptJson.addProperty("attempt", attempt);
        attemptJson.addProperty("latencyMs", reply.latencyMs());
        attempts.add(attemptJson);
        if (!reply.succeeded()) {
          // The agent is down or too slow; asking again would only wait again.
          attemptJson.addProperty("error", reply.failure());
          decision.addProperty("fallbackReason", reply.failure());
          break;
        }
        final JsonObject body = reply.body();
        final JsonObject buy = body.getAsJsonObject("buy");
        final String agentSource = stringOr(body, "source", "llm");
        attemptJson.add("answer", buy);
        attemptJson.addProperty("agentSource", agentSource);
        attemptJson.addProperty("rationale", stringOr(body, "rationale", ""));

        final Conversion conversion = toRules(buy);
        String error = conversion.error();
        if (error == null) {
          final IntegerMap<ProductionRule> toCommit =
              unitCounts(conversion.rules()).equals(proposalJson) ? proposal : conversion.rules();
          error = real.purchase(toCommit);
          if (error == null) {
            committed = true;
            decision.addProperty("source", agentSource);
            decision.add("bought", unitCounts(toCommit));
            return null;
          }
        }
        attemptJson.addProperty("error", error);
        previousAnswer = buy;
        previousError = new JsonPrimitive(error);
        if (attempt == maxAttempts) {
          decision.addProperty(
              "fallbackReason", "engine rejected " + maxAttempts + " answers; last: " + error);
        }
      }
    } catch (final RuntimeException e) {
      log.error("LLM purchase for {} failed; using ProAi's buy", player.getName(), e);
      decision.addProperty("fallbackReason", "bridge error: " + e);
    } finally {
      if (!committed) {
        result = real.purchase(proposal);
        decision.addProperty("source", "proai-fallback");
        decision.add("bought", unitCounts(proposal));
      }
      decision.add("attempts", attempts);
      writeLog(briefingJson, decision, (System.nanoTime() - start) / 1_000_000L);
    }
    return result;
  }

  private void writeLog(
      @Nullable final JsonObject briefingJson, final JsonObject decision, final long totalMs) {
    if (!LlmDecisionLog.enabled()) {
      return;
    }
    final JsonObject meta = new JsonObject();
    meta.addProperty("decisionMs", totalMs);
    final JsonObject entry = new JsonObject();
    entry.add("briefing", briefingJson == null ? JsonNull.INSTANCE : briefingJson);
    entry.add("decision", decision);
    entry.add("meta", meta);
    LlmDecisionLog.write(entry);
  }

  /** An answer converted to production rules, or why it cannot be. */
  record Conversion(@Nullable IntegerMap<ProductionRule> rules, @Nullable String error) {}

  /**
   * Converts {@code {"infantry": 4}} to this player's production rules, in frontier order so the
   * result is the same on every run. Rejects unknown names and anything but whole numbers >= 0.
   */
  Conversion toRules(final JsonObject buy) {
    final ProductionFrontier frontier = player.getProductionFrontier();
    if (frontier == null) {
      return new Conversion(null, "this player has no production frontier");
    }
    final Map<String, Integer> wanted = new TreeMap<>();
    for (final Map.Entry<String, JsonElement> entry : buy.entrySet()) {
      final JsonElement value = entry.getValue();
      final int count;
      try {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
          throw new NumberFormatException();
        }
        final double number = value.getAsDouble();
        count = (int) number;
        if (count != number || count < 0) {
          throw new NumberFormatException();
        }
      } catch (final NumberFormatException | UnsupportedOperationException e) {
        return new Conversion(
            null, "Count for " + entry.getKey() + " must be a whole number >= 0, got " + value);
      }
      if (count > 0) {
        wanted.put(entry.getKey(), count);
      }
    }
    final IntegerMap<ProductionRule> rules = new IntegerMap<>();
    for (final ProductionRule rule : frontier.getRules()) {
      for (final Map.Entry<NamedAttachable, Integer> result : rule.getResults().entrySet()) {
        if (!(result.getKey() instanceof UnitType type) || !wanted.containsKey(type.getName())) {
          continue;
        }
        final int perRule = Math.max(1, result.getValue());
        final int count = wanted.remove(type.getName());
        if (count % perRule != 0) {
          return new Conversion(
              null, type.getName() + " is sold in lots of " + perRule + "; " + count + " is not");
        }
        rules.add(rule, count / perRule);
      }
    }
    if (!wanted.isEmpty()) {
      return new Conversion(
          null, "Not buildable by " + player.getName() + ": " + String.join(", ", wanted.keySet()));
    }
    return new Conversion(rules, null);
  }

  /** {@code {unit name: count}} for the units a set of rules produces, zero counts omitted. */
  static JsonObject unitCounts(final IntegerMap<ProductionRule> rules) {
    final Map<String, Integer> units = new TreeMap<>();
    for (final ProductionRule rule : rules.keySet()) {
      final int times = rules.getInt(rule);
      if (times <= 0) {
        continue;
      }
      for (final Map.Entry<NamedAttachable, Integer> result : rule.getResults().entrySet()) {
        if (result.getKey() instanceof UnitType type) {
          units.merge(type.getName(), times * result.getValue(), Integer::sum);
        }
      }
    }
    final JsonObject json = new JsonObject();
    units.forEach(json::addProperty);
    return json;
  }

  private static String stringOr(final JsonObject body, final String key, final String fallback) {
    final JsonElement value = body.get(key);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
  }
}
