package org.triplea.ptv.eval;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.random.DeterministicRun;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Plays the same seed twice and reports the <em>first step</em> at which the two games differ.
 *
 * <p>{@code PtvDeterminismTest} answers "do these two runs match?" at the end of N rounds. That was
 * enough to find the first six entropy sources, but it is the wrong tool for the one that remains:
 * a stock 16-round run reproduces itself on only 3 of 7 seeds, and a pass/fail at the end says
 * nothing about where the trouble began. Bisecting by round would cost one full pair of games per
 * probe -- roughly 45 minutes each -- to recover a single bit.
 *
 * <p>This records a board fingerprint after every step instead, so one pair of runs localises the
 * divergence to an exact player and phase. That turns "somewhere in 16 rounds of 15 players" into
 * "Russians, Politics, round 7", which is a code path that can be read.
 *
 * <pre>
 *   java -cp ... org.triplea.ptv.eval.PtvDivergenceFinder --rounds 8 --seed 2000
 * </pre>
 */
@Slf4j
public final class PtvDivergenceFinder {

  private PtvDivergenceFinder() {}

  /** One step's worth of board state, labelled with what had just run. */
  private record StepState(int round, int stepIndex, String label, String fingerprint) {}

  public static void main(final String[] args) throws IOException {
    int rounds = 8;
    long seed = 2000L;
    Path out = null;
    for (int i = 0; i + 1 < args.length; i += 2) {
      switch (args[i]) {
        case "--rounds" -> rounds = Integer.parseInt(args[i + 1]);
        case "--seed" -> seed = Long.parseLong(args[i + 1]);
        case "--out" -> out = Path.of(args[i + 1]);
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    }
    GameTestUtils.setUp();

    // --out plays ONE game and writes its per-step fingerprints, so two separate JVM invocations
    // can be diffed. This matters: the failure being chased showed up between two JVMs, and running
    // both games inside one JVM cannot reproduce anything that varies per process.
    if (out != null) {
      final List<StepState> states = play(seed, rounds);
      try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
        for (final StepState state : states) {
          writer.write("### " + state.label());
          writer.newLine();
          writer.write(state.fingerprint());
        }
      }
      log.info("Wrote {} steps to {}", states.size(), out.toAbsolutePath());
      reportOffThreadUnitIds();
      return;
    }

    log.info("Run 1 of 2 (seed={} rounds={})", seed, rounds);
    final List<StepState> first = play(seed, rounds);
    log.info("Run 2 of 2 (seed={} rounds={})", seed, rounds);
    final List<StepState> second = play(seed, rounds);

    log.info("================ DIVERGENCE ================");
    log.info("run 1 recorded {} steps, run 2 recorded {}", first.size(), second.size());

    final int common = Math.min(first.size(), second.size());
    for (int i = 0; i < common; i++) {
      final StepState a = first.get(i);
      final StepState b = second.get(i);
      if (!a.label().equals(b.label())) {
        log.info("STEP SEQUENCE diverged at index {}: run1={} run2={}", i, a.label(), b.label());
        return;
      }
      if (!a.fingerprint().equals(b.fingerprint())) {
        log.info("FIRST DIVERGENCE at step index {} -> {}", i, a.label());
        log.info(
            "  (the step before this one, {}, still matched)",
            i > 0 ? first.get(i - 1).label() : "n/a");
        reportDiff(a.fingerprint(), b.fingerprint());
        return;
      }
    }
    if (first.size() != second.size()) {
      log.info(
          "Fingerprints matched for all {} shared steps but the runs had different lengths",
          common);
      return;
    }
    log.info("NO DIVERGENCE across {} steps -- this seed reproduces exactly", common);
    reportOffThreadUnitIds();
  }

  /**
   * Reports unit ids handed out from a thread other than the game thread.
   *
   * <p>Non-zero is the smoking gun for the intermittent divergence: the id counter is atomic so the
   * total never varies, but which thread receives which id does, and {@code Unit} hashes on its id.
   * That reorders every {@code HashMap<Unit, ?>} in the AI on some runs and not others.
   */
  private static void reportOffThreadUnitIds() {
    final long offThread = DeterministicRun.offThreadUnitIds();
    log.info("OFF-THREAD unit ids issued: {}", offThread);
    if (offThread > 0) {
      log.info("first off-thread creation was on {}", DeterministicRun.firstOffThreadTrace());
    }
  }

  /** Logs only the territories that differ, since the boards are 361 entries wide. */
  private static void reportDiff(final String a, final String b) {
    final Map<String, String> mapA = parse(a);
    final Map<String, String> mapB = parse(b);
    int shown = 0;
    for (final String territory : mapA.keySet()) {
      final String left = mapA.get(territory);
      final String right = mapB.get(territory);
      if (!left.equals(right) && shown++ < 15) {
        log.info("  {}:", territory);
        log.info("    run 1: {}", left);
        log.info("    run 2: {}", right);
      }
    }
    log.info("  ({} territories differ in total)", countDiffs(mapA, mapB));
  }

  private static long countDiffs(final Map<String, String> a, final Map<String, String> b) {
    return a.keySet().stream().filter(k -> !a.get(k).equals(b.get(k))).count();
  }

  private static Map<String, String> parse(final String fingerprint) {
    final Map<String, String> parsed = new TreeMap<>();
    for (final String entry : fingerprint.split("\n")) {
      final int split = entry.indexOf('=');
      if (split > 0) {
        parsed.put(entry.substring(0, split), entry.substring(split + 1));
      }
    }
    return parsed;
  }

  private static List<StepState> play(final long seed, final int rounds) {
    ServerGame game = null;
    try {
      DeterministicRun.enable(seed);
      game =
          GameTestUtils.setUpGameWithAis("ww2_path_to_victory.xml", PtvDivergenceFinder::allProAi);
      game.setRandomSource(new SeededRandomSource(seed));
      game.setStopGameOnDelegateExecutionStop(true);

      final GameData data = game.getData();
      final List<StepState> states = new ArrayList<>();
      while (!game.isGameOver() && data.getSequence().getRound() <= rounds) {
        // Captured before the step runs: getStepIndex() is a plain read, unlike getStep(), which
        // advances the sequence when the index is out of range.
        final int round = data.getSequence().getRound();
        final int stepIndex = data.getSequence().getStepIndex();
        final GameStep step = data.getSequence().getStep(stepIndex);
        final String label =
            "r"
                + round
                + " "
                + (step == null
                    ? "?"
                    : step.getPlayerId() == null ? "-" : step.getPlayerId().getName())
                + " "
                + (step == null ? "?" : step.getName());
        game.runNextStep();
        // ids-issued leads the board: units diverge before the divergence becomes visible.
        states.add(
            new StepState(
                round,
                stepIndex,
                label + "  [idsIssued=" + DeterministicRun.unitIdsIssued() + "]",
                fingerprint(data)));
      }
      return states;
    } finally {
      if (game != null) {
        game.stopGame();
      }
      DeterministicRun.disable();
    }
  }

  private static String fingerprint(final GameData data) {
    final StringBuilder board = new StringBuilder();
    final Map<String, String> ordered = new TreeMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      final Map<String, Integer> unitCounts = new TreeMap<>();
      for (final Unit unit : t.getUnits()) {
        unitCounts.merge(
            unit.getOwner().getName() + " " + unit.getType().getName(), 1, Integer::sum);
      }
      ordered.put(t.getName(), t.getOwner().getName() + " " + unitCounts);
    }
    ordered.forEach((k, v) -> board.append(k).append('=').append(v).append('\n'));
    return board.toString();
  }

  private static Map<String, PlayerTypes.Type> allProAi(final GameData data) {
    final Map<String, PlayerTypes.Type> types = new HashMap<>();
    data.getPlayerList().getPlayers().forEach(p -> types.put(p.getName(), PlayerTypes.PRO_AI));
    return types;
  }
}
