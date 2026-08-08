package org.triplea.ptv.eval;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.ai.pro.util.ProBattleUtils;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ProBattleUtils#attackCannotHitAnyDefender} actually detects the W15 case.
 *
 * <p>Written because a 10-round probe reported zero occurrences, and "the defect is rare" and "the
 * detector is broken" look identical from a count of zero. A live game cannot distinguish them; a
 * constructed battle can. Uses real PTV unit types rather than a synthetic map, since the whole
 * question is what this map's attachments say.
 */
class PtvNoHitAttackTest {

  private static GameData data;
  private static GamePlayer italians;
  private static GamePlayer russians;

  @BeforeAll
  static void setUp() throws IOException {
    GameTestUtils.setUp();
    final ServerGame game =
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
    italians = data.getPlayerList().getPlayerId("Italians");
    russians = data.getPlayerList().getPlayerId("Russians");
  }

  private static List<Unit> units(final GamePlayer owner, final String type, final int count) {
    final UnitType unitType = data.getUnitTypeList().getUnitTypeOrThrow(type);
    return unitType.createTemp(count, owner);
  }

  @Test
  @DisplayName("the observed case: aircraft alone against a submarine cannot hit it")
  void airCannotHitSubmarineWithoutDestroyer() {
    assertThat(
        ProBattleUtils.attackCannotHitAnyDefender(
            units(italians, "fighter", 2), units(russians, "submarine", 1)),
        is(true));
  }

  @Test
  @DisplayName("one destroyer in the force cancels the restriction for every unit")
  void destroyerMakesTheSameAttackLegal() {
    final List<Unit> attackers = units(italians, "fighter", 2);
    attackers.addAll(units(italians, "destroyer", 1));
    assertThat(
        ProBattleUtils.attackCannotHitAnyDefender(attackers, units(russians, "submarine", 1)),
        is(false));
  }

  @Test
  @DisplayName("air against a surface ship is fine")
  void airCanHitSurfaceShips() {
    assertThat(
        ProBattleUtils.attackCannotHitAnyDefender(
            units(italians, "fighter", 2), units(russians, "cruiser", 1)),
        is(false));
  }

  @Test
  @DisplayName("a mixed defence is attackable -- the submarine is not the only target")
  void mixedDefendersAreAttackable() {
    final List<Unit> defenders = units(russians, "submarine", 1);
    defenders.addAll(units(russians, "cruiser", 1));
    assertThat(
        ProBattleUtils.attackCannotHitAnyDefender(units(italians, "fighter", 2), defenders),
        is(false));
  }

  @Test
  @DisplayName("an unopposed capture is never reported -- it must not be pruned")
  void emptyDefendersAreNotADefect() {
    assertThat(
        ProBattleUtils.attackCannotHitAnyDefender(units(italians, "infantry", 1), List.of()),
        is(false));
  }
}
