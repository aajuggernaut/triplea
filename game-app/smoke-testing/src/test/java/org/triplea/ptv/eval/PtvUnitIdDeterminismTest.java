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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the assumption the rest of the determinism work rests on: that unit ids replay.
 *
 * <p>{@link games.strategy.engine.data.Unit} hashes on its id, so if two runs of a seed hand out
 * different ids then every {@code HashMap<Unit, ?>} in the AI -- the move-options maps, the defend
 * maps, the amphibious maps -- iterates differently, and the bots make different choices for
 * reasons that have nothing to do with the code under test. Kept separate from {@link
 * PtvDeterminismTest} because it only parses the map, so it runs in seconds rather than minutes and
 * localises a failure to setup rather than to gameplay.
 */
class PtvUnitIdDeterminismTest {

  private static final String PTV_XML = "ww2_path_to_victory.xml";

  @BeforeAll
  static void setUp() throws IOException {
    GameTestUtils.setUp();
  }

  @Test
  @DisplayName("starting unit ids replay for the same seed")
  void startingUnitIdsReplay() {
    assertThat(startingUnitIds(2000L), is(startingUnitIds(2000L)));
  }

  @Test
  @DisplayName("starting unit ids differ between seeds")
  void startingUnitIdsDifferBetweenSeeds() {
    assertThat(startingUnitIds(2000L), is(not(startingUnitIds(2001L))));
  }

  /**
   * Territory-qualified so a reordering within a territory is caught, not just a global permute.
   */
  private static List<String> startingUnitIds(final long seed) {
    ServerGame game = null;
    try {
      DeterministicRun.enable(seed);
      game = GameTestUtils.setUpGameWithAis(PTV_XML, PtvUnitIdDeterminismTest::allProAi);
      final GameData data = game.getData();
      final List<String> ids = new ArrayList<>();
      for (final Territory t : data.getMap().getTerritories()) {
        for (final Unit unit : t.getUnits()) {
          ids.add(t.getName() + "/" + unit.getType().getName() + "/" + unit.getId());
        }
      }
      return ids;
    } finally {
      if (game != null) {
        game.stopGame();
      }
      DeterministicRun.disable();
    }
  }

  private static Map<String, PlayerTypes.Type> allProAi(final GameData data) {
    final Map<String, PlayerTypes.Type> types = new HashMap<>();
    for (final var player : data.getPlayerList().getPlayers()) {
      types.put(player.getName(), PlayerTypes.PRO_AI);
    }
    return types;
  }
}
