package games.strategy.triplea.ai.pro.logging;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * File sink for the AI's full decision log, capturing every {@link ProLogger} message unfiltered.
 *
 * <p>{@link ProLogger}'s ~330 call sites already emit exactly what defect diagnosis needs --
 * per-territory attack values, strength differentials, prune reasons, final candidate lists -- but
 * headlessly they are either discarded or level-filtered into a console that sharded runs throw
 * away. When a trace is active, every message lands here regardless of level, one file per game, so
 * "why did the bot do that" becomes a text search instead of a GUI session.
 *
 * <p>Off unless {@link #begin} was called: scored evaluation runs never activate it, so it costs
 * one volatile null-check per log call there. The message strings themselves are built eagerly by
 * the call sites either way -- that cost predates this class and is unchanged by it.
 *
 * <p>Writing is deliberately free of randomness and of collection iteration, so an active trace
 * cannot perturb a deterministic replay: tracing game X produces the same game X.
 */
public final class PtvTrace {

  private static final Object lock = new Object();
  private static PrintWriter sink; // guarded by lock
  private static volatile boolean active; // fast-path gate, single writer via begin/end

  private PtvTrace() {}

  /** Opens a trace file, creating parent directories. Always pair with {@link #end}. */
  public static void begin(final Path file) throws IOException {
    synchronized (lock) {
      if (sink != null) {
        throw new IllegalStateException("A trace is already active; call end() first.");
      }
      if (file.toAbsolutePath().getParent() != null) {
        Files.createDirectories(file.toAbsolutePath().getParent());
      }
      sink = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
      sink.println("# ptv-trace: full ProLogger stream, unfiltered");
      sink.println("# level tags: [W]arn [F]=fine [D]=finer [T]=finest");
      active = true;
    }
  }

  /** Flushes and closes the active trace, if any. Safe to call when none is active. */
  public static void end() {
    synchronized (lock) {
      active = false;
      if (sink != null) {
        sink.flush();
        sink.close();
        sink = null;
      }
    }
  }

  public static boolean isActive() {
    return active;
  }

  /** A structural marker (round/turn/section header), written without a level tag. */
  public static void marker(final String text) {
    if (!active) {
      return;
    }
    synchronized (lock) {
      if (sink != null) {
        sink.println(text);
      }
    }
  }

  /** Called by {@link ProLogger} for every message when a trace is active. */
  static void write(final Level level, final String formattedMessage) {
    if (!active) {
      return;
    }
    synchronized (lock) {
      if (sink != null) {
        sink.println("[" + levelTag(level) + "] " + formattedMessage);
      }
    }
  }

  private static String levelTag(final Level level) {
    if (level == Level.WARNING) {
      return "W";
    }
    if (level == Level.FINE) {
      return "F";
    }
    if (level == Level.FINER) {
      return "D";
    }
    if (level == Level.FINEST) {
      return "T";
    }
    return level.getName();
  }
}
