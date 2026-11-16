package games.strategy.triplea.ai.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.triplea.java.collections.IntegerMap;

class LlmPurchaseInterceptorTest {
  private static final JsonObject BRIEFING = json("{\"schema\":\"llm-briefing/v0\",\"pus\":30}");

  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
  private final IPurchaseDelegate real = mock(IPurchaseDelegate.class);
  private final List<JsonObject> requests = new ArrayList<>();

  @Test
  void requestFollowsTheContract() {
    final IntegerMap<ProductionRule> proposal = proposal();
    interceptor(1, request -> reply("stub", "{\"infantry\":3,\"artillery\":1}")).decide(proposal);

    final JsonObject request = requests.get(0);
    assertThat(request.get("protocol").getAsString(), is("llm-bridge/v1"));
    assertThat(request.get("gameTag").getAsString(), is("seed1-Axis"));
    assertThat(request.get("decisionId").getAsString(), is("seed1-Axis/r3/Germans/purchase"));
    assertThat(request.get("attempt").getAsInt(), is(1));
    assertThat(request.get("briefing"), is(BRIEFING));
    assertThat(request.get("proAiProposal"), is(json("{\"artillery\":1,\"infantry\":3}")));
    assertThat(request.get("previousAnswer").isJsonNull(), is(true));
    assertThat(request.get("previousError").isJsonNull(), is(true));
  }

  @Test
  void answerEqualToTheProposalCommitsProAisOwnMap() {
    // IntegerMap keeps insertion order, which fixes the order units are created in. A stub agent
    // must reproduce a stock game exactly, so ProAi's map object itself has to be committed.
    final IntegerMap<ProductionRule> proposal = proposal();

    final String result =
        interceptor(3, request -> reply("stub", "{\"infantry\":3,\"artillery\":1}"))
            .decide(proposal);

    assertThat(result, is(nullValue()));
    verify(real).purchase(same(proposal));
  }

  @Test
  void differentAnswerIsCommittedInstead() {
    final IntegerMap<ProductionRule> proposal = proposal();

    interceptor(3, request -> reply("llm", "{\"armour\":2,\"infantry\":0}")).decide(proposal);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<IntegerMap<ProductionRule>> committed =
        ArgumentCaptor.forClass(IntegerMap.class);
    verify(real, times(1)).purchase(committed.capture());
    assertThat(LlmPurchaseInterceptor.unitCounts(committed.getValue()), is(json("{\"armour\":2}")));
  }

  @Test
  void unbuildableUnitIsSentBackWithoutAskingTheEngine() {
    final IntegerMap<ProductionRule> proposal = proposal();
    final List<JsonObject> answers =
        List.of(
            reply("llm", "{\"death_star\":1}").body(),
            reply("llm", "{\"infantry\":3,\"artillery\":1}").body());

    interceptor(3, request -> LlmAgentClient.Reply.ok(answers.get(requests.size() - 1), 10))
        .decide(proposal);

    assertThat(requests.size(), is(2));
    final JsonObject second = requests.get(1);
    assertThat(second.get("attempt").getAsInt(), is(2));
    assertThat(second.get("previousAnswer"), is(json("{\"death_star\":1}")));
    assertThat(second.get("previousError").getAsString(), containsString("death_star"));
    verify(real, times(1)).purchase(any());
    verify(real).purchase(same(proposal));
  }

  @Test
  void engineRejectionsAreSentBackThenProAisBuyIsUsed() {
    final IntegerMap<ProductionRule> proposal = proposal();
    when(real.purchase(argThat(map -> map != proposal))).thenReturn("Not enough resources");

    final String result =
        interceptor(2, request -> reply("llm", "{\"armour\":50}")).decide(proposal);

    assertThat(result, is(nullValue()));
    assertThat(requests.size(), is(2));
    assertThat(requests.get(1).get("previousError").getAsString(), is("Not enough resources"));
    verify(real, times(3)).purchase(any());
    verify(real).purchase(same(proposal));
  }

  @Test
  void agentDownFallsBackAtOnceWithoutRetrying() {
    final IntegerMap<ProductionRule> proposal = proposal();

    interceptor(3, request -> LlmAgentClient.Reply.failed("connection refused", 1))
        .decide(proposal);

    assertThat(requests.size(), is(1));
    verify(real, times(1)).purchase(any());
    verify(real).purchase(same(proposal));
  }

  @Test
  void exceptionsNeverEscapeAndProAisBuyIsUsed() {
    final IntegerMap<ProductionRule> proposal = proposal();

    interceptor(
            3,
            request -> {
              throw new IllegalStateException("boom");
            })
        .decide(proposal);

    verify(real).purchase(same(proposal));
  }

  @Test
  void briefingFailureFallsBackWithoutCallingTheAgent() {
    final IntegerMap<ProductionRule> proposal = proposal();
    final LlmPurchaseInterceptor interceptor =
        new LlmPurchaseInterceptor(
            real,
            germans,
            3,
            3,
            request -> {
              requests.add(request);
              return reply("llm", "{}");
            },
            () -> {
              throw new IllegalStateException("briefing broke");
            },
            "seed1-Axis");

    interceptor.decide(proposal);

    assertThat(requests.size(), is(0));
    verify(real).purchase(same(proposal));
  }

  @Test
  void emptyBuyIsLegal() {
    final IntegerMap<ProductionRule> proposal = proposal();

    interceptor(3, request -> reply("llm", "{}")).decide(proposal);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<IntegerMap<ProductionRule>> committed =
        ArgumentCaptor.forClass(IntegerMap.class);
    verify(real, times(1)).purchase(committed.capture());
    assertThat(committed.getValue().isEmpty(), is(true));
  }

  @Test
  void countsMustBeWholeAndNonNegative() {
    final LlmPurchaseInterceptor interceptor = interceptor(1, request -> reply("llm", "{}"));

    assertThat(interceptor.toRules(json("{\"infantry\":1.5}")).error(), is(notNullValue()));
    assertThat(interceptor.toRules(json("{\"infantry\":-1}")).error(), is(notNullValue()));
    assertThat(interceptor.toRules(json("{\"infantry\":\"3\"}")).error(), is(notNullValue()));
    assertThat(interceptor.toRules(json("{\"infantry\":3.0}")).error(), is(nullValue()));
  }

  @Test
  void otherDelegateMethodsPassThrough() {
    when(real.getName()).thenReturn("purchase");

    final IPurchaseDelegate proxy = interceptor(1, request -> reply("llm", "{}")).proxy();

    assertThat(proxy.getName(), is("purchase"));
    verify(real, never()).purchase(any());
  }

  private LlmPurchaseInterceptor interceptor(
      final int maxAttempts, final Function<JsonObject, LlmAgentClient.Reply> agent) {
    return new LlmPurchaseInterceptor(
        real,
        germans,
        3,
        maxAttempts,
        request -> {
          requests.add(request.deepCopy());
          return agent.apply(request);
        },
        () -> BRIEFING,
        "seed1-Axis");
  }

  /** Artillery first, then infantry: deliberately not frontier order. */
  private IntegerMap<ProductionRule> proposal() {
    final IntegerMap<ProductionRule> proposal = new IntegerMap<>();
    proposal.add(rule("artillery"), 1);
    proposal.add(rule("infantry"), 3);
    return proposal;
  }

  private ProductionRule rule(final String unit) {
    return germans.getProductionFrontier().getRules().stream()
        .filter(
            r ->
                r.getResults().keySet().stream()
                    .anyMatch(k -> k instanceof UnitType t && t.getName().equals(unit)))
        .findFirst()
        .orElseThrow();
  }

  private static LlmAgentClient.Reply reply(final String source, final String buy) {
    final JsonObject body = new JsonObject();
    body.add("buy", json(buy));
    body.addProperty("source", source);
    return LlmAgentClient.Reply.ok(body, 10);
  }

  private static JsonObject json(final String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }
}
