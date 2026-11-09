package org.triplea.ptv.eval;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.history.HistoryNode;
import games.strategy.engine.history.Round;
import games.strategy.engine.history.Step;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.tree.TreeNode;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.collections.IntegerMap;

/**
 * Extracts per-round, per-alliance economic series from saved games (.tsvg) with full history: PUs
 * collected (income including national-objective bonuses, net of blockade losses), territory
 * production, TUV, and victory cities. Writes one CSV per save.
 *
 * <p>Income is parsed from history event text ("X collect N PUs", "...met a national objective for
 * an additional N PUs", "captures N PUs while taking ... capital" -- plunder is reported in its own
 * columns) and attributed to the alliance of the player whose step the event sits under. Material
 * stats are computed by seeking the game state to each Round node, which lands on the state at the
 * END of that round (verified against a hand-tracked game), so a CSV row reads "income collected
 * during round R; material at the end of round R". A final {@code end} row holds the save's
 * terminal state, which is what the corpus manifest quotes; it differs from the last round row only
 * when a save stops mid-round.
 *
 * <p>Destroyed treasuries (the {@code destroysPUs} recapture rule) and manual edit-mode cash
 * changes are not income. Unmatched PU-bearing lines are printed as an audit trail so a text format
 * drift cannot silently zero a series (this caught the {@code Optional[PUs]} drift).
 *
 * <pre>
 *   ./gradlew :smoke-testing:ptvStatsDump -PptvStatsArgs="--out DIR --dir DIR_OF_SAVES"
 *   java @argfile org.triplea.ptv.eval.PtvStatsDump --out DIR save1.tsvg [save2.tsvg ...]
 * </pre>
 *
 * <p>{@code --dir} processes every .tsvg in a directory, which sidesteps the corpus's
 * spaces-in-filenames problem when args arrive through a Gradle property.
 */
@Slf4j
public final class PtvStatsDump {

  // Newer engine builds (seen in rounds played mid-2026) render the resource in trigger text as
  // "Optional[PUs]"; both spellings must count or a game spanning engine versions drops income.
  private static final Pattern COLLECT =
      Pattern.compile("\\bcollects? (\\d+) (?:Optional\\[)?PUs?\\b");
  private static final Pattern OBJECTIVE =
      Pattern.compile("for an additional (\\d+) (?:Optional\\[)?PUs?\\b");
  private static final Pattern CAPTURED = Pattern.compile("captures? (\\d+) ?PUs? while taking");

  /**
   * Handicap income from the per-player "Income Percentage" / "PU Income Bonus" game options
   * (BonusIncomeUtils). The message carries the TOTAL bonus after "income for" when a percentage is
   * set, else only the flat amount: "Giving Germans 105% income for 4 PUs; end with ...", "Giving
   * Germans 3 PUs & 105% income for 7 PUs; ...", "Giving Germans 3 PUs PUs; ...".
   */
  private static final Pattern BONUS_PERCENT =
      Pattern.compile("^Giving \\S+ .*?income for (-?\\d+) (?:Optional\\[)?PUs?");

  private static final Pattern BONUS_FLAT =
      Pattern.compile("^Giving \\S+ (-?\\d+) (?:Optional\\[)?PUs?\\b");

  /**
   * PU-bearing lines that are known not to be income, kept out of the audit noise. Manual edit-mode
   * cash adjustments are consciously excluded from income (two exist in the corpus, +10 and +1), as
   * are treasuries destroyed rather than captured when a capital falls (the destroysPUs rule).
   */
  private static final Pattern KNOWN_NOT_INCOME =
      Pattern.compile(
          "Remaining resources|Turning on Edit Mode|EDIT: Changing PUs"
              + "|destroys \\d+ ?PUs? while taking");

  private PtvStatsDump() {}

  public static void main(final String[] args) throws IOException {
    Path outDir = Path.of(".");
    final List<Path> saves = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      if ("--out".equals(args[i])) {
        outDir = Path.of(args[++i]);
      } else if ("--dir".equals(args[i])) {
        try (var stream = Files.list(Path.of(args[++i]))) {
          stream
              .filter(p -> p.getFileName().toString().endsWith(".tsvg"))
              .sorted()
              .forEach(saves::add);
        }
      } else {
        saves.add(Path.of(args[i]));
      }
    }
    if (saves.isEmpty()) {
      throw new IllegalArgumentException(
          "No saves given. Usage: --out DIR (--dir SAVES_DIR | save1.tsvg ...)");
    }
    Files.createDirectories(outDir);
    GameTestUtils.setUp();

    for (final Path save : saves) {
      dumpOne(save, outDir);
    }
  }

  private static void dumpOne(final Path save, final Path outDir) throws IOException {
    log.info("Loading {}", save);
    final GameData data =
        GameDataManager.loadGame(save)
            .orElseThrow(() -> new IllegalStateException("Could not load save: " + save));

    final Set<GamePlayer> axis = data.getAllianceTracker().getPlayersInAlliance("Axis");
    final Set<GamePlayer> allies = data.getAllianceTracker().getPlayersInAlliance("Allies");
    if (axis.isEmpty() || allies.isEmpty()) {
      throw new IllegalStateException(
          "Expected alliances named Axis and Allies; found " + data.getAllianceTracker());
    }

    final List<String> handicaps = new ArrayList<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      final int pct = Properties.getIncomePercentage(p, data.getProperties());
      final int flat = Properties.getPuIncomeBonus(p, data.getProperties());
      if (pct != 100 || flat != 0) {
        handicaps.add(p.getName() + " " + pct + "%" + (flat != 0 ? " +" + flat + " PU" : ""));
      }
    }
    log.info(
        "{} handicap settings: {}",
        baseName(save),
        handicaps.isEmpty() ? "none" : String.join(", ", handicaps));

    final Map<Integer, int[]> incomeByRound = parseIncome(data, axis, allies);

    final List<Round> rounds = new ArrayList<>();
    final Enumeration<TreeNode> roots = ((HistoryNode) data.getHistory().getRoot()).children();
    while (roots.hasMoreElements()) {
      if (roots.nextElement() instanceof Round r) {
        rounds.add(r);
      }
    }

    data.getHistory().enableSeeking(null);
    final StringBuilder csv = new StringBuilder();
    csv.append(
        "game,round,axis_income,allies_income,axis_captured,allies_captured,"
            + "axis_bonus,allies_bonus,income_complete,"
            + "axis_prod,allies_prod,axis_tuv,allies_tuv,axis_vc,allies_vc\n");
    final String game = baseName(save);
    for (int i = 0; i < rounds.size(); i++) {
      final Round round = rounds.get(i);
      data.getHistory().gotoNode(round);
      final Material m = Material.of(data, axis, allies);
      final int[] income = incomeByRound.getOrDefault(round.getRoundNo(), new int[7]);
      // Games end by concession, usually partway through a round. Only the final round can be
      // short; it counts as complete when as many nations collected in it as in the round before.
      final boolean incomeComplete =
          i < rounds.size() - 1
              || i == 0
              || income[6]
                  >= incomeByRound.getOrDefault(rounds.get(i - 1).getRoundNo(), new int[7])[6];
      csv.append(
          String.format(
              "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
              game,
              round.getRoundNo(),
              income[0],
              income[1],
              income[2],
              income[3],
              income[4],
              income[5],
              incomeComplete ? 1 : 0,
              m.axisProd,
              m.alliesProd,
              m.axisTuv,
              m.alliesTuv,
              m.axisVc,
              m.alliesVc));
    }
    data.getHistory().gotoNode(data.getHistory().getLastNode());
    final Material end = Material.of(data, axis, allies);
    csv.append(
        String.format(
            "%s,end,,,,,,,,%d,%d,%d,%d,%d,%d%n",
            game,
            end.axisProd,
            end.alliesProd,
            end.axisTuv,
            end.alliesTuv,
            end.axisVc,
            end.alliesVc));

    final Path out = outDir.resolve(game + "-stats.csv");
    Files.writeString(out, csv.toString(), StandardCharsets.UTF_8);
    log.info(
        "{}: {} rounds | end VC {}-{} prod {}-{} tuv {}-{} -> {}",
        game,
        rounds.size(),
        end.axisVc,
        end.alliesVc,
        end.axisProd,
        end.alliesProd,
        end.axisTuv,
        end.alliesTuv,
        out);
  }

  /**
   * Sums collect + national-objective + handicap-bonus PUs (slots 0/1), captured-capital plunder
   * (slots 2/3), and the handicap bonus alone (slots 4/5, already included in 0/1) per round per
   * alliance from history node titles. Collect and objective lines are Events; plunder lines are
   * EventChild detail lines, so every non-structural node's title is scanned.
   */
  private static Map<Integer, int[]> parseIncome(
      final GameData data, final Set<GamePlayer> axis, final Set<GamePlayer> allies) {
    final Map<Integer, int[]> incomeByRound = new TreeMap<>();
    final List<String> audit = new ArrayList<>();
    int round = 0;
    GamePlayer stepPlayer = null;
    final Enumeration<?> nodes = ((HistoryNode) data.getHistory().getRoot()).preorderEnumeration();
    while (nodes.hasMoreElements()) {
      final Object node = nodes.nextElement();
      if (node instanceof Round r) {
        round = r.getRoundNo();
        continue;
      }
      if (node instanceof Step s) {
        stepPlayer = s.getPlayerId().orElse(null);
        continue;
      }
      if (!(node instanceof HistoryNode h)) {
        continue;
      }
      final String title = Optional.ofNullable(h.getTitle()).orElse("");
      if (!title.contains("PU")) {
        continue;
      }
      int slot = -1;
      int amount = 0;
      boolean bonus = false;
      boolean isCollect = false;
      final Matcher collect = COLLECT.matcher(title);
      final Matcher objective = OBJECTIVE.matcher(title);
      final Matcher captured = CAPTURED.matcher(title);
      final Matcher bonusPercent = BONUS_PERCENT.matcher(title);
      final Matcher bonusFlat = BONUS_FLAT.matcher(title);
      if (collect.find()) {
        slot = 0;
        isCollect = true;
        amount = Integer.parseInt(collect.group(1));
      } else if (objective.find()) {
        slot = 0;
        amount = Integer.parseInt(objective.group(1));
      } else if (captured.find()) {
        slot = 2;
        amount = Integer.parseInt(captured.group(1));
      } else if (bonusPercent.find()) {
        slot = 0;
        bonus = true;
        amount = Integer.parseInt(bonusPercent.group(1));
      } else if (bonusFlat.find()) {
        slot = 0;
        bonus = true;
        amount = Integer.parseInt(bonusFlat.group(1));
      }
      if (slot < 0) {
        if (!KNOWN_NOT_INCOME.matcher(title).find()) {
          audit.add("r" + round + " [" + name(stepPlayer) + "] " + title);
        }
        continue;
      }
      if (amount == 0 && !isCollect) {
        continue;
      }
      // A handicap applies to the starting treasury in a playerless pre-game step, so bonus
      // lines are attributed to the nation they name rather than to the step's player.
      final GamePlayer payee =
          bonus
              ? Optional.ofNullable(data.getPlayerList().getPlayerId(title.split(" ")[1]))
                  .orElse(stepPlayer)
              : stepPlayer;
      final int side = axis.contains(payee) ? 0 : allies.contains(payee) ? 1 : -1;
      if (side < 0) {
        audit.add("r" + round + " UNATTRIBUTED [" + name(stepPlayer) + "] " + title);
        continue;
      }
      final int[] totals = incomeByRound.computeIfAbsent(round, k -> new int[7]);
      totals[slot + side] += amount;
      if (isCollect) {
        totals[6]++;
      }
      if (bonus) {
        totals[4 + side] += amount;
      }
    }
    // The audit list holds every PU-bearing line that was neither counted nor known-safe.
    // Empty is the expected state; anything here needs a human eye before the numbers are used.
    audit.forEach(line -> log.warn("AUDIT {}", line));
    return incomeByRound;
  }

  private static String name(final GamePlayer player) {
    return player == null ? "<none>" : player.getName();
  }

  private static String baseName(final Path save) {
    final String fileName = save.getFileName().toString();
    return fileName.endsWith(".tsvg") ? fileName.substring(0, fileName.length() - 5) : fileName;
  }

  /** Per-alliance production, TUV, and victory cities at the current (sought) game state. */
  private static final class Material {
    int axisProd;
    int alliesProd;
    int axisTuv;
    int alliesTuv;
    int axisVc;
    int alliesVc;

    static Material of(
        final GameData data, final Set<GamePlayer> axis, final Set<GamePlayer> allies) {
      final Material m = new Material();
      final Map<GamePlayer, IntegerMap<UnitType>> tuvCosts = new LinkedHashMap<>();
      for (final GamePlayer p : axis) {
        tuvCosts.put(p, new TuvCostsCalculator().getCostsForTuv(p));
      }
      for (final GamePlayer p : allies) {
        tuvCosts.put(p, new TuvCostsCalculator().getCostsForTuv(p));
      }
      for (final Territory t : data.getMap().getTerritories()) {
        final GamePlayer owner = t.getOwner();
        final TerritoryAttachment ta = TerritoryAttachment.get(t).orElse(null);
        if (ta != null) {
          if (axis.contains(owner)) {
            m.axisProd += ta.getProduction();
            m.axisVc += ta.getVictoryCity();
          } else if (allies.contains(owner)) {
            m.alliesProd += ta.getProduction();
            m.alliesVc += ta.getVictoryCity();
          }
        }
        for (final Unit unit : t.getUnits()) {
          final IntegerMap<UnitType> costs = tuvCosts.get(unit.getOwner());
          if (costs == null) {
            continue;
          }
          final int cost = costs.getInt(unit.getType());
          if (axis.contains(unit.getOwner())) {
            m.axisTuv += cost;
          } else {
            m.alliesTuv += cost;
          }
        }
      }
      return m;
    }
  }
}
