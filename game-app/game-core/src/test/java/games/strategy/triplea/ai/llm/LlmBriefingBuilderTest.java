package games.strategy.triplea.ai.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.Test;

class LlmBriefingBuilderTest {
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");

  @Test
  void budgetStatesTheBankAndTheTotalCapacityAcrossAllFactories() {
    final JsonObject briefing = LlmBriefingBuilder.purchase(data, germans, 30);

    assertThat(briefing.get("schema").getAsString(), is("llm-briefing/v0.2"));
    final JsonObject budget = briefing.getAsJsonObject("budget");
    assertThat(budget.get("pusAvailable").getAsInt(), is(30));
    final JsonArray placement = briefing.getAsJsonArray("placement");
    assertThat(placement.size(), greaterThan(0));
    int total = 0;
    for (final var row : placement) {
      total += row.getAsJsonObject().get("maxUnits").getAsInt();
    }
    assertThat(budget.get("totalPlacementCapacity").getAsInt(), is(total));
    // v0.2: a count of factories here was read as a unit limit.
    assertThat(budget.has("placementTerritories"), is(false));
    // v0 consumers (the Python agent's validation) still read these.
    assertThat(briefing.get("pus").getAsInt(), is(30));
  }

  @Test
  void placementRowsComeLargestFirst() {
    final JsonArray placement =
        LlmBriefingBuilder.purchase(data, germans, 30).getAsJsonArray("placement");

    for (int i = 1; i < placement.size(); i++) {
      assertThat(
          placement.get(i - 1).getAsJsonObject().get("maxUnits").getAsInt(),
          greaterThanOrEqualTo(placement.get(i).getAsJsonObject().get("maxUnits").getAsInt()));
    }
  }

  @Test
  void sameGameStateGivesByteIdenticalBriefings() {
    // The agent caches answers by briefing hash, so iteration-order noise would defeat replay.
    final Gson gson = new Gson();

    assertThat(
        gson.toJson(LlmBriefingBuilder.purchase(data, germans, 30)),
        is(gson.toJson(LlmBriefingBuilder.purchase(data, germans, 30))));
  }
}
