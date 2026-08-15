package org.triplea.ptv.eval;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the W26 opening-conformance anchors actually fire -- on the unplayed start position, which
 * violates every deadline anchor by construction (France is French, the 98 SZ convoy is afloat,
 * Scotland is garrisoned, Persia is neutral) while the standing anchors conform (Sweden untouched,
 * kamikaze tokens at 6). Silence == conformance in live probes, and the W15/W21 lesson is that a
 * silent check and a dead check are indistinguishable without this test.
 */
class PtvOpeningConformanceTest {

  private static GameData data;

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
  }

  private static List<String> anchorsFiring(final int round) {
    return PtvDefectProbe.openingConformance(data, round, new HashSet<>()).stream()
        .map(PtvDefectProbe.Occurrence::defect)
        .toList();
  }

  @Test
  @DisplayName("every round-1 anchor fires on the unplayed start position")
  void roundOneAnchorsFireOnStartPosition() {
    assertThat(
        anchorsFiring(1),
        containsInAnyOrder(
            "OPEN_FRANCE_G1",
            "OPEN_YUGOSLAVIA_G1",
            "OPEN_FINLAND_G1",
            "OPEN_CONVOY98_G1",
            "OPEN_BALTIC_STRIKE_G1",
            "OPEN_SCOTLAND_STRIP_G1"));
  }

  @Test
  @DisplayName("the round-2 and round-3 anchors fire on the start position at their deadlines")
  void laterDeadlineAnchorsFire() {
    assertThat(anchorsFiring(2), containsInAnyOrder("OPEN_DNG_ANZAC_R2"));
    assertThat(anchorsFiring(3), containsInAnyOrder("OPEN_PERSIA_R3", "OPEN_IRAQ_R3"));
  }

  @Test
  @DisplayName("off-deadline rounds emit nothing: standing anchors conform at the start")
  void standingAnchorsConformAtStart() {
    // Sweden is untouched and Japan holds 6 kamikaze tokens, so a non-deadline round is silent.
    assertThat(anchorsFiring(5), is(empty()));
  }

  @Test
  @DisplayName("the standing anchors report once, then stay quiet")
  void standingAnchorsReportOnce() {
    final HashSet<String> reported = new HashSet<>();
    reported.add("OPEN_SWEDEN_NEUTRALITY");
    reported.add("OPEN_KAMIKAZE_UNSPENT");
    // With both already reported, even a violated standing anchor could not re-fire; on the
    // conforming start board this is just the silent path exercised with state present.
    assertThat(PtvDefectProbe.openingConformance(data, 7, reported), is(empty()));
  }
}
