package games.strategy.triplea.ai.llm;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import java.io.IOException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.collections.IntegerMap;

/**
 * The bridge between TripleA and an external LLM game agent. Plays as stock ProAi except where the
 * agent is asked to decide; contract and design in {@code docs/purchase-agent-spec.md} of the
 * triplea-llm-agent repo.
 *
 * <p>Three modes, chosen per purchase from system properties:
 *
 * <ul>
 *   <li><b>Agent</b> ({@value LlmAgentConfig#AGENT_URL} set and this seat in {@value
 *       LlmAgentConfig#SEATS}, or that list empty): ProAi plans as usual, then {@link
 *       LlmPurchaseInterceptor} asks the agent at the moment ProAi commits its buy and commits the
 *       agent's answer if the engine accepts it, ProAi's buy otherwise.
 *   <li><b>Logging</b> (no agent for this seat, {@value LlmDecisionLog#LOG_DIR_PROPERTY} set):
 *       plays exactly as ProAi and records the briefing plus ProAi's actual buy.
 *   <li><b>Plain</b> (neither): stock ProAi.
 * </ul>
 *
 * <p>The game must never stall on the bridge: every failure falls back to ProAi's own decision.
 */
@Slf4j
public class LlmBridgeAi extends ProAi {
  private boolean hintsLoaded;
  private LlmTerritoryHints hints = new LlmTerritoryHints(Map.of(), "none");

  public LlmBridgeAi(final String name, final String playerLabel) {
    super(name, playerLabel);
  }

  @Override
  protected Map<String, Double> getTerritoryWeights(final GamePlayer player) {
    // An AI belongs to one game. Latch the file (or its failure) before the first simulation;
    // edits to the source file cannot change its priorities halfway through the game.
    if (!hintsLoaded) {
      hintsLoaded = true;
      try {
        hints = LlmTerritoryHints.fromSystemProperty(player.getData());
      } catch (final IOException | RuntimeException e) {
        log.error("Invalid territory hints; continuing without hints: {}", e.getMessage());
      }
    }
    return hints.forPlayer(player.getName());
  }

  @Override
  protected void purchase(
      final boolean purchaseForBid,
      final int pusToSpend,
      final IPurchaseDelegate purchaseDelegate,
      final GameData data,
      final GamePlayer player) {
    if (purchaseForBid || pusToSpend <= 0) {
      super.purchase(purchaseForBid, pusToSpend, purchaseDelegate, data, player);
      return;
    }
    final LlmAgentConfig config = LlmAgentConfig.fromSystemProperties();
    if (config.decidesFor(player)) {
      final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
      final LlmPurchaseInterceptor interceptor =
          new LlmPurchaseInterceptor(
              purchaseDelegate,
              player,
              data.getSequence().getRound(),
              config.maxAttempts(),
              LlmAgentClient.transport(config.agentUrl(), config.timeout()),
              // Built when ProAi commits, from the real game data (its own simulation ran on a
              // copy); repairs have been paid by then, so the bank is what is left to spend.
              () ->
                  LlmBriefingBuilder.purchase(data, player, player.getResources().getQuantity(pus)),
              LlmDecisionLog.gameTag());
      super.purchase(purchaseForBid, pusToSpend, interceptor.proxy(), data, player);
      return;
    }
    if (!LlmDecisionLog.enabled()) {
      super.purchase(purchaseForBid, pusToSpend, purchaseDelegate, data, player);
      return;
    }
    JsonObject record = null;
    final long start = System.nanoTime();
    try {
      record = LlmBriefingBuilder.purchase(data, player, pusToSpend);
    } catch (final RuntimeException e) {
      log.error("LLM briefing failed for {}; continuing as ProAi", player.getName(), e);
    }
    final long briefingMs = (System.nanoTime() - start) / 1_000_000L;

    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final int bankBefore = player.getResources().getQuantity(pus);
    final IntegerMap<UnitType> before =
        new IntegerMap<>(player.getUnitCollection().getUnitsByType());
    super.purchase(purchaseForBid, pusToSpend, purchaseDelegate, data, player);

    if (record == null) {
      return;
    }
    final IntegerMap<UnitType> bought =
        new IntegerMap<>(player.getUnitCollection().getUnitsByType());
    bought.subtract(before);
    final JsonObject outcome = new JsonObject();
    outcome.addProperty("source", "proai");
    outcome.add("bought", LlmBriefingBuilder.counts(bought));
    // Includes repairs, which do not show up as units.
    outcome.addProperty("pusSpent", bankBefore - player.getResources().getQuantity(pus));
    final JsonObject meta = new JsonObject();
    meta.addProperty("briefingMs", briefingMs);
    final JsonObject entry = new JsonObject();
    entry.add("briefing", record);
    entry.add("decision", outcome);
    entry.add("meta", meta);
    LlmDecisionLog.write(entry);
  }
}
