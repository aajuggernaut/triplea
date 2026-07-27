package games.strategy.triplea.ai.pro;

/**
 * ProAi with WW2 Path to Victory specific behaviour enabled.
 *
 * <p>Exists so that a patched bot and a stock one can play each other inside a single game. Without
 * two distinct player types, every measurement compares a patched world against a stock world, and
 * an improvement to both sides is indistinguishable from an improvement to one. Registered
 * alongside stock ProAi in {@link games.strategy.engine.framework.startup.ui.PlayerTypes}.
 *
 * <p>Deliberately inherits all of ProAi's tactical machinery — move validation, the battle
 * calculator, unit placement — and differs only in strategy, which is what the PTV-specific flags
 * on {@link ProData} select.
 */
public final class PtvProAi extends ProAi {
  public PtvProAi(final String name, final String playerLabel) {
    super(name, playerLabel, true);
  }
}
