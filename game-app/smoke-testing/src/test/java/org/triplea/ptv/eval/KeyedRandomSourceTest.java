package org.triplea.ptv.eval;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The property that makes {@code --rng keyed} worth having: a roll's dice depend on what is being
 * rolled, not on how many rolls came before it. {@link SeededRandomSource} is the contrast.
 */
class KeyedRandomSourceTest {
  private static final String UKRAINE_R1 =
      "Germans roll dice for 6 infantry, 2 artillery in Ukraine, round 1";

  private final AtomicReference<String> step = new AtomicReference<>("3|germansBattle");

  private KeyedRandomSource source() {
    return new KeyedRandomSource(42L, step::get);
  }

  @Test
  @DisplayName("an extra, unrelated roll earlier in the game does not change a later roll")
  void unrelatedRollsDoNotShiftLaterDice() {
    final KeyedRandomSource a = source();
    final int[] plain = a.getRandom(6, 8, UKRAINE_R1);

    final KeyedRandomSource b = source();
    step.set("2|germansBattle");
    b.getRandom(6, 12, "Germans roll dice for 4 armour in Poland, round 1");
    step.set("3|germansBattle");
    b.getRandom(6, 3, "Russians roll dice for 3 infantry in Belarus, round 1");
    assertThat(b.getRandom(6, 8, UKRAINE_R1), is(plain));
  }

  @Test
  @DisplayName("contrast: one sequence per game shifts every later roll after an extra roll")
  void streamDiceShiftAfterAnExtraRoll() {
    final int[] plain = new SeededRandomSource(42L).getRandom(6, 8, UKRAINE_R1);

    final SeededRandomSource b = new SeededRandomSource(42L);
    b.getRandom(6, 3, "Russians roll dice for 3 infantry in Belarus, round 1");
    assertThat(b.getRandom(6, 8, UKRAINE_R1), is(not(plain)));
  }

  @Test
  @DisplayName("the same battle with other units rolls the same first dice, plus extra ones")
  void unitListIsNotPartOfTheKey() {
    final int[] eight = source().getRandom(6, 8, UKRAINE_R1);
    final int[] ten =
        source().getRandom(6, 10, "Germans roll dice for 4 armour, 6 infantry in Ukraine, round 1");
    assertThat(Arrays.copyOf(ten, 8), is(eight));
  }

  @Test
  @DisplayName("normalising keeps player, territory and battle round; other labels pass through")
  void normalize() {
    assertThat(
        KeyedRandomSource.normalize(UKRAINE_R1), is("Germans roll dice in Ukraine, round 1"));
    assertThat(
        KeyedRandomSource.normalize("Germans roll AA dice in Western Germany"),
        is("Germans roll AA dice in Western Germany"));
    assertThat(KeyedRandomSource.normalize(null), is(""));
  }

  @Test
  @DisplayName("the same key rolled again in a step gets fresh dice, reproducibly")
  void repeatedKeyGetsNewDice() {
    final KeyedRandomSource a = source();
    final int[] first = a.getRandom(6, 20, UKRAINE_R1);
    final int[] second = a.getRandom(6, 20, UKRAINE_R1);
    assertThat(second, is(not(first)));

    final KeyedRandomSource b = source();
    b.getRandom(6, 20, UKRAINE_R1);
    assertThat(b.getRandom(6, 20, UKRAINE_R1), is(second));
  }

  @Test
  @DisplayName("round and step are part of the key, and so is the seed")
  void contextAndSeedMatter() {
    final int[] round3 = source().getRandom(6, 20, UKRAINE_R1);
    step.set("4|germansBattle");
    assertThat(source().getRandom(6, 20, UKRAINE_R1), is(not(round3)));
    step.set("3|germansBattle");
    assertThat(new KeyedRandomSource(43L, step::get).getRandom(6, 20, UKRAINE_R1), is(not(round3)));
  }

  @Test
  @DisplayName("values stay in range and are roughly uniform")
  void valuesAreUniform() {
    final KeyedRandomSource s = source();
    final int[] counts = new int[6];
    for (int i = 0; i < 6000; i++) {
      step.set((i / 100) + "|step");
      counts[s.getRandom(6, "roll " + (i % 100))]++;
    }
    final Integer[] boxed = Arrays.stream(counts).boxed().toArray(Integer[]::new);
    assertThat(Arrays.asList(boxed), everyItem(greaterThanOrEqualTo(900)));
    assertThat(Arrays.asList(boxed), everyItem(lessThan(1100)));
  }
}
