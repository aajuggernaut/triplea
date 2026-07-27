package org.triplea.ptv.eval;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.EndRoundDelegate;
import games.strategy.triplea.delegate.Matches;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
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
 * <p><b>Win/loss is not the primary metric.</b> PTV enables only {@code Triggered Victory}: Total
 * Victory, Honorable Surrender, Projection of Power and Economic Victory are all absent from the
 * map XML and default to false. A game therefore ends only when a map-defined victory trigger
 * fires, which bot-vs-bot play frequently never reaches -- an observed ProAI-vs-WeakAi game ran the
 * full 60 rounds undecided. Every game is consequently scored on material position at the final
 * round (victory cities, production, units held per side), which yields a graded signal whether or
 * not anyone actually wins.
 *
 * <p><b>Do not run two instances of this in one JVM.</b> {@code ProAi} holds a {@code static final
 * ConcurrentBattleCalculator} shared by every ProAi instance, and rebinds its game data in {@code
 * prepareData}. Concurrent games would simulate battles against each other's board state. Games are
 * therefore run sequentially here; to use more cores, launch several processes with disjoint {@code
 * --seeds} and merge the CSVs.
 */
@Slf4j
public final class PtvEvalRunner {

  private static final String PTV_XML = "ww2_path_to_victory.xml";
  private static final String AXIS = "Axis";
  private static final String ALLIES = "Allies";

  private PtvEvalRunner() {}

  public static void main(final String[] args) throws IOException {
    final Options options = Options.parse(args);
    GameTestUtils.setUp();

    final PlayerTypes.Type candidate = bot(options.candidate);
    final PlayerTypes.Type baseline = bot(options.baseline);
    final List<String> sides = options.sides();
    final List<Long> seeds = options.seeds();

    Files.createDirectories(options.out.toAbsolutePath().getParent());
    final List<GameOutcome> outcomes = new ArrayList<>();

    try (PrintWriter csv =
        new PrintWriter(Files.newBufferedWriter(options.out, StandardCharsets.UTF_8))) {
      csv.println("# ptv-eval run " + Instant.now());
      csv.println("# map=WW2 Path to Victory v6.4 (commit 45354ca)");
      csv.println("# candidate=" + options.candidate + " baseline=" + options.baseline);
      csv.println("# maxRounds=" + options.maxRounds + " games=" + (seeds.size() * sides.size()));
      csv.println(GameOutcome.csvHeader());
      csv.flush();

      for (final String side : sides) {
        for (final long seed : seeds) {
          final GameOutcome outcome =
              runOneGame(seed, side, candidate, baseline, options.maxRounds);
          outcomes.add(outcome);
          csv.println(outcome.toCsvRow());
          csv.flush();
          log.info(
              "seed={} candidateSide={} -> {} | VC {}v{} prod {}v{} units {}v{} | {} rounds, {} ms",
              seed,
              side,
              outcome.outcome(),
              outcome.candidateScore().victoryCities(),
              outcome.opponentScore().victoryCities(),
              outcome.candidateScore().production(),
              outcome.opponentScore().production(),
              outcome.candidateScore().units(),
              outcome.opponentScore().units(),
              outcome.rounds(),
              outcome.durationMs());
        }
      }
    }

    summarize(outcomes, options);
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
      final int maxRounds) {

    final long startNanos = System.nanoTime();
    final String opponentSide = candidateSide.equals(AXIS) ? ALLIES : AXIS;
    ServerGame game = null;
    try {
      game =
          GameTestUtils.setUpGameWithAis(
              PTV_XML, data -> assignTypes(data, candidateSide, candidate, baseline));
      game.setRandomSource(new SeededRandomSource(seed));
      game.setStopGameOnDelegateExecutionStop(true);

      while (!game.isGameOver() && game.getData().getSequence().getRound() <= maxRounds) {
        game.runNextStep();
      }

      // The counter has already advanced into the round we refused to play, so a capped game
      // reports maxRounds rather than maxRounds + 1. A game that ended on its own is unaffected.
      final GameData data = game.getData();
      final int rounds = Math.min(data.getSequence().getRound(), maxRounds);
      final EndRoundDelegate endDelegate = (EndRoundDelegate) data.getEndRoundDelegate();
      final Collection<GamePlayer> winners = endDelegate.getWinners();

      final SideScore candidateScore = scoreAlliance(data, candidateSide);
      final SideScore opponentScore = scoreAlliance(data, opponentSide);

      final boolean decided = game.isGameOver() && !winners.isEmpty();
      final String outcome = decided ? "VICTORY" : "ROUND_CAP";
      final String winningAlliance = decided ? allianceOf(data, winners) : "none";
      final List<String> winnerNames =
          winners.stream().map(GamePlayer::getName).collect(Collectors.toList());

      return new GameOutcome(
          seed,
          candidateSide,
          outcome,
          winningAlliance,
          decided ? winnerNames : List.of(),
          rounds,
          elapsedMs(startNanos),
          candidateScore,
          opponentScore);

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
          SideScore.EMPTY);
    } finally {
      if (game != null) {
        // Required between games: releases the shared ConcurrentBattleCalculator's threads and
        // clears its game data, which would otherwise leak into the next game's simulations.
        game.stopGame();
      }
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
    // Logged every game as an audit trail: if this ever shows a single AI type covering all 15
    // players, the comparison is measuring a bot against itself and the results are worthless.
    final Map<String, Long> byLabel =
        types.values().stream()
            .collect(
                Collectors.groupingBy(
                    PlayerTypes.Type::getLabel, TreeMap::new, Collectors.counting()));
    log.info("AI assignment (candidate plays {}): {}", candidateSide, byLabel);
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
          "candidate as {}: {} games | {} decided ({} won) | mean VC {} vs {} | mean prod {} vs {}",
          side,
          forSide.size(),
          decided,
          wins,
          mean(scored, o -> o.candidateScore().victoryCities()),
          mean(scored, o -> o.opponentScore().victoryCities()),
          mean(scored, o -> o.candidateScore().production()),
          mean(scored, o -> o.opponentScore().production()));
    }
    final List<GameOutcome> scored =
        outcomes.stream().filter(o -> !o.outcome().equals("ERROR")).toList();
    final long vcAhead = scored.stream().filter(GameOutcome::candidateAheadOnVictoryCities).count();
    log.info(
        "OVERALL: candidate ahead on victory cities in {} of {} scored games",
        vcAhead,
        scored.size());
    log.info("CSV written to {}", options.out.toAbsolutePath());
  }

  private static String mean(
      final List<GameOutcome> outcomes, final java.util.function.ToIntFunction<GameOutcome> f) {
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
      default ->
          throw new IllegalArgumentException("Unknown bot '" + name + "' (expected pro|fast|weak)");
    };
  }

  /** Material position of one alliance at the end of a game. */
  private record SideScore(int victoryCities, int production, int units) {
    static final SideScore EMPTY = new SideScore(-1, -1, -1);
  }

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
      SideScore opponentScore) {

    boolean candidateWon() {
      return outcome.equals("VICTORY") && winningAlliance.equals(candidateSide);
    }

    boolean candidateAheadOnVictoryCities() {
      return candidateScore.victoryCities() > opponentScore.victoryCities();
    }

    static String csvHeader() {
      return "seed,candidate_side,outcome,winning_alliance,winners,rounds,duration_ms,"
          + "candidate_won,candidate_vc,opponent_vc,candidate_prod,opponent_prod,"
          + "candidate_units,opponent_units";
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
          String.valueOf(candidateScore.victoryCities()),
          String.valueOf(opponentScore.victoryCities()),
          String.valueOf(candidateScore.production()),
          String.valueOf(opponentScore.production()),
          String.valueOf(candidateScore.units()),
          String.valueOf(opponentScore.units()));
    }
  }

  /** Command line options. */
  private static final class Options {
    private int games = 10;
    private long seedStart = 1000L;
    private List<Long> explicitSeeds = null;
    private String side = "both";
    private int maxRounds = 100;
    private String candidate = "pro";
    private String baseline = "pro";
    private Path out = Path.of("build", "ptv-eval", "results.csv");

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
