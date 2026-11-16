package games.strategy.triplea.ai.llm;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.collections.IntegerMap;

/**
 * The bridge between TripleA and an external LLM game agent. Milestone 2 ("logging bridge"): it
 * plays exactly like stock ProAi and, at each strategic decision, records the briefing an LLM would
 * be shown together with what ProAi actually chose. That proves the hook, shows real briefing
 * sizes, and builds a corpus of (situation, ProAi answer) pairs before any model is involved.
 *
 * <p>Logging is off unless {@value LlmDecisionLog#LOG_DIR_PROPERTY} is set. Nothing here may change
 * a decision: the briefing is built from the live game data before ProAi acts, read-only, and any
 * failure in it is logged and ignored so the game never stalls on the bridge.
 *
 * <p>Only purchases are covered so far. The next step replaces ProAi's purchase with the agent's
 * answer, falling back to ProAi's on timeout, error, or an illegal answer.
 */
@Slf4j
public class LlmBridgeAi extends ProAi {

  public LlmBridgeAi(final String name, final String playerLabel) {
    super(name, playerLabel);
  }

  @Override
  protected void purchase(
      final boolean purchaseForBid,
      final int pusToSpend,
      final IPurchaseDelegate purchaseDelegate,
      final GameData data,
      final GamePlayer player) {
    if (purchaseForBid || pusToSpend <= 0 || !LlmDecisionLog.enabled()) {
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
