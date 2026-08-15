package games.strategy.engine.random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the W22 step-scoping property directly: the draws one game step sees depend only on (seed,
 * round, step name), never on how many draws earlier steps consumed. That is the whole point of the
 * change -- before it, one extra purchase draw in round 2 re-phased every later policy draw of
 * every player, which is why paired seeds measured no tighter than unpaired ones.
 */
class DeterministicRunPolicyScopeTest {

  @AfterEach
  void cleanUp() {
    DeterministicRun.disable();
  }

  @Test
  @DisplayName("a step's draws are unchanged when an earlier step consumes extra draws")
  void extraDrawsInOneStepDoNotShiftTheNext() {
    DeterministicRun.enable(7L);
    DeterministicRun.beginStepPolicyScope(1, "germansPurchase");
    DeterministicRun.nextDouble();
    DeterministicRun.nextDouble();
    DeterministicRun.beginStepPolicyScope(1, "britishPurchase");
    final double british = DeterministicRun.nextDouble();

    DeterministicRun.enable(7L);
    DeterministicRun.beginStepPolicyScope(1, "germansPurchase");
    for (int i = 0; i < 50; i++) {
      DeterministicRun.nextDouble(); // a candidate bot deliberating differently
    }
    DeterministicRun.beginStepPolicyScope(1, "britishPurchase");
    assertThat(DeterministicRun.nextDouble(), is(british));
  }

  @Test
  @DisplayName("the same step replays the same sequence on the same seed")
  void sameContextReproduces() {
    DeterministicRun.enable(7L);
    DeterministicRun.beginStepPolicyScope(3, "japanesePolitics");
    final double first = DeterministicRun.nextDouble();
    final int second = DeterministicRun.nextInt(100);

    DeterministicRun.enable(7L);
    DeterministicRun.beginStepPolicyScope(3, "japanesePolitics");
    assertThat(DeterministicRun.nextDouble(), is(first));
    assertThat(DeterministicRun.nextInt(100), is(second));
  }

  @Test
  @DisplayName("round and step name both key the stream")
  void differentContextsGetDifferentStreams() {
    DeterministicRun.enable(7L);
    DeterministicRun.beginStepPolicyScope(1, "germansPurchase");
    final double r1 = DeterministicRun.nextDouble();
    DeterministicRun.beginStepPolicyScope(2, "germansPurchase");
    final double r2 = DeterministicRun.nextDouble();
    DeterministicRun.beginStepPolicyScope(2, "russiansPurchase");
    final double otherPlayer = DeterministicRun.nextDouble();
    assertThat(r1, is(not(r2)));
    assertThat(r2, is(not(otherPlayer)));
  }

  @Test
  @DisplayName("a different base seed changes every step stream")
  void baseSeedStillMatters() {
    DeterministicRun.enable(7L);
    DeterministicRun.beginStepPolicyScope(1, "germansPurchase");
    final double seedSeven = DeterministicRun.nextDouble();

    DeterministicRun.enable(8L);
    DeterministicRun.beginStepPolicyScope(1, "germansPurchase");
    assertThat(DeterministicRun.nextDouble(), is(not(seedSeven)));
  }

  @Test
  @DisplayName("without a step scope, the legacy global sequence still serves draws")
  void fallbackToGlobalSequenceSurvives() {
    DeterministicRun.enable(7L);
    // No beginStepPolicyScope call: draws must still be deterministic via the global stream.
    final double first = DeterministicRun.nextDouble();
    DeterministicRun.enable(7L);
    assertThat(DeterministicRun.nextDouble(), is(first));
  }
}
