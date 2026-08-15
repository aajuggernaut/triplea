package org.triplea.ptv.eval;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.ai.pro.util.ProMatches;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the W28 claim condition fires -- and only where the map says it may. The predicate keys
 * off the engine's own relationship flag ({@code Custodianship}: allied archetype + {@code
 * canTakeOverOwnedTerritory}), so the cases pin the real PTV relationships: the Dutch East Indies
 * are claimable by ANZAC and the British, already-claimed Borneo (UK_Pacific-owned) is not,
 * ordinary allied ground is not, and no Axis power can claim anything -- the structural-inertness
 * fact the Tier-2 design leans on.
 */
class PtvCustodialClaimTest {

  private static GameData data;
  private static GamePlayer anzac;
  private static GamePlayer british;
  private static GamePlayer germans;

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
    anzac = data.getPlayerList().getPlayerId("ANZAC");
    british = data.getPlayerList().getPlayerId("British");
    germans = data.getPlayerList().getPlayerId("Germans");
  }

  private static boolean claimable(final GamePlayer player, final String territory) {
    return ProMatches.territoryIsClaimableCustodial(data, player)
        .test(data.getMap().getTerritoryOrNull(territory));
  }

  @Test
  @DisplayName("the Dutch East Indies are claimable by ANZAC and the British at the start")
  void dutchEastIndiesAreClaimable() {
    for (final String t : List.of("Dutch New Guinea", "Java", "Sumatra", "Celebes")) {
      assertThat(t + " for ANZAC", claimable(anzac, t), is(true));
      assertThat(t + " for British", claimable(british, t), is(true));
    }
  }

  @Test
  @DisplayName("already-claimed and ordinary allied ground is not claimable")
  void claimedAndOrdinaryAlliedGroundIsNot() {
    // Borneo starts UK_Pacific-owned -- the custodial flag lives on the Dutch relationship, so
    // once claimed the territory stops qualifying.
    assertThat(claimable(anzac, "Borneo"), is(false));
    // Ordinary allies without the takeover flag.
    assertThat(claimable(anzac, "United Kingdom"), is(false));
    // Own territory.
    assertThat(claimable(anzac, "New Guinea"), is(false));
  }

  @Test
  @DisplayName("Friendly_Neutral carries the same flag: the Axis can claim their pro-neutrals")
  void axisCanClaimTheirFriendlyNeutrals() {
    // Discovered by this test's first run: the Finland G1 walk-in is the same custodial
    // mechanic. Stock executes it only because Finland carries defense value (Russian border);
    // the DEI carries none, which is why it alone sat unclaimed.
    for (final String t : List.of("Finland", "Bulgaria", "Iraq")) {
      assertThat(t + " for Germans", claimable(germans, t), is(true));
    }
  }

  @Test
  @DisplayName("Sweden is Strict_Neutral and claimable by nobody")
  void swedenIsNeverClaimable() {
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (ProMatches.territoryIsClaimableCustodial(data, p)
          .test(data.getMap().getTerritoryOrNull("Sweden"))) {
        throw new AssertionError("Sweden must never be claimable, but is for " + p.getName());
      }
    }
  }

  @Test
  @DisplayName("the probe counts the unclaimed DEI on the start board")
  void probeCountsUnclaimedCustodialTerritories() {
    final List<PtvDefectProbe.Occurrence> found =
        PtvDefectProbe.unclaimedCustodialTerritories(data, 1);
    assertThat(found.size(), greaterThanOrEqualTo(4));
    assertThat(
        found.stream().map(o -> o.detail().split(" claimable")[0]).toList(),
        hasItem("Dutch New Guinea"));
    assertThat(found.stream().allMatch(o -> o.defect().equals("CUSTODIAL_UNCLAIMED")), is(true));
  }
}
