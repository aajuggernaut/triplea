package games.strategy.triplea.ai.pro.logging;

import games.strategy.engine.framework.GameRunner;
import games.strategy.triplea.ai.pro.AbstractProAi;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Class to log messages to log window and console. */
public final class ProLogger {

  /**
   * System property controlling headless log verbosity, as a {@link Level} name. Defaults to {@code
   * FINE}.
   */
  public static final String HEADLESS_LOG_LEVEL_PROPERTY = "triplea.ai.pro.headlessLogLevel";

  /**
   * Headless mirror of the AI's decision log.
   *
   * <p>{@link ProLogUi} renders only into a Swing window that is never constructed in headless
   * mode, so every one of ProAI's log calls is silently discarded during batch evaluation --
   * precisely when its reasoning is most useful. These messages are therefore also emitted through
   * slf4j when running headless.
   *
   * <p>Volume is filtered by {@link #HEADLESS_LOG_LEVEL_PROPERTY}, defaulting to {@code FINE}. The
   * stock {@code FINEST} default across roughly 330 call sites, 15 players and dozens of rounds
   * produces far more output than is readable.
   */
  private static final Logger headlessLog = LoggerFactory.getLogger(ProLogger.class);

  private static final boolean headless =
      Boolean.parseBoolean(System.getProperty(GameRunner.TRIPLEA_HEADLESS, "false"));

  private static final Level headlessLevel = parseHeadlessLevel();

  private ProLogger() {}

  private static Level parseHeadlessLevel() {
    try {
      return Level.parse(System.getProperty(HEADLESS_LOG_LEVEL_PROPERTY, "FINE"));
    } catch (final IllegalArgumentException e) {
      return Level.FINE;
    }
  }

  /**
   * Emits a round and player marker so that a headless log can be navigated by turn. Without this,
   * the log is an undifferentiated stream with no indication of who is deciding what.
   */
  static void headlessRoundMarker(final int round, final String playerName) {
    if (headless) {
      headlessLog.info("===== Round {} : {} =====", round, playerName);
    }
  }

  public static void warn(final String message) {
    log(Level.WARNING, message);
  }

  public static void info(final String message) {
    log(Level.FINE, message);
  }

  public static void debug(final String message) {
    log(Level.FINER, message);
  }

  public static void trace(final String message) {
    log(Level.FINEST, message);
  }

  private static void log(final Level level, final String message) {
    log(level, message, null);
  }

  /**
   * Some notes on using the Pro AI logger: First, to make the logs easily readable even when there
   * are hundreds of lines, I want every considerable step down in the call stack to mean more log
   * message indentation. For example, the base logs in the {@link AbstractProAi} class have no
   * indentation before them, but the base logs in the {@link
   * games.strategy.triplea.ai.pro.ProCombatMoveAi} class will have two spaces inserted at the
   * start, and the level below that, four spaces. In this way, when you're reading the log, you can
   * skip over unimportant areas with speed because of the indentation. Second, I generally want the
   * Fine logs to be messages that run less than 10 times each round, including almost all messages
   * in the Pro AI class. Finest for messages showing details within a method that, for example,
   * returns a value. (So, for example, the NCM_Task method IsTaskWorthwhile() would primarily use
   * finest, as it just returns a boolean, and the logs within it are just for details) Finer for
   * just about everything else. (There's also the SERVER, INFO, etc. levels) Just keep these things
   * in mind while adding new logging code.
   */
  public static void log(final Level level, final String message, final @Nullable Throwable t) {
    // Mirrored before the settings check: those settings are a property of the Swing debug window,
    // which does not exist headlessly.
    if (headless && level.intValue() >= headlessLevel.intValue()) {
      headlessLog.info(formatMessage(message, t, level));
    }
    final ProLogSettings settings = ProLogSettings.loadSettings();
    if (!settings.isLogEnabled()) {
      return; // Skip displaying to settings window if settings window option is turned off
    }
    final Level logDepth = settings.getLogLevel();
    if (logDepth.equals(Level.FINE) && (level.equals(Level.FINER) || level.equals(Level.FINEST))) {
      return; // If the settings window log depth is a higher level than this messages, skip
    }
    if (logDepth.equals(Level.FINER) && level.equals(Level.FINEST)) {
      return;
    }
    ProLogUi.notifyAiLogMessage(formatMessage(message, t, level));
  }

  /**
   * Adds extra spaces to get logs to lineup correctly. (Adds two spaces to fine, one to finer, none
   * to finest, etc.)
   */
  private static String formatMessage(
      final String message, final @Nullable Throwable t, final Level level) {
    final int compensateLength = (level.toString().length() - 4) * 2;
    final StringBuilder builder = new StringBuilder(" ".repeat(Math.max(0, compensateLength)));
    builder.append(message);
    if (t != null) {
      builder.append(" (error: ").append(t.getMessage()).append(")");
    }
    return builder.toString();
  }
}
