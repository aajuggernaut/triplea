package org.triplea.ptv.eval;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.random.DeterministicRun;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Plays one game and reports how often each named behavioural defect occurred.
 *
 * <pre>
 *   java -cp &lt;cp&gt; org.triplea.ptv.eval.PtvDefectProbeRunner --rounds 6 --seed 2000 --bot pro
 * </pre>
 *
 * <p>This is the cheap half of the measurement strategy. A material comparison needs ~26 games per
 * arm and most of a day; a defect count needs one game and answers a different, sharper question --
 * not "is the bot better?" but "does it still do this specific dumb thing?". Run it before and
 * after a fix: if the count does not move, the patch does not do what it claims, and there is no
 * point spending a day measuring whether it helps.
 */
@Slf4j
public final class PtvDefectProbeRunner {

  private static final String PTV_XML = "ww2_path_to_victory.xml";

  private PtvDefectProbeRunner() {}

  public static void main(final String[] args) throws IOException {
    int rounds = 6;
    long seed = 2000L;
    String bot = "pro";
    for (int i = 0; i < args.length; i += 2) {
      switch (args[i]) {
        case "--rounds" -> rounds = Integer.parseInt(args[i + 1]);
        case "--seed" -> seed = Long.parseLong(args[i + 1]);
        case "--bot" -> bot = args[i + 1];
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    }

    GameTestUtils.setUp();
    final PlayerTypes.Type type =
        switch (bot.toLowerCase(Locale.ROOT)) {
          case "pro" -> PlayerTypes.PRO_AI;
          case "ptv" -> PlayerTypes.PTV_AI;
          case "weak" -> PlayerTypes.WEAK_AI;
          default -> throw new IllegalArgumentException("Unknown bot: " + bot);
        };

    final List<PtvDefectProbe.Occurrence> occurrences = new ArrayList<>();
    final java.util.Set<String> capturedWatchlist = new java.util.HashSet<>();
    Map<String, String> previousPositions = Map.of();
    ServerGame game = null;
    try {
      DeterministicRun.enable(seed);
      game =
          GameTestUtils.setUpGameWithAis(
              PTV_XML,
              data -> {
                final Map<String, PlayerTypes.Type> types = new HashMap<>();
                data.getPlayerList().getPlayers().forEach(p -> types.put(p.getName(), type));
                return types;
              });
      game.setRandomSource(new SeededRandomSource(seed));
      game.setStopGameOnDelegateExecutionStop(true);

      final GameData data = game.getData();
      int lastRound = -1;
      while (!game.isGameOver() && data.getSequence().getRound() <= rounds) {
        game.runNextStep();
        final int round = data.getSequence().getRound();
        if (round != lastRound) {
          if (lastRound > 0) {
            // Sampled on entering the next round, i.e. after every player has finished moving,
            // so a transport counted here has genuinely been left loaded rather than caught
            // mid-turn.
            final List<PtvDefectProbe.Occurrence> found = PtvDefectProbe.all(data, lastRound);
            final Map<String, String> positions = PtvDefectProbe.loadedTransportPositions(data);
            found.addAll(PtvDefectProbe.stalledCargo(previousPositions, positions, lastRound));
            found.addAll(PtvDefectProbe.japaneseCaptures(data, lastRound, capturedWatchlist));
            previousPositions = positions;
            occurrences.addAll(found);
            log.info("round {} complete: {} defect occurrences", lastRound, found.size());
          }
          lastRound = round;
        }
      }
    } finally {
      if (game != null) {
        game.stopGame();
      }
      DeterministicRun.disable();
    }

    log.info(
        "=============== DEFECT PROBE: bot={} seed={} rounds={} ===============",
        bot,
        seed,
        rounds);
    final Map<String, Map<String, Integer>> summary = PtvDefectProbe.summarize(occurrences);
    if (summary.isEmpty()) {
      log.info("no defects observed");
    }
    summary.forEach(
        (defect, byPlayer) -> {
          final int total = byPlayer.values().stream().mapToInt(Integer::intValue).sum();
          log.info("{}: {} total  {}", defect, total, byPlayer);
        });
    log.info("--- first 25 occurrences ---");
    occurrences.stream().limit(25).forEach(o -> log.info("  {}", o));
  }
}
