package org.triplea.ptv.eval;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.delegate.Matches;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Proves {@link ProMatches#territoryIsValuableAndAmphibiouslyThreatened} actually fires for the
 * W5/W14 case -- and only for it.
 *
 * <p>Written under the W21 rule: a probe count of zero cannot distinguish "the defect is fixed"
 * from "the candidate condition is dead code", which is exactly how the first W7 patch survived
 * unmeasured. The cases construct the threat directly on the real PTV board, since the whole
 * question is what this map's geography says: Norway's land neighbors are Axis Finland and neutral
 * Sweden, so the stock defend-with-one-unit threat test can never fire for it, while a British
 * battleship group starts two sea zones off its coast and a loaded transport three.
 *
 * <p>Ordered because the later cases mutate the board (clearing the North Sea, re-adding ships at
 * measured distances); the start-position cases run first.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PtvAmphibThreatTest {

  private static GameData data;
  private static GamePlayer germans;
  private static Territory norway;

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
    germans = data.getPlayerList().getPlayerId("Germans");
    norway = data.getMap().getTerritoryOrNull("Norway");
  }

  private static boolean threatened(final Territory t) {
    return ProMatches.territoryIsValuableAndAmphibiouslyThreatened(data, germans).test(t);
  }

  /** Water territories within {@code distance} water moves of {@code t}, per the map itself. */
  private static Set<Territory> waterWithin(final Territory t, final int distance) {
    return data.getMap().getNeighbors(t, distance, Matches.territoryIsWater());
  }

  @Test
  @Order(1)
  @DisplayName("the observed case: Norway at the start position, Royal Navy two zones out")
  void norwayIsThreatenedAtTheStartPosition() {
    assertThat(threatened(norway), is(true));
  }

  @Test
  @Order(2)
  @DisplayName("worthless ground is never a candidate, however close the enemy fleet")
  void lowValueCoastIsNotACandidate() {
    // Eire: production 0, British destroyer and transport in the adjacent 112 Sea Zone.
    assertThat(threatened(data.getMap().getTerritoryOrNull("Eire")), is(false));
  }

  @Test
  @Order(3)
  @DisplayName("a valuable landlocked territory is immune by geometry")
  void landlockedTerritoryIsNotACandidate() {
    // Slovakia Hungary: production 3, no sea-zone connection at all.
    assertThat(threatened(data.getMap().getTerritoryOrNull("Slovakia Hungary")), is(false));
  }

  @Test
  @Order(4)
  @DisplayName("clearing every potential-enemy ship within three water moves clears the threat")
  void threatDisappearsWithTheShips() {
    final Predicate<Unit> potentialEnemyUnit =
        Matches.unitIsOwnedByAnyOf(ProUtils.getPotentialEnemyPlayers(germans));
    for (final Territory z : waterWithin(norway, 3)) {
      final List<Unit> enemyUnits = z.getUnitCollection().getMatches(potentialEnemyUnit);
      if (!enemyUnits.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(z, enemyUnits));
      }
    }
    assertThat(threatened(norway), is(false));
  }

  @Test
  @Order(5)
  @DisplayName("a transport four water moves out is beyond assault range and does not count")
  void shipBeyondTransportReachDoesNotCount() {
    final Set<Territory> withinFour = new HashSet<>(waterWithin(norway, 4));
    withinFour.removeAll(waterWithin(norway, 3));
    assertThat("the map has water at distance exactly 4", withinFour.isEmpty(), is(false));
    final Territory fourOut = withinFour.iterator().next();
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    data.performChange(
        ChangeFactory.addUnits(
            fourOut,
            data.getUnitTypeList().getUnitTypeOrThrow("transport").createTemp(1, british)));
    assertThat(threatened(norway), is(false));
  }

  @Test
  @Order(6)
  @DisplayName("the same transport at three water moves -- one sail from the beach -- counts")
  void shipAtTransportReachCounts() {
    final Set<Territory> atThree = new HashSet<>(waterWithin(norway, 3));
    atThree.removeAll(waterWithin(norway, 2));
    assertThat("the map has water at distance exactly 3", atThree.isEmpty(), is(false));
    final Territory threeOut = atThree.iterator().next();
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    data.performChange(
        ChangeFactory.addUnits(
            threeOut,
            data.getUnitTypeList().getUnitTypeOrThrow("transport").createTemp(1, british)));
    assertThat(threatened(norway), is(true));
  }

  @Test
  @Order(7)
  @DisplayName("an allied fleet offshore is not a threat")
  void ownAndAlliedShipsDoNotThreaten() {
    // After order-6 the only ship near Norway is the British transport three zones out; a German
    // fleet parked right on the coast must not keep the predicate firing once that is gone.
    final Predicate<Unit> potentialEnemyUnit =
        Matches.unitIsOwnedByAnyOf(ProUtils.getPotentialEnemyPlayers(germans));
    for (final Territory z : waterWithin(norway, 3)) {
      final List<Unit> enemyUnits = z.getUnitCollection().getMatches(potentialEnemyUnit);
      if (!enemyUnits.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(z, enemyUnits));
      }
    }
    final Territory coast = waterWithin(norway, 1).iterator().next();
    data.performChange(
        ChangeFactory.addUnits(
            coast, data.getUnitTypeList().getUnitTypeOrThrow("cruiser").createTemp(1, germans)));
    assertThat(threatened(norway), is(false));
    assertThat("sanity: the German cruiser is on the coast", coast.getUnits().isEmpty(), not(true));
  }
}
