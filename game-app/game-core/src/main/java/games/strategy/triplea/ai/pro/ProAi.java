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

  /**
   * Clears the shared calculator's game data and returns the future that completes when the async
   * teardown has actually finished. {@link #stopGame} fires the same teardown but discards the
   * future, so a batch runner that starts the next game immediately can otherwise race the previous
   * game's teardown: {@code createWorkers} reads {@code DeterministicRun.isEnabled()} more than
   * once, and the runner flips that flag between games. Join this before re-enabling.
   */
  public static java.util.concurrent.CompletableFuture<Boolean> drainSharedCalculator() {
    return concurrentCalc.setGameData(null);
  }

  @Override
  protected void prepareData(final GameData data) {
    concurrentCalc.setGameData(data);
  }
}
