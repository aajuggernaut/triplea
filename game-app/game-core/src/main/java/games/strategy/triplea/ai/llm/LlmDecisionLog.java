package games.strategy.triplea.ai.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Append-only JSON-lines log of every strategic decision the LLM bridge sees: the briefing, and
 * (once an LLM is wired in) its answer and the outcome. One file per game, named by the game tag.
 *
 * <p>Off unless {@value #LOG_DIR_PROPERTY} names a directory, in which case {@link LlmBridgeAi}
 * plays exactly like stock ProAi and writes nothing. A write failure is logged and swallowed: the
 * log is a diagnostic artifact and must never stop a game.
 */
@Slf4j
public final class LlmDecisionLog {
  /** System property naming the directory decision logs are written to. */
  public static final String LOG_DIR_PROPERTY = "triplea.ai.llm.logDir";

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private static volatile String gameTag = "game";

  private LlmDecisionLog() {}

  /**
   * Names the file the current game's decisions go to. Batch runners call this before each game
   * (e.g. {@code seed3000-Axis}) so that games in one JVM do not share a log.
   */
  public static void setGameTag(final String tag) {
    gameTag = tag.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  static boolean enabled() {
    return logDir() != null;
  }

  @Nullable
  private static Path logDir() {
    final String dir = System.getProperty(LOG_DIR_PROPERTY);
    return dir == null || dir.isBlank() ? null : Path.of(dir);
  }

  static synchronized void write(final JsonObject record) {
    final Path dir = logDir();
    if (dir == null) {
      return;
    }
    record.addProperty("gameTag", gameTag);
    final Path file = dir.resolve(gameTag + ".jsonl");
    try {
      Files.createDirectories(dir);
      try (Writer out =
          Files.newBufferedWriter(
              file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
        out.write(GSON.toJson(record));
        out.write('\n');
      }
    } catch (final IOException e) {
      log.error("Could not append LLM decision record to {}", file, e);
    }
  }
}
