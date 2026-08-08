package org.triplea.ptv.eval;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.random.DeterministicRun;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check that makes evaluation results trustworthy: two runs of the same seed must play the same
 * game.
 *
 * <p>Before {@link DeterministicRun}, they did not. Replaying seeds 2000-2007 on 6 cores and then
 * on 8 produced different results, and seed 2003 inverted outright (Axis 0-21 became 20-1), because
 * the AI's battle simulations drew from unseeded random sources and were split across a
 * core-count-dependent number of workers. Any patched-vs-stock comparison run in that state was
 * measuring thread scheduling as much as it was measuring the patch.
 *
 * <p>Deliberately compares a full board fingerprint rather than the aggregate victory-city or
 * production score: two boards can reach the same totals by different routes, so totals would let
 * real divergence through.
 */
class PtvDeterminismTest {

  private static final String PTV_XML = "ww2_path_to_victory.xml";

  /** Enough turns for the AIs to purchase, move, fight and place -- where divergence shows up. */
  private static final int ROUNDS = 2;

  @BeforeAll
  static void setUp() throws IOException {
    GameTestUtils.setUp();
  }

  @Test
  @DisplayName("the same seed replays exactly")
  void sameSeedProducesSameGame() {
    final Map<String, String> first = fingerprintOfGame(2000L, true);
    final Map<String, String> second = fingerprintOfGame(2000L, true);
    // Reported as a per-territory diff: comparing the maps directly dumps two 361-entry boards
    // into the failure message, which says nothing about where the divergence started.
    assertThat(diff(first, second), is(List.of()));
  }

  @Test
  @DisplayName("different seeds still produce different games")
  void differentSeedsProduceDifferentGames() {
    assertThat(fingerprintOfGame(2000L, true), is(not(fingerprintOfGame(2001L, true))));
  }

  private static List<String> diff(
      final Map<String, String> first, final Map<String, String> second) {
    final List<String> differences = new ArrayList<>();
    for (final String territory : new TreeSet<>(first.keySet())) {
      final String a = first.get(territory);
      final String b = second.get(territory);
      if (!a.equals(b)) {
        differences.add(territory + ":\n    run 1: " + a + "\n    run 2: " + b);
      }
    }
    return differences;
  }

  /**
   * Plays {@value ROUNDS} rounds and returns an order-independent description of the whole board:
   * who owns each territory and exactly what stands on it.
   */
  private static Map<String, String> fingerprintOfGame(
      final long seed, final boolean deterministic) {
    ServerGame game = null;
    try {
      if (deterministic) {
        // Before parsing: the starting units are created there, and their ids must come from the
        // seeded sequence for hash-ordered collections to iterate reproducibly.
        DeterministicRun.enable(seed);
      }
      game = GameTestUtils.setUpGameWithAis(PTV_XML, PtvDeterminismTest::allProAi);
      game.setRandomSource(new SeededRandomSource(seed));
      game.setStopGameOnDelegateExecutionStop(true);

      final GameData data = game.getData();
      while (!game.isGameOver() && data.getSequence().getRound() <= ROUNDS) {
        game.runNextStep();
      }
      return fingerprint(data);
    } finally {
      if (game != null) {
        game.stopGame();
      }
      DeterministicRun.disable();
    }
  }

  private static Map<String, String> fingerprint(final GameData data) {
    final Map<String, String> board = new TreeMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      final Map<String, Integer> unitCounts = new TreeMap<>();
      for (final Unit unit : t.getUnits()) {
        unitCounts.merge(
            unit.getOwner().getName() + " " + unit.getType().getName(), 1, Integer::sum);
      }
      board.put(t.getName(), t.getOwner().getName() + " " + unitCounts);
    }
    return board;
  }

  private static Map<String, PlayerTypes.Type> allProAi(final GameData data) {
    final Map<String, PlayerTypes.Type> types = new HashMap<>();
    for (final var player : data.getPlayerList().getPlayers()) {
      types.put(player.getName(), PlayerTypes.PRO_AI);
    }
    return types;
  }
}
