package games.strategy.triplea.ai.pro.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProTerritoryHintsPullTest {
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
  private final Territory russia = data.getMap().getTerritoryOrNull("Russia");
  private final Territory egypt = data.getMap().getTerritoryOrNull("Egypt");

  @Test
  void weightsExistingPullOnlyForTheHintedPlayerAndEmptyMapIsExact() {
    final ProData plain = proData(Map.of());
    final ProData hinted = proData(Map.of("Russia", 3.0));
    final Map<Territory, Double> baseline = pull(plain, germans, List.of());
    assertThat(pull(hinted, germans, List.of()).get(russia), is(3 * baseline.get(russia)));
    assertThat(pull(hinted, japanese, List.of()), is(pull(plain, japanese, List.of())));
    final ProAi stock = new ProAi("Germans", "Hard AI");
    stock.getProData().initializeSimulation(stock, data, germans);
    assertThat(pull(stock.getProData(), germans, List.of()), is(baseline));
  }

  @Test
  void factorylessEnemyJoinsPullWithProductionFormulaButAttackedAlliedAndWaterDoNot() {
    final Map<Territory, Double> plain = pull(proData(Map.of()), germans, List.of());
    assertThat(plain.containsKey(egypt), is(false));
    final ProData hinted = proData(Map.of("Egypt", 3.0, "Germany", 3.0, "112 Sea Zone", 3.0));
    final Map<Territory, Double> actual = pull(hinted, germans, List.of());
    final int landMass = ProTerritoryValueUtils.findMaxLandMassSize(germans);
    final int reach = 1 + data.getMap().getNeighbors(
        egypt, 6, ProMatches.territoryCanPotentiallyMoveLandUnits(germans)).size();
    final double value = Math.sqrt(TerritoryAttachment.getProduction(egypt)) * 32 * reach / landMass;
    assertThat(actual.get(egypt), closeTo(value * 3, 1e-12));
    assertThat(actual.size(), is(plain.size() + 1));
    assertThat(pull(hinted, germans, List.of(egypt)).containsKey(egypt), is(false));
    assertThat(pull(hinted, japanese, List.of()).containsKey(egypt), is(false));
  }

  @Test
  void nameKeyedHintsAreReadAgainForSimulationData() {
    final ProData hinted = proData(Map.of("Russia", 3.0));
    // A separately parsed map supplies different Territory and GamePlayer objects, as copies do.
    final GameData copy = TestMapGameData.GLOBAL1940.getGameData();
    hinted.initializeSimulation(hinted.getProAi(), copy, copy.getPlayerList().getPlayerId("Germans"));
    assertThat(hinted.getTerritoryWeight(hinted.getPlayer(), copy.getMap().getTerritoryOrNull("Russia")), is(3.0));
    assertThat(hinted.getTerritoryWeight(copy.getPlayerList().getPlayerId("Japanese"), russia), is(1.0));
  }

  private Map<Territory, Double> pull(final ProData proData, final GamePlayer player, final List<Territory> attacking) {
    return ProTerritoryValueUtils.findEnemyCapitalsAndFactoriesValue(
        proData, player, ProTerritoryValueUtils.findMaxLandMassSize(player), List.of(), attacking);
  }

  private ProData proData(final Map<String, Double> weights) {
    final ProAi ai = new ProAi("Germans", "Hard AI") {
      @Override
      protected Map<String, Double> getTerritoryWeights(final GamePlayer player) {
        return weights;
      }
    };
    ai.getProData().initializeSimulation(ai, data, germans);
    return ai.getProData();
  }
}
