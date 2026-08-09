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
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.EndRoundDelegate;
import games.strategy.triplea.delegate.Matches;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.collections.CollectionUtils;

/**
 * Batch runner for headless WW2 Path to Victory games, used to compare a candidate AI against a
 * baseline AI.
 *
 * <p>Usage (from the smoke-testing project directory):
 *
 * <pre>
 *   ./gradlew :smoke-testing:ptvEval -PptvEvalArgs="--games 20 --candidate pro --baseline weak"
 * </pre>
 *
 * <p><b>Win/loss is not the metric.</b> PTV enables only {@code Triggered Victory}: Total Victory,
 * Honorable Surrender, Projection of Power and Economic Victory are all absent from the map XML and
 * default to false. A game ends only when a map-defined victory trigger fires, which bot-vs-bot
 * play essentially never reaches -- across 16 measured games, zero ended in a victory, including
 * games where the loser was reduced to zero units. Games are therefore scored on material position
 * (victory cities, production, units held per side).
 *
 * <p><b>The run is scored at {@value #PRIMARY_METRIC_ROUND}, not at the end.</b> Endpoint scoring
 * saturates and is nearly all noise: a stock Axis ends six games in eight at exactly zero victory
 * cities and one at twenty, giving an SD of 6.9 around a mean of 3.0. Detecting a +2 VC improvement
 * there would need ~191 games per arm; at round 15 it needs ~26. Material is recorded at every
 * fifth round as well, so a bot that merely collapses later still registers as different.
 *
 * <p><b>Runs are reproducible, but only because {@code --deterministic} is on by default.</b>
 * Seeding the game's dice alone is not enough: the AI's own battle simulations drew from unseeded
 * random sources and were split across a core-count-dependent number of workers, so replaying seeds
 * 2000-2007 on 6 cores and then on 8 gave different results -- seed 2003 inverted outright. {@link
 * games.strategy.engine.random.DeterministicRun} pins those sources, plus the random unit ids that
 * made every {@code HashSet<Unit>} iterate differently each run. This matters for more than
 * tidiness: with it, a patched-vs-stock comparison is <em>paired</em>, because both arms play an
 * identical game until the patch first changes a decision.
 *
 * <p><b>Do not run two instances of this in one JVM.</b> {@code ProAi} holds a {@code static final
 * ConcurrentBattleCalculator} shared by every ProAi instance, and rebinds its game data in {@code
 * prepareData}. Concurrent games would simulate battles against each other's board state. Games are
 * therefore run sequentially here; to use more cores, launch several processes with disjoint {@code
 * --seeds} and merge the CSVs. {@code --deterministic} pins the battle calculator's pool to a fixed
 * size rather than serialising it, so throughput is unchanged.
 */
@Slf4j
public final class PtvEvalRunner {

  private static final String PTV_XML = "ww2_path_to_victory.xml";
  private static final String AXIS = "Axis";
  private static final String ALLIES = "Allies";

  /**
   * The round the run is scored on.
   *
   * <p>Not the endpoint, which is close to useless: a stock Axis ends 6 games in 8 at exactly zero
   * victory cities and one at twenty, so its SD (6.9) dwarfs its mean (3.0) and detecting a +2 VC
   * improvement would take ~191 games per arm. Round 15 needs ~26. Round 5 is tighter still but
   * correlates with the endpoint at r = 0.01 -- nothing has diverged that early, so it measures
   * nothing. Round 20 predicts slightly better than 15 but costs ~3x the games. Full working in
   * {@code notes/003-measurement-noise-floor.md} of the companion repo.
   */
  private static final int PRIMARY_METRIC_ROUND = 15;

  /**
   * The realized AI-assignment histogram from the most recent game, for the run footer. Identical
   * across a run's games (same assigner, same map); previously it existed only in the console log,
   * which bare-JVM shard launches discard.
   */
  private static volatile String lastAssignment = null;

  /**
   * Rounds at which material position is sampled mid-game: every fifth round the run reaches.
   * Derived rather than fixed so that shortening {@code --max-rounds} does not silently leave
   * checkpoints that can never fire.
   */
  private static List<Integer> checkpointsFor(final int maxRounds) {
    return IntStream.rangeClosed(1, maxRounds / 5).map(i -> i * 5).boxed().toList();
  }

  private PtvEvalRunner() {}

  public static void main(final String[] args) throws IOException {
    // Marks this JVM as a batch eval: ServerGame's stalled-teardown path logs and continues
    // instead of System.exit(), which would kill every remaining game in the batch.
    System.setProperty("triplea.ai.ptv.eval", "true");
    final Options options = Options.parse(args);
    GameTestUtils.setUp();

    final PlayerTypes.Type candidate = bot(options.candidate);
    final PlayerTypes.Type baseline = bot(options.baseline);
    if (candidate == baseline && !options.allowSelf) {
      // The default arguments are pro-vs-pro, so forgetting --candidate silently produced a
      // self-comparison whose numbers looked perfectly ordinary. Three such files shipped.
      throw new IllegalArgumentException(
          "candidate and baseline are both '"
              + options.candidate
              + "' -- a self-comparison measures nothing. Pass --allow-self true if this is"
              + " deliberate (e.g. a reproducibility census).");
    }
    final List<String> sides = options.sides();
    final List<Long> seeds = options.seeds();
    final List<Integer> checkpointRounds = checkpointsFor(options.maxRounds);

    if (options.maxRounds < PRIMARY_METRIC_ROUND) {
      log.warn(
          "--max-rounds {} is below the primary metric round {}, so this run cannot be scored on"
              + " it. Raise --max-rounds to at least {}.",
          options.maxRounds,
          PRIMARY_METRIC_ROUND,
          PRIMARY_METRIC_ROUND + 1);
    }

    if (Files.exists(options.out) && !options.overwrite) {
      // The default --out is a fixed path; without this, rerunning truncated a complete CSV into
      // a shorter one with no trace of the loss.
      throw new IllegalArgumentException(
          "Output file already exists: "
              + options.out.toAbsolutePath()
              + " -- refusing to overwrite a previous run. Pass --overwrite true or a new --out.");
    }
    Files.createDirectories(options.out.toAbsolutePath().getParent());
    final List<GameOutcome> outcomes = new ArrayList<>();

    try (PrintWriter csv =
        new PrintWriter(Files.newBufferedWriter(options.out, StandardCharsets.UTF_8))) {
      // Provenance is read from inside the JVM, not echoed from the command line: the CSV states
      // what actually ran. Bare-JVM shard launches drop Gradle's defaults, so echoed intentions
      // and reality have already diverged once (a "single-feature" run whose header could not
      // prove it was not the whole stack).
      csv.println("# ptv-eval run " + Instant.now());
      csv.println("# map=WW2 Path to Victory v6.4 (commit 45354ca)");
      csv.println("# engine=" + engineBuildId());
      csv.println("# candidate=" + options.candidate + " baseline=" + options.baseline);
      csv.println("# side=" + options.side);
      csv.println("# features=" + PtvFeature.selected());
      csv.println("# workers=" + DeterministicRun.workerCount());
      csv.println(
          "# heapMaxMb="
              + Runtime.getRuntime().maxMemory() / (1024 * 1024)
              + " cores="
              + Runtime.getRuntime().availableProcessors());
      csv.println("# maxRounds=" + options.maxRounds + " games=" + (seeds.size() * sides.size()));
      csv.println(
          "# seeds=" + seeds.stream().map(String::valueOf).collect(Collectors.joining(",")));
      csv.println("# checkpoints=" + checkpointRounds);
      csv.println("# primaryMetricRound=" + PRIMARY_METRIC_ROUND);
      csv.println("# deterministic=" + options.deterministic);
      csv.println(GameOutcome.csvHeader());
      csv.flush();

      for (final String side : sides) {
        for (final long seed : seeds) {
          final GameOutcome outcome =
              runOneGame(
                  seed,
                  side,
                  candidate,
                  baseline,
                  options.maxRounds,
                  options.deterministic,
                  checkpointRounds);
          outcomes.add(outcome);
          csv.println(outcome.toCsvRow());
          csv.flush();
          if (!outcome.diag().clean()) {
            log.warn(
                "DIAG-CONTAMINATED game (seed={}, side={}): {} -- the AI decided on missing or"
                    + " corrupted odds at some point; do not score this row.",
                seed,
                side,
                outcome.diag());
          }
          log.info(
              "seed={} candidateSide={} -> {} | PRIMARY r{} VC {} share {}% | "
                  + "VC {}v{} prod {}v{} units {}v{} | VC path {} | {} rounds, {} ms",
              seed,
              side,
              outcome.outcome(),
              PRIMARY_METRIC_ROUND,
              outcome.primaryVictoryCities(),
              fmt(outcome.primaryProductionShare()),
              outcome.candidateScore().victoryCities(),
              outcome.opponentScore().victoryCities(),
              outcome.candidateScore().production(),
              outcome.opponentScore().production(),
              outcome.candidateScore().units(),
              outcome.opponentScore().units(),
              trajectory(outcome.checkpoints(), c -> c.candidate().victoryCities()),
              outcome.rounds(),
              outcome.durationMs());
        }
      }
      // The completeness marker. A crashed or killed batch produces a file without this line, so
      // a consumer can tell "finished 8 of 8" from "died after 5" without counting rows against
      // the games= header. compare.py refuses files that lack it.
      final long errors = outcomes.stream().filter(o -> o.outcome().equals("ERROR")).count();
      csv.println(
          "# run_complete games="
              + outcomes.size()
              + " errors="
              + errors
              + " assignment="
              + (lastAssignment == null ? "unrecorded" : lastAssignment)
              + " finished="
              + Instant.now());
      if (csv.checkError()) {
        // PrintWriter swallows IOExceptions; without this a full disk yields a silently short CSV.
        throw new IOException(
            "CSV writer reported an error; " + options.out.toAbsolutePath() + " is suspect.");
      }
    }

    summarize(outcomes, options);
  }

  /**
   * The fork commit this runner was built from, with a {@code -dirty} suffix when the working tree
   * has uncommitted changes -- which during patch development is the norm, and exactly when knowing
   * "which code was this" matters most. Falls back to {@code unknown} outside a git checkout.
   */
  private static String engineBuildId() {
    final String sha = git("rev-parse", "--short=9", "HEAD");
    if (sha == null) {
      return "unknown";
    }
    final String status = git("status", "--porcelain");
    return status == null || status.isEmpty() ? sha : sha + "-dirty";
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  private static String git(final String... gitArgs) {
    try {
      final List<String> command = new ArrayList<>(List.of("git"));
      command.addAll(List.of(gitArgs));
      final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      final StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (output.length() > 0) {
            output.append(' ');
          }
          output.append(line.trim());
        }
      }
      return process.waitFor() == 0 ? output.toString() : null;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (final IOException e) {
      return null;
    }
  }

  /**
   * Plays a single game to completion, or until the round cap. Returns the outcome rather than
   * throwing, so that one bad game does not abort an overnight batch.
   */
  private static GameOutcome runOneGame(
      final long seed,
      final String candidateSide,
      final PlayerTypes.Type candidate,
      final PlayerTypes.Type baseline,
      final int maxRounds,
      final boolean deterministic,
      final List<Integer> checkpointRounds) {

    final long startNanos = System.nanoTime();
    final String opponentSide = candidateSide.equals(AXIS) ? ALLIES : AXIS;
    final List<Checkpoint> checkpoints = new ArrayList<>();
    ServerGame game = null;
    try {
      // Must be enabled before the map is parsed: unit ids are drawn from the seeded sequence, and
      // the starting units are created during parsing.
      if (deterministic) {
        DeterministicRun.enable(seed);
      }
      game =
          GameTestUtils.setUpGameWithAis(
              PTV_XML, data -> assignTypes(data, candidateSide, candidate, baseline));
      game.setRandomSource(new SeededRandomSource(seed));
      game.setStopGameOnDelegateExecutionStop(true);

      final GameData data = game.getData();
      int lastRound = -1;
      while (!game.isGameOver() && data.getSequence().getRound() <= maxRounds) {
        game.runNextStep();
        final int round = data.getSequence().getRound();
        if (round != lastRound) {
          lastRound = round;
          // Sampled on entering the round, so the value reflects the position going into it.
          if (checkpointRounds.contains(round) && round <= maxRounds) {
            checkpoints.add(
                new Checkpoint(
                    round, scoreAlliance(data, candidateSide), scoreAlliance(data, opponentSide)));
          }
        }
      }

      // The counter has already advanced into the round we refused to play, so a capped game
      // reports maxRounds rather than maxRounds + 1. A game that ended on its own is unaffected.
      final int rounds = Math.min(data.getSequence().getRound(), maxRounds);
      final EndRoundDelegate endDelegate = (EndRoundDelegate) data.getEndRoundDelegate();
      final Collection<GamePlayer> winners = endDelegate.getWinners();

      final SideScore candidateScore = scoreAlliance(data, candidateSide);
      final SideScore opponentScore = scoreAlliance(data, opponentSide);

      final boolean decided = game.isGameOver() && !winners.isEmpty();
      final List<String> winnerNames =
          winners.stream().map(GamePlayer::getName).collect(Collectors.toList());

      return new GameOutcome(
          seed,
          candidateSide,
          decided ? "VICTORY" : "ROUND_CAP",
          decided ? allianceOf(data, winners) : "none",
          decided ? winnerNames : List.of(),
          rounds,
          elapsedMs(startNanos),
          candidateScore,
          opponentScore,
          checkpoints,
          // Read before teardown: stopGame's own cancels must not be blamed on the game.
          Diag.snapshot());

    } catch (final RuntimeException e) {
      log.error("Game failed (seed={}, candidateSide={})", seed, candidateSide, e);
      return new GameOutcome(
          seed,
          candidateSide,
          "ERROR",
          "none",
          List.of(),
          -1,
          elapsedMs(startNanos),
          SideScore.EMPTY,
          SideScore.EMPTY,
          checkpoints,
          Diag.snapshot());
    } finally {
      if (game != null) {
        // Required between games: releases the shared ConcurrentBattleCalculator's threads and
        // clears its game data, which would otherwise leak into the next game's simulations.
        game.stopGame();
      }
      // stopGame discards the teardown future, and the teardown reads DeterministicRun.isEnabled()
      // more than once while it runs -- joining here closes the window in which the next game's
      // enable()/disable() flip could be observed mid-teardown.
      ProAi.drainSharedCalculator().join();
      // Cleared per game rather than per run, so the unit-id sequence restarts from the same point
      // for every seed instead of carrying over from whatever the previous game consumed.
      DeterministicRun.disable();
    }
  }

  /**
   * Material position of one alliance: victory cities held, production income, and units on the
   * board. This is the graded metric that replaces win/loss, since PTV games between bots usually
   * end at the round cap rather than by a victory trigger.
   */
  private static SideScore scoreAlliance(final GameData data, final String alliance) {
    final Set<GamePlayer> members = data.getAllianceTracker().getPlayersInAlliance(alliance);
    int victoryCities = 0;
    int production = 0;
    int units = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      if (Matches.isTerritoryOwnedByAnyOf(members).test(t)) {
        victoryCities +=
            TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0);
        production += TerritoryAttachment.getProduction(t);
      }
      units += CollectionUtils.countMatches(t.getUnits(), u -> members.contains(u.getOwner()));
    }
    return new SideScore(victoryCities, production, units);
  }

  /**
   * Assigns the candidate AI to every player on {@code candidateSide}, and the baseline AI to
   * everyone else -- including the minor and neutral powers (Dutch, Mongolians, Neutral_*), which
   * stay on the baseline in both directions so they do not confound the comparison.
   */
  private static Map<String, PlayerTypes.Type> assignTypes(
      final GameData data,
      final String candidateSide,
      final PlayerTypes.Type candidate,
      final PlayerTypes.Type baseline) {

    final Collection<GamePlayer> candidatePlayers =
        data.getAllianceTracker().getPlayersInAlliance(candidateSide);
    if (candidatePlayers.isEmpty()) {
      throw new IllegalStateException("No players found in alliance: " + candidateSide);
    }
    final Map<String, PlayerTypes.Type> types = new HashMap<>();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      types.put(player.getName(), candidatePlayers.contains(player) ? candidate : baseline);
    }
    // Logged every game as an audit trail, captured for the CSV footer, and validated: a single
    // AI type covering all 15 players when candidate and baseline differ means the run is
    // measuring a bot against itself, and the numbers are worthless.
    final Map<String, Long> byLabel =
        types.values().stream()
            .collect(
                Collectors.groupingBy(
                    PlayerTypes.Type::getLabel, TreeMap::new, Collectors.counting()));
    log.info("AI assignment (candidate plays {}): {}", candidateSide, byLabel);
    lastAssignment = byLabel.toString().replace(",", ";");
    if (candidate != baseline && byLabel.size() < 2) {
      throw new IllegalStateException(
          "candidate and baseline are different bots but the realized assignment has a single AI"
              + " type ("
              + byLabel
              + ") -- the run would compare a bot to itself.");
    }
    return types;
  }

  private static String allianceOf(final GameData data, final Collection<GamePlayer> winners) {
    for (final String alliance : List.of(AXIS, ALLIES)) {
      final Collection<GamePlayer> members =
          data.getAllianceTracker().getPlayersInAlliance(alliance);
      if (winners.stream().anyMatch(members::contains)) {
        return alliance;
      }
    }
    return "other";
  }

  /** Formats checkpoint samples as {@code round:value} pairs, e.g. {@code 5:12;10:6;15:3}. */
  private static String trajectory(
      final List<Checkpoint> checkpoints, final ToIntFunction<Checkpoint> value) {
    return checkpoints.stream()
        .map(c -> c.round() + ":" + value.applyAsInt(c))
        .collect(Collectors.joining(";"));
  }

  private static void summarize(final List<GameOutcome> outcomes, final Options options) {
    log.info("================ PTV EVAL SUMMARY ================");
    log.info("candidate={}  baseline={}", options.candidate, options.baseline);
    for (final String side : options.sides()) {
      final List<GameOutcome> forSide =
          outcomes.stream().filter(o -> o.candidateSide().equals(side)).toList();
      final List<GameOutcome> scored =
          forSide.stream().filter(o -> !o.outcome().equals("ERROR")).toList();
      final long wins = forSide.stream().filter(GameOutcome::candidateWon).count();
      final long decided = forSide.stream().filter(o -> o.outcome().equals("VICTORY")).count();
      log.info(
          "candidate as {}: {} games | {} decided ({} won) | final VC {} vs {} | final prod {} vs {}",
          side,
          forSide.size(),
          decided,
          wins,
          mean(scored, o -> o.candidateScore().victoryCities()),
          mean(scored, o -> o.opponentScore().victoryCities()),
          mean(scored, o -> o.candidateScore().production()),
          mean(scored, o -> o.opponentScore().production()));

      // The primary metric, with its own precision. Printed together on purpose: three patches in
      // a row were declared "no effect" from runs that could not have resolved any effect, because
      // nobody worked out what the sample size could detect. Now the run says so itself.
      final Stats vc = statsAt(scored, PRIMARY_METRIC_ROUND, c -> c.candidate().victoryCities());
      final Stats share = statsAt(scored, PRIMARY_METRIC_ROUND, PtvEvalRunner::productionShare);
      if (vc.n() > 0) {
        log.info(
            "  PRIMARY (round {}): VC mean {} SD {} SE {} | prod share {}% SD {} SE {} | n={}",
            PRIMARY_METRIC_ROUND,
            fmt(vc.mean()),
            fmt(vc.sd()),
            fmt(vc.standardError()),
            fmt(share.mean()),
            fmt(share.sd()),
            fmt(share.standardError()),
            vc.n());
        log.info(
            "  smallest difference this many games could detect (80% power, a=.05):"
                + " {} VC / {} points of production share."
                + " A measured gap smaller than that is not evidence of anything.",
            fmt(vc.minimumDetectableDifference()), fmt(share.minimumDetectableDifference()));
      } else {
        log.warn(
            "  PRIMARY (round {}): no games reached it -- nothing to score.", PRIMARY_METRIC_ROUND);
      }

      // The collapse curve: where the candidate's position stood entering each checkpoint round.
      // Two bots with the same final score can differ sharply here, which is the point.
      final StringBuilder curve = new StringBuilder();
      for (final int round : checkpointsFor(options.maxRounds)) {
        final OptionalDouble avg =
            scored.stream()
                .map(o -> checkpointAt(o, round))
                .filter(Objects::nonNull)
                .mapToInt(c -> c.candidate().victoryCities())
                .average();
        if (avg.isPresent()) {
          curve
              .append(curve.length() == 0 ? "" : "  ")
              .append(String.format(Locale.ROOT, "r%d:%.1f", round, avg.getAsDouble()));
        }
      }
      log.info("  mean candidate VC by round: {}", curve);
    }
    final List<GameOutcome> scored =
        outcomes.stream().filter(o -> !o.outcome().equals("ERROR")).toList();
    final long vcAhead = scored.stream().filter(GameOutcome::candidateAheadOnVictoryCities).count();
    log.info(
        "OVERALL: candidate ahead on victory cities in {} of {} scored games",
        vcAhead,
        scored.size());
    final long contaminated = outcomes.stream().filter(o -> !o.diag().clean()).count();
    if (contaminated > 0) {
      log.warn(
          "OVERALL: {} of {} games are DIAG-CONTAMINATED (see diag_* columns); their rows must"
              + " not be scored.",
          contaminated,
          outcomes.size());
    }
    log.info("CSV written to {}", options.out.toAbsolutePath());
  }

  private static Checkpoint checkpointAt(final GameOutcome outcome, final int round) {
    return outcome.checkpoints().stream().filter(c -> c.round() == round).findFirst().orElse(null);
  }

  /**
   * The candidate's share of total production, which is more comparable across games than its raw
   * production: a game where both sides grew is not the same as one where the candidate pulled
   * ahead, and the raw figure cannot tell them apart.
   */
  private static double productionShare(final Checkpoint checkpoint) {
    final int total = checkpoint.candidate().production() + checkpoint.opponent().production();
    return total == 0 ? 0 : 100.0 * checkpoint.candidate().production() / total;
  }

  private static Stats statsAt(
      final List<GameOutcome> outcomes, final int round, final ToDoubleFunction<Checkpoint> value) {
    final double[] values =
        outcomes.stream()
            .map(o -> checkpointAt(o, round))
            .filter(Objects::nonNull)
            .mapToDouble(value)
            .toArray();
    return Stats.of(values);
  }

  private static String fmt(final double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  /** Sample mean and spread, plus what a sample that size can actually resolve. */
  private record Stats(double mean, double sd, int n) {

    static Stats of(final double[] values) {
      if (values.length == 0) {
        return new Stats(Double.NaN, Double.NaN, 0);
      }
      final double mean = Arrays.stream(values).average().orElse(Double.NaN);
      if (values.length == 1) {
        return new Stats(mean, Double.NaN, 1);
      }
      final double sumSquares =
          Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum() / (values.length - 1);
      return new Stats(mean, Math.sqrt(sumSquares), values.length);
    }

    double standardError() {
      return sd / Math.sqrt(n);
    }

    /**
     * The smallest true difference this sample size would detect 80% of the time at a = .05, two
     * sided, comparing two arms of {@code n} games. Rearranged from the usual {@code n = 16s^2/D^2}
     * to {@code D = 4s/sqrt(n)}.
     */
    double minimumDetectableDifference() {
      return 4 * sd / Math.sqrt(n);
    }
  }

  private static String mean(final List<GameOutcome> outcomes, final ToIntFunction<GameOutcome> f) {
    if (outcomes.isEmpty()) {
      return "n/a";
    }
    return String.format(
        Locale.ROOT, "%.1f", outcomes.stream().mapToInt(f).average().orElse(Double.NaN));
  }

  private static long elapsedMs(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private static PlayerTypes.Type bot(final String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "pro" -> PlayerTypes.PRO_AI;
      case "fast" -> PlayerTypes.FAST_AI;
      case "weak" -> PlayerTypes.WEAK_AI;
      case "ptv" -> PlayerTypes.PTV_AI;
      default ->
          throw new IllegalArgumentException(
              "Unknown bot '" + name + "' (expected pro|fast|weak|ptv)");
    };
  }

  /** Material position of one alliance at a point in time. */
  private record SideScore(int victoryCities, int production, int units) {
    static final SideScore EMPTY = new SideScore(-1, -1, -1);
  }

  /**
   * The game's diagnostic tripwires, snapshotted from {@link DeterministicRun} before teardown. Any
   * non-zero value means the AI decided on missing or corrupted odds at some point -- an empty
   * worker pool answering NaN, a cancelled worker returning a truncated sample, a stopped
   * calculator answering all-zero, or unit ids issued off-thread. Such a game must not be scored;
   * compare.py refuses rows where these are non-zero.
   */
  private record Diag(
      long emptyPool, long degradedPool, long truncatedCalc, long stoppedCalc, long offThreadIds) {

    static Diag snapshot() {
      return new Diag(
          DeterministicRun.emptyPoolResults(),
          DeterministicRun.degradedPoolBuilds(),
          DeterministicRun.truncatedCalcRuns(),
          DeterministicRun.stoppedCalcQueries(),
          DeterministicRun.offThreadUnitIds());
    }

    boolean clean() {
      return emptyPool == 0
          && degradedPool == 0
          && truncatedCalc == 0
          && stoppedCalc == 0
          && offThreadIds == 0;
    }
  }

  /** Material position of both sides entering a given round. */
  private record Checkpoint(int round, SideScore candidate, SideScore opponent) {}

  /** One row of the results CSV. */
  private record GameOutcome(
      long seed,
      String candidateSide,
      String outcome,
      String winningAlliance,
      List<String> winners,
      int rounds,
      long durationMs,
      SideScore candidateScore,
      SideScore opponentScore,
      List<Checkpoint> checkpoints,
      Diag diag) {

    boolean candidateWon() {
      return outcome.equals("VICTORY") && winningAlliance.equals(candidateSide);
    }

    boolean candidateAheadOnVictoryCities() {
      return candidateScore.victoryCities() > opponentScore.victoryCities();
    }

    /**
     * The primary metric, or -1 if the game ended before reaching {@link #PRIMARY_METRIC_ROUND}.
     */
    int primaryVictoryCities() {
      final Checkpoint c = checkpointAt(this, PRIMARY_METRIC_ROUND);
      return c == null ? -1 : c.candidate().victoryCities();
    }

    double primaryProductionShare() {
      final Checkpoint c = checkpointAt(this, PRIMARY_METRIC_ROUND);
      return c == null ? -1 : productionShare(c);
    }

    static String csvHeader() {
      // primary_* are duplicated out of the trajectory columns on purpose: they are what the run is
      // scored on, and every consumer otherwise has to re-parse "5:9;10:8;15:6" to find them.
      return "seed,candidate_side,outcome,winning_alliance,winners,rounds,duration_ms,"
          + "candidate_won,primary_vc,primary_prod_share,"
          + "candidate_vc,opponent_vc,candidate_prod,opponent_prod,"
          + "candidate_units,opponent_units,"
          + "candidate_vc_by_round,opponent_vc_by_round,"
          + "candidate_prod_by_round,opponent_prod_by_round,"
          + "diag_empty_pool,diag_degraded_pool,diag_truncated_calc,diag_stopped_calc,"
          + "diag_offthread_ids";
    }

    String toCsvRow() {
      return String.join(
          ",",
          String.valueOf(seed),
          candidateSide,
          outcome,
          winningAlliance,
          String.join(";", winners),
          String.valueOf(rounds),
          String.valueOf(durationMs),
          String.valueOf(candidateWon()),
          String.valueOf(primaryVictoryCities()),
          String.format(Locale.ROOT, "%.2f", primaryProductionShare()),
          String.valueOf(candidateScore.victoryCities()),
          String.valueOf(opponentScore.victoryCities()),
          String.valueOf(candidateScore.production()),
          String.valueOf(opponentScore.production()),
          String.valueOf(candidateScore.units()),
          String.valueOf(opponentScore.units()),
          trajectory(checkpoints, c -> c.candidate().victoryCities()),
          trajectory(checkpoints, c -> c.opponent().victoryCities()),
          trajectory(checkpoints, c -> c.candidate().production()),
          trajectory(checkpoints, c -> c.opponent().production()),
          String.valueOf(diag.emptyPool()),
          String.valueOf(diag.degradedPool()),
          String.valueOf(diag.truncatedCalc()),
          String.valueOf(diag.stoppedCalc()),
          String.valueOf(diag.offThreadIds()));
    }
  }

  /** Command line options. */
  private static final class Options {
    private int games = 10;
    private long seedStart = 1000L;
    private List<Long> explicitSeeds = null;
    private String side = "both";
    private String candidate = "pro";
    private String baseline = "pro";
    private boolean deterministic = true;
    // 16, not 100: the run is scored at round 15, and playing on to 35 doubled the cost of a game
    // to reach an endpoint whose noise floor swallows any plausible effect.
    private int maxRounds = 16;
    private Path out = Path.of("build", "ptv-eval", "results.csv");
    private boolean allowSelf = false;
    private boolean overwrite = false;

    static Options parse(final String[] args) {
      final Options o = new Options();
      for (int i = 0; i < args.length; i += 2) {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("Missing value for argument: " + args[i]);
        }
        final String value = args[i + 1];
        switch (args[i]) {
          case "--games" -> o.games = Integer.parseInt(value);
          case "--seed-start" -> o.seedStart = Long.parseLong(value);
          case "--seeds" ->
              o.explicitSeeds =
                  java.util.Arrays.stream(value.split(","))
                      .map(String::trim)
                      .map(Long::parseLong)
                      .collect(Collectors.toList());
          case "--side" -> o.side = value;
          case "--max-rounds" -> o.maxRounds = Integer.parseInt(value);
          case "--candidate" -> o.candidate = value;
          case "--baseline" -> o.baseline = value;
          case "--deterministic" -> o.deterministic = Boolean.parseBoolean(value);
          case "--allow-self" -> o.allowSelf = Boolean.parseBoolean(value);
          case "--overwrite" -> o.overwrite = Boolean.parseBoolean(value);
          case "--out" -> o.out = Path.of(value);
          default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
        }
      }
      return o;
    }

    List<Long> seeds() {
      if (explicitSeeds != null) {
        return explicitSeeds;
      }
      final List<Long> generated = new ArrayList<>(games);
      for (int i = 0; i < games; i++) {
        generated.add(seedStart + i);
      }
      return generated;
    }

    List<String> sides() {
      return switch (side.toLowerCase(Locale.ROOT)) {
        case "axis" -> List.of(AXIS);
        case "allies" -> List.of(ALLIES);
        case "both" -> List.of(AXIS, ALLIES);
        default ->
            throw new IllegalArgumentException(
                "Unknown side '" + side + "' (expected axis|allies|both)");
      };
    }
  }
}
