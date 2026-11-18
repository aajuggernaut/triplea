package games.strategy.engine.random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Step-keyed unit ids ({@code --rng keyed}): a step's unit ids depend only on (seed, round, step
 * name) and how many units that step itself created -- not on how many units earlier steps created.
 * Off by default, where ids must be exactly the game-wide sequence as before.
 */
class DeterministicRunStepUnitIdTest {

  @AfterEach
  void cleanUp() {
    DeterministicRun.setStepKeyedUnitIds(false);
    DeterministicRun.disable();
  }

  private static List<UUID> ids(final int count) {
    final List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(DeterministicRun.newUnitId());
    }
    return ids;
  }

  /** Germany buys {@code germanUnits} in round 1, then the British buy three. */
  private static List<UUID> britishIdsAfterGermanBuy(final int germanUnits) {
    DeterministicRun.enable(11L);
    ids(5); // map parsing
    DeterministicRun.beginStepPolicyScope(1, "germansPurchase");
    ids(germanUnits);
    DeterministicRun.beginStepPolicyScope(1, "britishPurchase");
    return ids(3);
  }

  @Test
  @DisplayName("keyed: a step's ids do not shift when an earlier step created more units")
  void keyedIdsAreContainedToTheirStep() {
    DeterministicRun.setStepKeyedUnitIds(true);
    assertThat(britishIdsAfterGermanBuy(15), is(britishIdsAfterGermanBuy(9)));
  }

  @Test
  @DisplayName("default: ids are one game-wide sequence, so an earlier step's count shifts them")
  void defaultIdsStayGameWide() {
    assertThat(britishIdsAfterGermanBuy(15), is(not(britishIdsAfterGermanBuy(9))));
  }

  @Test
  @DisplayName("default: step boundaries do not change the ids at all")
  void defaultIdsIgnoreSteps() {
    DeterministicRun.enable(11L);
    final List<UUID> plain = ids(8);

    DeterministicRun.enable(11L);
    final List<UUID> stepped = new ArrayList<>(ids(3));
    DeterministicRun.beginStepPolicyScope(1, "germansPurchase");
    stepped.addAll(ids(5));
    assertThat(stepped, is(plain));
  }

  @Test
  @DisplayName("keyed: units made before the first step keep the game-wide ids")
  void keyedMapParsingIdsMatchDefault() {
    DeterministicRun.enable(11L);
    final List<UUID> plain = ids(5);

    DeterministicRun.setStepKeyedUnitIds(true);
    DeterministicRun.enable(11L);
    assertThat(ids(5), is(plain));
  }

  @Test
  @DisplayName("keyed: ids stay unique across steps, rounds and map parsing")
  void keyedIdsAreUnique() {
    DeterministicRun.setStepKeyedUnitIds(true);
    DeterministicRun.enable(3L);
    final Set<UUID> seen = new HashSet<>(ids(200));
    int total = 200;
    for (int round = 1; round <= 16; round++) {
      for (final String step : List.of("germansPurchase", "germansBattle", "russiansPurchase")) {
        DeterministicRun.beginStepPolicyScope(round, step);
        seen.addAll(ids(40));
        total += 40;
      }
    }
    assertThat(seen.size(), is(total));
  }

  @Test
  @DisplayName("keyed: the game-wide count still advances, so unitIdsIssued keeps its meaning")
  void keyedStillCountsIds() {
    DeterministicRun.setStepKeyedUnitIds(true);
    DeterministicRun.enable(3L);
    ids(4);
    DeterministicRun.beginStepPolicyScope(1, "germansPurchase");
    ids(6);
    assertThat(DeterministicRun.unitIdsIssued(), is(10L));
  }
}
