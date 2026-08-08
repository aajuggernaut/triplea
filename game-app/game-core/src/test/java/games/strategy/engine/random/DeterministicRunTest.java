package games.strategy.engine.random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DeterministicRunTest {

  @AfterEach
  void tearDown() {
    DeterministicRun.disable();
  }

  private static List<UUID> unitIds(final int count) {
    final List<UUID> ids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ids.add(DeterministicRun.newUnitId());
    }
    return ids;
  }

  @Nested
  @DisplayName("disabled by default, so normal play is unaffected")
  class Disabled {
    @Test
    void notEnabledUnlessAskedFor() {
      assertThat(DeterministicRun.isEnabled(), is(false));
    }

    @Test
    void unitIdsAreRandom() {
      assertThat(unitIds(50), is(not(unitIds(50))));
    }

    @Test
    void disableRestoresRandomIds() {
      DeterministicRun.enable(7L);
      final List<UUID> seeded = unitIds(20);
      DeterministicRun.disable();
      assertThat(unitIds(20), is(not(seeded)));
    }
  }

  @Nested
  @DisplayName("when enabled, every entropy source replays")
  class Enabled {
    @Test
    void unitIdsReplayForTheSameSeed() {
      DeterministicRun.enable(2000L);
      final List<UUID> first = unitIds(200);
      DeterministicRun.enable(2000L);
      assertThat(unitIds(200), is(first));
    }

    @Test
    void unitIdsDifferBetweenSeeds() {
      DeterministicRun.enable(2000L);
      final List<UUID> first = unitIds(200);
      DeterministicRun.enable(2001L);
      assertThat(unitIds(200), is(not(first)));
    }

    @Test
    void unitIdsAreDistinct() {
      DeterministicRun.enable(2000L);
      final List<UUID> ids = unitIds(5000);
      assertThat(Set.copyOf(ids).size(), is(ids.size()));
    }

    @Test
    @DisplayName("ids spread across hash buckets, so hash-ordered sets do not degenerate")
    void unitIdsHashWell() {
      DeterministicRun.enable(2000L);
      final long distinctBuckets =
          unitIds(1000).stream().map(id -> id.hashCode() & 1023).distinct().count();
      assertThat(distinctBuckets, is(greaterThan(500L)));
    }

    @Test
    void simulationSeedsDependOnContextAndRunSeed() {
      DeterministicRun.enable(2000L);
      final long a = DeterministicRun.seedFor(12345L);
      assertThat("same context replays", DeterministicRun.seedFor(12345L), is(a));
      assertThat("different context differs", DeterministicRun.seedFor(12346L), is(not(a)));
      DeterministicRun.enable(2001L);
      assertThat("different run seed differs", DeterministicRun.seedFor(12345L), is(not(a)));
    }

    @Test
    @DisplayName("a context's seed does not shift when other contexts are drawn first")
    void seedsAreNotPositionDependent() {
      DeterministicRun.enable(2000L);
      final long direct = DeterministicRun.seedFor(999L);
      DeterministicRun.enable(2000L);
      IntStream.range(0, 100).forEach(DeterministicRun::seedFor);
      assertThat(DeterministicRun.seedFor(999L), is(direct));
    }
  }

  @Nested
  class SeededDice {
    @Test
    void sameSeedGivesSameRolls() {
      final List<Integer> first = roll(new PlainRandomSource(42L));
      assertThat(roll(new PlainRandomSource(42L)), is(first));
      assertThat(roll(new PlainRandomSource(43L)), is(not(first)));
    }

    @Test
    void rollsStayInRange() {
      final PlainRandomSource source = new PlainRandomSource(42L);
      assertThat(
          roll(source).stream().filter(v -> v < 0 || v > 5).collect(Collectors.toList()),
          is(List.of()));
    }

    private List<Integer> roll(final PlainRandomSource source) {
      return IntStream.of(source.getRandom(6, 500, "test")).boxed().collect(Collectors.toList());
    }
  }
}
