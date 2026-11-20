package games.strategy.triplea.ai.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.xml.TestMapGameData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LlmTerritoryHintsTest {
  @TempDir Path temp;
  private final GameData data = TestMapGameData.GLOBAL1940.getGameData();

  @Test
  void validHintsArePlayerSpecificAndFingerprintTheExactBytes() throws Exception {
    final Path file = temp.resolve("hints.json");
    Files.writeString(file, "{\"Germans\": {\"Russia\": 3.0}}\n");
    final LlmTerritoryHints hints = LlmTerritoryHints.load(file, data);
    assertThat(hints.forPlayer("Germans"), is(Map.of("Russia", 3.0)));
    assertThat(hints.forPlayer("Japanese"), is(Map.of()));
    assertThat(hints.fingerprint(), is(HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))).substring(0, 9)));
    assertThrows(UnsupportedOperationException.class, () -> hints.forPlayer("Germans").put("Egypt", 2.0));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "not json", "{Germans: {Russia: 3}}", "{} trailing", "[]", "null",
      "{\"Unknown\":{\"Russia\":3}}", "{\"Germans\":[]}",
      "{\"Germans\":{\"Nowhere\":3}}", "{\"Germans\":{\"Russia\":0}}",
      "{\"Germans\":{\"Russia\":-1}}", "{\"Germans\":{\"Russia\":1e999}}",
      "{\"Germans\":{\"Russia\":NaN}}", "{\"Germans\":{\"Russia\":Infinity}}",
      "{\"Germans\":{\"Russia\":\"3\"}}", "{\"Germans\":{\"Russia\":null}}"
  })
  void rejectsInvalidHints(final String json) throws IOException {
    final Path file = temp.resolve("invalid.json");
    Files.writeString(file, json);
    assertThrows(IllegalArgumentException.class, () -> LlmTerritoryHints.load(file, data));
  }

  @Test
  void missingFileIsAnError() {
    assertThrows(IOException.class, () -> LlmTerritoryHints.load(temp.resolve("missing"), data));
  }

  @Test
  void bridgeLatchesFailureAndDoesNotRetryOrThrow() throws IOException {
    final Path file = temp.resolve("bad.json");
    Files.writeString(file, "bad");
    withProperty(file, () -> {
      final LlmBridgeAi ai = new LlmBridgeAi("Germans", "LLM Bridge (AI)");
      final GamePlayer player = data.getPlayerList().getPlayerId("Germans");
      assertThat(ai.getTerritoryWeights(player), is(Map.of()));
      Files.writeString(file, "{\"Germans\":{\"Russia\":3}}");
      assertThat(ai.getTerritoryWeights(player), is(Map.of()));
    });
  }

  @Test
  void bridgeHintsAreIndependentOfPurchaseSeatsAndStaticAcrossCalls() throws IOException {
    final Path file = temp.resolve("hints.json");
    Files.writeString(file, "{\"Germans\":{\"Russia\":3}}");
    withProperty(file, () -> {
      final LlmBridgeAi ai = new LlmBridgeAi("Germans", "LLM Bridge (AI)");
      final GamePlayer player = data.getPlayerList().getPlayerId("Germans");
      assertThat(ai.getTerritoryWeights(player), is(Map.of("Russia", 3.0)));
      Files.writeString(file, "{}");
      assertThat(ai.getTerritoryWeights(player), is(Map.of("Russia", 3.0)));
      assertThat(ai.getTerritoryWeights(data.getPlayerList().getPlayerId("Japanese")), is(Map.of()));
    });
  }

  private void withProperty(final Path file, final IoAction action) throws IOException {
    final String old = System.getProperty(LlmTerritoryHints.FILE_PROPERTY);
    System.setProperty(LlmTerritoryHints.FILE_PROPERTY, file.toString());
    try {
      action.run();
    } finally {
      if (old == null) {
        System.clearProperty(LlmTerritoryHints.FILE_PROPERTY);
      } else {
        System.setProperty(LlmTerritoryHints.FILE_PROPERTY, old);
      }
    }
  }

  private interface IoAction {
    void run() throws IOException;
  }
}
