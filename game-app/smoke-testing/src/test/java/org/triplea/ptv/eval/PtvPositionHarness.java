package org.triplea.ptv.eval;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.random.DeterministicRun;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.PtvFeature;
import games.strategy.triplea.ai.pro.logging.PtvTrace;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs one AI turn (or phase) against a saved position and reports what the AI decided -- the W23
 * position harness. Iterating on a patch drops from a ~20-minute game to under a minute, and the
 * full unfiltered decision trace says <em>why</em>: whether a target was never a candidate, was a
 * candidate pruned by value, or was pruned by odds, which are three different fixes.
 *
 * <pre>
 *   java @argfile org.triplea.ptv.eval.PtvPositionHarness --save path/to/position.tsvg
 *       [--mode turn|phase|steps]   default turn: run until the acting player changes
 *       [--phase combatMove]        with --mode phase: run through the steps whose name contains this
 *       [--steps N]                 with --mode steps: run exactly N steps
 *       [--player-type pro|ptv]     AI assigned to every player (ptv reads -Dtriplea.ai.ptv.features)
 *       [--seed N]                  DeterministicRun seed, default 1000 -- same seed, same decisions
 *       [--trace PATH]              default: alongside the save, .trace.txt appended
 * </pre>
 *
 * <p>Positions come from two places: saves of the humans' games ({@code positions/corpus}), and
 * bot-game snapshots written by {@code PtvEvalRunner --snapshot-rounds}. Both load here because
 * saves are plain serialized {@code GameData}.
 */
@Slf4j
public final class PtvPositionHarness {

  private static final int STEP_SAFETY_CAP = 400;

  private PtvPositionHarness() {}

  public static void main(final String[] args) throws IOException {
    System.setProperty("triplea.ai.ptv.eval", "true");
    final Options options = Options.parse(args);
    GameTestUtils.setUp();

    final Path tracePath =
        options.trace != null
            ? options.trace
            : options.save.resolveSibling(options.save.getFileName() + ".trace.txt");

    DeterministicRun.enable(options.seed);
    PtvTrace.begin(tracePath);
    ServerGame game = null;
    try {
      game =
          GameTestUtils.setUpGameFromSave(
              options.save,
              data ->
                  data.getPlayerList().getPlayers().stream()
                      .collect(
                          HashMap::new,
                          (m, p) -> m.put(p.getName(), options.playerType()),
                          HashMap::putAll));
      game.setRandomSource(new SeededRandomSource(options.seed));
      game.setStopGameOnDelegateExecutionStop(true);

      final GameData data = game.getData();
      final String startStep = data.getSequence().getStep().getName();
      final String startPlayer = playerName(data);
      final int startRound = data.getSequence().getRound();
      final Snapshot before = Snapshot.of(data);

      PtvTrace.marker(
          "===== HARNESS: "
              + options.save.getFileName()
              + " | round "
              + startRound
              + " | step "
              + startStep
              + " | acting "
              + startPlayer
              + " | mode "
              + options.mode
              + " =====");
      log.info(
          "Position: round {} step {} acting {} | mode={} playerType={} seed={} features={}",
          startRound,
          startStep,
          startPlayer,
          options.mode,
          options.playerTypeName,
          options.seed,
          PtvFeature.selected());

      int stepsRun = 0;
      boolean phaseEntered = false;
      // Turn mode runs one player's whole turn. A snapshot taken at a round boundary sits on
      // playerless init steps, so the turn to run is the first real player's that begins at or
      // after the current step -- bind to it when it appears, and treat trailing playerless
      // steps (end-of-round bookkeeping) as part of the turn rather than a stop signal.
      String turnPlayer = startPlayer;
      while (!game.isGameOver() && stepsRun < STEP_SAFETY_CAP) {
        final String stepName = data.getSequence().getStep().getName();
        final String current = playerName(data);
        if (options.mode.equals("turn")) {
          if (turnPlayer.equals("<none>") && !current.equals("<none>")) {
            turnPlayer = current;
          } else if (!turnPlayer.equals("<none>")
              && !current.equals("<none>")
              && !current.equals(turnPlayer)) {
            break;
          }
        }
        if (options.mode.equals("phase")) {
          final boolean matches =
              stepName.toLowerCase(Locale.ROOT).contains(options.phase.toLowerCase(Locale.ROOT));
          if (phaseEntered && !matches) {
            break;
          }
          if (matches) {
            phaseEntered = true;
          }
        }
        if (options.mode.equals("steps") && stepsRun >= options.steps) {
          break;
        }
        game.runNextStep();
        stepsRun++;
      }
      if (stepsRun >= STEP_SAFETY_CAP) {
        log.warn("Stopped at the {}-step safety cap -- check the mode arguments.", STEP_SAFETY_CAP);
      }

      final Snapshot after = Snapshot.of(data);
      report(data, before, after, stepsRun, tracePath);
    } finally {
      if (game != null) {
        game.stopGame();
      }
      ProAi.drainSharedCalculator().join();
      PtvTrace.end();
      DeterministicRun.disable();
    }
  }

  private static String playerName(final GameData data) {
    final GamePlayer player = data.getSequence().getStep().getPlayerId();
    return player == null ? "<none>" : player.getName();
  }

  private static void report(
      final GameData data,
      final Snapshot before,
      final Snapshot after,
      final int stepsRun,
      final Path tracePath) {
    log.info(
        "Ran {} steps; now at round {} step {} acting {}",
        stepsRun,
        data.getSequence().getRound(),
        data.getSequence().getStep().getName(),
        playerName(data));
    log.info(
        "Alliance position (VC/prod): Axis {}/{} -> {}/{} | Allies {}/{} -> {}/{}",
        before.axisVc,
        before.axisProd,
        after.axisVc,
        after.axisProd,
        before.alliesVc,
        before.alliesProd,
        after.alliesVc,
        after.alliesProd);

    final Map<String, String[]> ownershipChanges = new TreeMap<>();
    for (final Map.Entry<String, String> entry : before.owner.entrySet()) {
      final String now = after.owner.get(entry.getKey());
      if (!entry.getValue().equals(now)) {
        ownershipChanges.put(entry.getKey(), new String[] {entry.getValue(), now});
      }
    }
    if (ownershipChanges.isEmpty()) {
      log.info("No territory changed hands.");
    } else {
      log.info("{} territories changed hands:", ownershipChanges.size());
      ownershipChanges.forEach((t, v) -> log.info("  {}: {} -> {}", t, v[0], v[1]));
    }

    final List<Territory> pending =
        List.copyOf(
            data.getBattleDelegate().getBattleTracker().getPendingBattleSitesWithoutBombing());
    if (pending.isEmpty()) {
      log.info("No battles pending.");
    } else {
      log.info("{} battles pending: {}", pending.size(), pending);
    }
    log.info("Full decision trace: {}", tracePath.toAbsolutePath());
  }

  /** Per-alliance material and full territory-ownership map, for before/after diffing. */
  private static final class Snapshot {
    final int axisVc;
    final int axisProd;
    final int alliesVc;
    final int alliesProd;
    final Map<String, String> owner = new LinkedHashMap<>();

    private Snapshot(final GameData data) {
      final Set<GamePlayer> axis = data.getAllianceTracker().getPlayersInAlliance("Axis");
      final Set<GamePlayer> allies = data.getAllianceTracker().getPlayersInAlliance("Allies");
      int avc = 0;
      int aprod = 0;
      int lvc = 0;
      int lprod = 0;
      for (final Territory t : data.getMap().getTerritories()) {
        owner.put(t.getName(), t.getOwner().getName());
        final var ta = TerritoryAttachment.get(t).orElse(null);
        if (ta == null) {
          continue;
        }
        if (axis.contains(t.getOwner())) {
          avc += ta.getVictoryCity();
          aprod += ta.getProduction();
        } else if (allies.contains(t.getOwner())) {
          lvc += ta.getVictoryCity();
          lprod += ta.getProduction();
        }
      }
      this.axisVc = avc;
      this.axisProd = aprod;
      this.alliesVc = lvc;
      this.alliesProd = lprod;
    }

    static Snapshot of(final GameData data) {
      return new Snapshot(data);
    }
  }

  private static final class Options {
    private Path save;
    private String mode = "turn";
    private String phase = null;
    private int steps = 1;
    private String playerTypeName = "pro";
    private long seed = 1000L;
    private Path trace = null;

    PlayerTypes.Type playerType() {
      return switch (playerTypeName.toLowerCase(Locale.ROOT)) {
        case "pro" -> PlayerTypes.PRO_AI;
        case "ptv" -> PlayerTypes.PTV_AI;
        default ->
            throw new IllegalArgumentException(
                "Unknown player type '" + playerTypeName + "' (expected pro|ptv)");
      };
    }

    static Options parse(final String[] args) {
      final Options o = new Options();
      for (int i = 0; i < args.length; i += 2) {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("Missing value for argument: " + args[i]);
        }
        final String value = args[i + 1];
        switch (args[i]) {
          case "--save" -> o.save = Path.of(value);
          case "--mode" -> o.mode = value.toLowerCase(Locale.ROOT);
          case "--phase" -> o.phase = value;
          case "--steps" -> o.steps = Integer.parseInt(value);
          case "--player-type" -> o.playerTypeName = value;
          case "--seed" -> o.seed = Long.parseLong(value);
          case "--trace" -> o.trace = Path.of(value);
          default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
        }
      }
      if (o.save == null) {
        throw new IllegalArgumentException("--save is required");
      }
      if (!List.of("turn", "phase", "steps").contains(o.mode)) {
        throw new IllegalArgumentException("Unknown mode '" + o.mode + "' (turn|phase|steps)");
      }
      if (o.mode.equals("phase") && (o.phase == null || o.phase.isBlank())) {
        throw new IllegalArgumentException("--mode phase requires --phase <substring>");
      }
      return o;
    }
  }
}
