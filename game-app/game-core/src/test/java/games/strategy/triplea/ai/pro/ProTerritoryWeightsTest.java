package games.strategy.triplea.ai.pro;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProTerritoryWeightsTest {
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
  private final Territory russia = data.getMap().getTerritoryOrNull("Russia");

  @Test
  void attackPriorityIsWeightedOnlyForTheHintedPlayer() throws Exception {
    final ProAi plain = ai(Map.of());
    final ProAi hinted = ai(Map.of("Russia", 3.0));
    assertThat(attackValue(hinted, germans), is(3 * attackValue(plain, germans)));
    assertThat(attackValue(hinted, japanese), is(attackValue(plain, japanese)));
    assertThat(hinted.getProData().getTerritoryWeight(japanese, russia), is(1.0));
  }

  @Test
  void emptyHintsAreANoOp() throws Exception {
    final ProAi stock = new ProAi("Germans", "Hard AI");
    stock.getProData().initializeSimulation(stock, data, germans);
    final ProAi empty = ai(Map.of());
    assertThat(empty.getProData().hasTerritoryWeights(germans), is(false));
    assertThat(Double.doubleToLongBits(attackValue(empty, germans)),
        is(Double.doubleToLongBits(attackValue(stock, germans))));
  }

  private ProAi ai(final Map<String, Double> weights) {
    final ProAi ai = new ProAi("Germans", "Hard AI") {
      @Override
      protected Map<String, Double> getTerritoryWeights(final GamePlayer player) {
        return weights;
      }
    };
    ai.getProData().initializeSimulation(ai, data, germans);
    return ai;
  }

  private double attackValue(final ProAi ai, final GamePlayer forPlayer) throws Exception {
    final ProCombatMoveAi combat = new ProCombatMoveAi(ai);
    final Field field = ProCombatMoveAi.class.getDeclaredField("data");
    field.setAccessible(true);
    field.set(combat, data);
    final ProTerritory target = new ProTerritory(russia, ai.getProData());
    target.setMaxBattleResult(new ProBattleResult(100, 100, true, List.of(), List.of(), 1));
    combat.prioritizeAttackOptions(forPlayer, new ArrayList<>(List.of(target)));
    return target.getValue();
  }
}
