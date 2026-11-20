package games.strategy.triplea.ai.llm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import games.strategy.engine.data.GameData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Validated, immutable, name-keyed priorities. The digest identifies the exact source bytes. */
public record LlmTerritoryHints(Map<String, Map<String, Double>> players, String fingerprint) {
  public static final String FILE_PROPERTY = "triplea.ai.llm.hintsFile";

  public static LlmTerritoryHints fromSystemProperty(final GameData data) throws IOException {
    final String file = System.getProperty(FILE_PROPERTY);
    return file == null ? new LlmTerritoryHints(Map.of(), "none") : load(Path.of(file), data);
  }

  public static LlmTerritoryHints load(final Path file, final GameData data) throws IOException {
    final byte[] bytes = Files.readAllBytes(file);
    final JsonElement root;
    try {
      root =
          new GsonBuilder()
              .setStrictness(Strictness.STRICT)
              .create()
              .fromJson(new String(bytes, StandardCharsets.UTF_8), JsonElement.class);
    } catch (final RuntimeException e) {
      throw new IllegalArgumentException("Invalid hints JSON: " + e.getMessage(), e);
    }
    if (root == null || !root.isJsonObject()) {
      throw new IllegalArgumentException("Hints must be a JSON object keyed by player name");
    }
    final Map<String, Map<String, Double>> players = new TreeMap<>();
    for (final var entry : root.getAsJsonObject().entrySet()) {
      if (data.getPlayerList().getPlayerId(entry.getKey()) == null) {
        throw new IllegalArgumentException("Unknown hints player: " + entry.getKey());
      }
      if (!entry.getValue().isJsonObject()) {
        throw new IllegalArgumentException("Hints for " + entry.getKey() + " must be an object");
      }
      final JsonObject territories = entry.getValue().getAsJsonObject();
      final Map<String, Double> weights = new TreeMap<>();
      for (final var territory : territories.entrySet()) {
        if (data.getMap().getTerritoryOrNull(territory.getKey()) == null) {
          throw new IllegalArgumentException("Unknown hints territory: " + territory.getKey());
        }
        final JsonElement value = territory.getValue();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
          throw new IllegalArgumentException("Hint weight must be numeric: " + territory.getKey());
        }
        final double weight = value.getAsDouble();
        if (!Double.isFinite(weight) || weight <= 0) {
          throw new IllegalArgumentException(
              "Hint weight must be finite and positive: " + territory.getKey());
        }
        weights.put(territory.getKey(), weight);
      }
      players.put(entry.getKey(), Map.copyOf(weights));
    }
    try {
      final String hash =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      return new LlmTerritoryHints(Map.copyOf(players), hash.substring(0, 9));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public Map<String, Double> forPlayer(final String name) {
    return players.getOrDefault(name, Map.of());
  }
}
