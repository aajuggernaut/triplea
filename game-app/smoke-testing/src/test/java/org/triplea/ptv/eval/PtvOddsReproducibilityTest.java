package org.triplea.ptv.eval;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.random.DeterministicRun;
import games.strategy.triplea.odds.calculator.AggregateResults;
import games.strategy.triplea.odds.calculator.ConcurrentBattleCalculator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins down the remaining determinism failure at the level of a single battle.
 *
 * <p>A cross-JVM pair of 8-round games showed the AI receiving different odds for identical inputs
 * from round 1 onward, but each probe costs ~20 minutes and only reports that a rolling hash
 * diverged. This asks the same question directly: run one battle through the pinned four-worker
 * calculator twice and compare. Seconds instead of game-minutes, and it separates the two
 * possibilities that need opposite fixes -- a differing <em>roll count</em> means the runs simulated
 * different numbers of battles (a pool or run-count-split problem), while an identical roll count
 * with differing percentages means the dice themselves are not seeded reproducibly.
 */
class PtvOddsReproducibilityTest {

  private static GameData data;
  private static ServerGame game;
  private static ConcurrentBattleCalculator calc;
  private static GamePlayer germans;
  private static GamePlayer russians;

  @BeforeAll
  static void setUp() throws IOException {
    GameTestUtils.setUp();
    DeterministicRun.enable(2002L);
    game =
        GameTestUtils.setUpGameWithAis(
            "ww2_path_to_victory.xml",
            gameData -> {
              final Map<String, PlayerTypes.Type> types = new HashMap<>();
              gameData
                  .getPlayerList()
                  .getPlayers()
                  .forEach(p -> types.put(p.getName(), PlayerTypes.PRO_AI));
              return types;
            });
    data = game.getData();
    germans = data.getPlayerList().getPlayerId("Germans");
    russians = data.getPlayerList().getPlayerId("Russians");
    calc = new ConcurrentBattleCalculator();
    calc.setGameData(data).join();
  }

  @AfterAll
  static void tearDown() {
    if (calc != null) {
      calc.setGameData(null);
    }
    if (game != null) {
      game.stopGame();
    }
    DeterministicRun.disable();
  }

  private static List<Unit> units(final GamePlayer owner, final String type, final int count) {
    final UnitType unitType = data.getUnitTypeList().getUnitTypeOrThrow(type);
    return new ArrayList<>(unitType.createTemp(count, owner));
  }

  private static AggregateResults run(
      final Territory where, final List<Unit> attackers, final List<Unit> defenders) {
    return calc.calculate(
        germans, russians, where, attackers, defenders, List.of(), List.of(), false, 100);
  }

  @Test
  @DisplayName("the same battle simulated twice returns the same odds")
  void sameBattleGivesSameOdds() {
    final Territory where = data.getMap().getTerritoryOrNull("Ukraine");
    final AggregateResults first = run(where, units(germans, "infantry", 8), units(russians, "infantry", 6));
    final AggregateResults second = run(where, units(germans, "infantry", 8), units(russians, "infantry", 6));

    // Reported before the percentages: which of these two fails tells you which bug it is.
    assertThat(
        "roll count differs -> the two runs simulated different amounts, not different dice",
        second.getRollCount(),
        is(first.getRollCount()));
    assertThat(second.getAttackerWinPercent(), is(first.getAttackerWinPercent()));
    assertThat(second.getDefenderWinPercent(), is(first.getDefenderWinPercent()));
    assertThat(second.getAverageAttackingUnitsLeft(), is(first.getAverageAttackingUnitsLeft()));
    assertThat(second.getAverageBattleRoundsFought(), is(first.getAverageBattleRoundsFought()));
  }

  @Test
  @DisplayName("the SAME units in a different order must give the same odds")
  void unitOrderDoesNotChangeOdds() {
    final Territory where = data.getMap().getTerritoryOrNull("Ukraine");
    final List<Unit> attackers = units(germans, "infantry", 5);
    attackers.addAll(units(germans, "armour", 3));
    final List<Unit> defenders = units(russians, "infantry", 6);

    final AggregateResults inOrder = run(where, new ArrayList<>(attackers), new ArrayList<>(defenders));

    // Same set, different sequence. The AI builds these collections from hash-ordered maps keyed by
    // Unit, so their order is not something it controls or guarantees. If the simulation is
    // order-sensitive then every such collection is an entropy source, and seeding the dice cannot
    // fix it -- the battle plays out differently with identical rolls.
    final List<Unit> shuffledAttackers = new ArrayList<>(attackers);
    Collections.reverse(shuffledAttackers);
    final List<Unit> shuffledDefenders = new ArrayList<>(defenders);
    Collections.reverse(shuffledDefenders);
    final AggregateResults reordered = run(where, shuffledAttackers, shuffledDefenders);

    assertThat(reordered.getRollCount(), is(inOrder.getRollCount()));
    assertThat(reordered.getAttackerWinPercent(), is(inOrder.getAttackerWinPercent()));
    assertThat(
        reordered.getAverageAttackingUnitsLeft(), is(inOrder.getAverageAttackingUnitsLeft()));
  }

  @Test
  @DisplayName("repeated across many battles, so an intermittent race is caught")
  void repeatedBattlesAreStable() {
    final Territory where = data.getMap().getTerritoryOrNull("Ukraine");
    final AggregateResults reference =
        run(where, units(germans, "armour", 5), units(russians, "infantry", 5));
    for (int i = 0; i < 20; i++) {
      final AggregateResults repeat =
          run(where, units(germans, "armour", 5), units(russians, "infantry", 5));
      assertThat(
          "attempt " + i + " diverged", repeat.getRollCount(), is(reference.getRollCount()));
      assertThat(
          "attempt " + i + " diverged",
          repeat.getAttackerWinPercent(),
          is(reference.getAttackerWinPercent()));
    }
  }
}
