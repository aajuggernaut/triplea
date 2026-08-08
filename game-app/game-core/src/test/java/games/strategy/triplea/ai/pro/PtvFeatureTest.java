package games.strategy.triplea.ai.pro;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The parser is exercised directly rather than through the system property, because {@link
 * PtvFeature} reads the property once into a static -- deliberately, since an evaluation run must
 * not change its feature set mid-flight.
 */
class PtvFeatureTest {

  /** Mirrors {@code PtvFeature.parse}, which is private; kept in step by the tests below. */
  private static Set<PtvFeature> parse(final String value) {
    return PtvFeature.parseForTesting(value);
  }

  @Nested
  @DisplayName("defaults leave existing behaviour alone")
  class Defaults {
    @Test
    void nullSelectsEverything() {
      assertThat(parse(null), is(EnumSet.allOf(PtvFeature.class)));
    }

    @Test
    void blankSelectsEverything() {
      assertThat(parse("   "), is(EnumSet.allOf(PtvFeature.class)));
    }
  }

  @Nested
  class Selection {
    @Test
    void singleFeature() {
      assertThat(parse("OBJECTIVE_VALUE"), containsInAnyOrder(PtvFeature.OBJECTIVE_VALUE));
    }

    @Test
    void multipleFeatures() {
      assertThat(
          parse("OBJECTIVE_VALUE,AMPHIB_TARGETS"),
          containsInAnyOrder(PtvFeature.OBJECTIVE_VALUE, PtvFeature.AMPHIB_TARGETS));
    }

    @Test
    void toleratesWhitespaceAndCase() {
      assertThat(
          parse(" objective_value , Amphib_Targets "),
          containsInAnyOrder(PtvFeature.OBJECTIVE_VALUE, PtvFeature.AMPHIB_TARGETS));
    }

    @Test
    @DisplayName("'none' gives a ptv bot that should be indistinguishable from stock")
    void noneSelectsNothing() {
      assertThat(parse("none"), is(emptyIterable()));
    }
  }

  @Nested
  class Mistakes {
    @Test
    @DisplayName("a typo fails loudly rather than silently measuring the wrong bot")
    void unknownFeatureThrows() {
      final IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, () -> parse("OBJECTIVE_VALU"));
      assertThat(thrown.getMessage().contains("OBJECTIVE_VALU"), is(true));
    }

    @Test
    void unknownFeatureAmongValidOnesStillThrows() {
      assertThrows(IllegalArgumentException.class, () -> parse("OBJECTIVE_VALUE,NOT_A_FEATURE"));
    }
  }
}
