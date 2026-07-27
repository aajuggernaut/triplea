package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GameData;
import games.strategy.engine.framework.GameShutdownRegistry;
import games.strategy.triplea.odds.calculator.ConcurrentBattleCalculator;

public class ProAi extends AbstractProAi {
  // Odds calculator
  private static final ConcurrentBattleCalculator concurrentCalc = new ConcurrentBattleCalculator();

  public ProAi(final String name, final String playerLabel) {
    this(name, playerLabel, false);
  }

  /**
   * @param ptvFeatures Enables WW2 Path to Victory specific behaviour. See {@link PtvProAi}. The
   *     battle calculator is shared with stock ProAi instances, which is safe because it is rebound
   *     to the current game data in {@link #prepareData} at the start of each player's turn.
   */
  protected ProAi(final String name, final String playerLabel, final boolean ptvFeatures) {
    super(name, concurrentCalc, new ProData(ptvFeatures), playerLabel);
    // cuncurrentCalc is static so that it can be shared across all ProAi instances
    // at the end of a game, it needs to be cleared up
    GameShutdownRegistry.registerShutdownAction(() -> concurrentCalc.setGameData(null));
  }

  @Override
  public void stopGame() {
    super.stopGame(); // absolutely MUST call super.stopGame() first
    concurrentCalc.cancel();
    concurrentCalc.setGameData(null);
  }

  @Override
  protected void prepareData(final GameData data) {
    concurrentCalc.setGameData(data);
  }
}
