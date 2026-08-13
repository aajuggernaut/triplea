package games.strategy.triplea.ai.pro;

import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The individually selectable PTV behaviours carried by {@link PtvProAi}.
 *
 * <p>They were originally a single boolean, which meant an evaluation run could only ever answer
 * "does the whole stack help?" -- and if the answer was no, could not say which member was
 * responsible or whether two of them were cancelling out. Each behaviour has exactly one gate in
 * the code, so separating them costs nothing and makes a run attributable.
 *
 * <p>Selected with {@code -Dtriplea.ai.ptv.features=NO_PARK_LOADED,GARRISON_CHINA}; the default
 * (property absent — i.e. GUI sparring games) is the <b>accepted stack</b>: the features that have
 * passed the two-tier measurement protocol, currently {@code NO_PARK_LOADED}, {@code
 * GARRISON_CHINA} and {@code NO_HIT_ATTACK_GUARD}. It was originally all-of-them, but that shipped
 * measured-harmful and unproven behaviours to every sparring game once the measurements existed (W3
 * measured −1.29 VC on 2026-08-10; W2 undetectable at ±2 VC). Scored evaluation runs always pass an
 * explicit list and are unaffected by the default. {@code none} disables the lot, which is useful
 * as a sanity check: a {@code ptv} bot with no features should be indistinguishable from stock, and
 * if it is not, something other than the features differs.
 */
@Slf4j
public enum PtvFeature {
  /** W2/W2-rev: value territories by the national-objective income they carry. */
  OBJECTIVE_VALUE,

  /** W3: shift purchasing toward mobility once the opening is over. */
  MOBILITY_PURCHASE,

  /** W12: stop abandoning amphibious targets that pay income. */
  AMPHIB_TARGETS,

  /** W15: cancel attacks whose units cannot legally target any defender. */
  NO_HIT_ATTACK_GUARD,

  /**
   * W7: never commit cargo to a sea zone with no nameable unload territory.
   *
   * <p>The non-combat amphib planner's sea branch otherwise parks loaded ships (including PTV's
   * marine-carrying warships) at the highest-value sea zone it can reach, repeats that choice every
   * round because nothing penalizes cargo afloat, and then anchors the fleet to defend the parked
   * ship. Skipping the no-unload commit releases the ship to the later passes -- unload to safe
   * land if loaded, loading position or safety if empty -- which were previously unreachable for
   * loaded ships. Diagnosis: notes/006 in the companion repo.
   */
  NO_PARK_LOADED,

  /**
   * W4: garrison Japanese-held Chinese guerrilla territories with one land unit.
   *
   * <p>PTV spawns a free Chinese infantry in any of 16 interior territories that is Japanese-owned
   * and holds no Axis ground unit at the Chinese trigger check -- every exposed round is a free
   * enemy unit. Stock ProAi's defend-with-one-unit pass only considers empty territories adjacent
   * to enemy land, which the deep interior is not. This adds the exposed guerrilla territories to
   * that pass's list; its existing machinery (cheapest defender, cost guard) does the rest.
   * Measured exposure without it: 3-8 territory-rounds per 10-round game (probe batch 2026-08-09).
   */
  GARRISON_CHINA,

  /**
   * W5/W14: keep one land unit in valuable coastal territories an enemy could assault by sea.
   *
   * <p>The defend-with-one-unit pass's threat test is "has a neighbor owned by an enemy with a land
   * unit in it" -- threat that walks. Threat that sails is invisible to it, so territories whose
   * land neighbors are friendly or neutral are stripped bare no matter what stands offshore: Norway
   * (land neighbors Finland and Sweden) goes empty from round 1 while a British battleship group
   * sits two sea zones away and a loaded transport three. The engine's own enemy-attack model sees
   * the amphibious force perfectly -- it is computed three calls after this pass runs. This adds
   * owned, valuable (production >= 2 or a victory city), unheld territories with potential-enemy
   * sea presence within three water moves (transport movement plus the unload step) to the same
   * pass; its existing machinery does the rest, exactly as GARRISON_CHINA. Measured without it:
   * Norway/Finland spend ~11 of 20 round-slots in Allied hands per 10-round game vs one Norway loss
   * in 15 human games (baselines 2026-08-11).
   */
  DEFEND_AMPHIB_THREAT;

  public static final String SYSTEM_PROPERTY = "triplea.ai.ptv.features";

  /** The features that have passed the two-tier measurement protocol. The GUI default. */
  private static final Set<PtvFeature> ACCEPTED_STACK =
      EnumSet.of(NO_PARK_LOADED, GARRISON_CHINA, NO_HIT_ATTACK_GUARD);

  // Declared before SELECTED: parse() reads it during class initialization, and a later
  // declaration is still null at that point -- which shipped as a crash on the default path,
  // caught by the first no-property launch (the retention probe baselines).
  // Set.copyOf: every PTV ProData holds a reference to this set, so a mutable EnumSet here would
  // let one stray remove() silently reconfigure every PTV bot mid-run.
  private static final Set<PtvFeature> SELECTED =
      Set.copyOf(parse(System.getProperty(SYSTEM_PROPERTY)));

  /** The features enabled for this JVM. Read once; an evaluation run does not change mid-flight. */
  public static Set<PtvFeature> selected() {
    return SELECTED;
  }

  @VisibleForTesting
  static Set<PtvFeature> parseForTesting(final String value) {
    return parse(value);
  }

  private static Set<PtvFeature> parse(final String value) {
    if (value == null || value.isBlank()) {
      // The default is the accepted stack, not all-of-them: a sparring game should get exactly
      // the behaviours the measurements support. Logged like every other path so a shard launch
      // that dropped its -D flag leaves a trace.
      log.info(
          "PTV features: accepted stack {} (property {} not set)", ACCEPTED_STACK, SYSTEM_PROPERTY);
      return EnumSet.copyOf(ACCEPTED_STACK);
    }
    if (value.trim().equalsIgnoreCase("none")) {
      log.info("PTV features: none (a ptv bot will behave as stock ProAi)");
      return EnumSet.noneOf(PtvFeature.class);
    }
    final Set<PtvFeature> features = EnumSet.noneOf(PtvFeature.class);
    for (final String token : value.split(",")) {
      final String name = token.trim().toUpperCase(Locale.ROOT);
      if (name.isEmpty()) {
        continue;
      }
      // Deliberately fatal rather than ignored: a typo here would silently measure the wrong bot,
      // and the run costs hours.
      features.add(
          Arrays.stream(values())
              .filter(f -> f.name().equals(name))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Unknown PTV feature '"
                              + token.trim()
                              + "'. Expected a comma-separated subset of "
                              + Arrays.toString(values())
                              + ", or 'none'.")));
    }
    log.info("PTV features: {}", features);
    return features;
  }
}
