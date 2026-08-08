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
 * <p>Selected with {@code -Dtriplea.ai.ptv.features=OBJECTIVE_VALUE,MOBILITY_PURCHASE}; the default
 * is all of them, so ordinary play and existing runs are unaffected. {@code none} disables the lot,
 * which is useful as a sanity check: a {@code ptv} bot with no features should be indistinguishable
 * from stock, and if it is not, something other than the features differs.
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
  NO_HIT_ATTACK_GUARD;

  public static final String SYSTEM_PROPERTY = "triplea.ai.ptv.features";

  private static final Set<PtvFeature> SELECTED = parse(System.getProperty(SYSTEM_PROPERTY));

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
      return EnumSet.allOf(PtvFeature.class);
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
