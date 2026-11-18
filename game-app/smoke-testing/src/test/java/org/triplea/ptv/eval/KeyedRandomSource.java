package org.triplea.ptv.eval;

import games.strategy.engine.random.IRandomSource;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Game dice keyed on <em>what</em> is being rolled, for paired comparisons ({@code --rng keyed}).
 *
 * <p>{@link SeededRandomSource} is one sequence per game: once two arms roll a different number of
 * dice -- one extra battle, one more defender -- every later roll differs, and the two games stop
 * sharing their luck. Batch 1 of the LLM-agent project measured the result: paired Axis VCs
 * uncorrelated (r = -0.12), so pairing bought nothing.
 *
 * <p>Here each call draws from its own stream, seeded from the game seed, the game round and step
 * (supplied by the caller, e.g. {@code "3|germansBattle"}), the roll's annotation with any unit
 * list removed, and how many times that same key has already been rolled this game. The same battle
 * in the same round therefore rolls the same dice in both arms even if one arm brought other units
 * or fought elsewhere first; with more units it rolls the same first dice plus extra ones. Dice
 * distributions are unchanged: each stream is a {@link SplittableRandom} (splitmix64), the
 * algorithm {@code PlainRandomSource} uses.
 *
 * <p>Not thread-safe by design beyond a lock: real game dice are rolled on the game thread.
 */
public final class KeyedRandomSource implements IRandomSource {
  /**
   * {@code DiceRoll.getAnnotation}: "Germans roll dice for 3 infantry, 2 artillery in Ukraine,
   * round 2". The unit list changes when an arm buys differently, so it is dropped from the key.
   * Greedy {@code .+} before " in " takes the last " in ", so unit names cannot be mistaken for the
   * territory.
   */
  private static final Pattern BATTLE_DICE =
      Pattern.compile("^(.+?) roll dice for .+ in (.+), round (\\d+)$");

  private final long seed;
  private final Supplier<String> stepContext;
  private final Map<String, Integer> occurrences = new HashMap<>();
  private final Object lock = new Object();

  /**
   * @param seed the game seed
   * @param stepContext the current game round and step, e.g. {@code round + "|" + stepName}
   */
  public KeyedRandomSource(final long seed, final Supplier<String> stepContext) {
    this.seed = seed;
    this.stepContext = stepContext;
  }

  /** The annotation with any battle unit list removed; other annotations pass unchanged. */
  static String normalize(final String annotation) {
    if (annotation == null) {
      return "";
    }
    final Matcher m = BATTLE_DICE.matcher(annotation);
    return m.matches()
        ? m.group(1) + " roll dice in " + m.group(2) + ", round " + m.group(3)
        : annotation;
  }

  @Override
  public int getRandom(final int max, final String annotation) {
    return getRandom(max, 1, annotation)[0];
  }

  @Override
  public int[] getRandom(final int max, final int count, final String annotation) {
    if (max <= 0) {
      throw new IllegalArgumentException("max must be > 0 (" + annotation + ")");
    }
    if (count <= 0) {
      throw new IllegalArgumentException("count must be > 0 (" + annotation + ")");
    }
    final String key = stepContext.get() + "|" + normalize(annotation);
    final int occurrence;
    synchronized (lock) {
      occurrence = occurrences.merge(key, 1, Integer::sum) - 1;
    }
    final SplittableRandom random = new SplittableRandom(streamSeed(seed, key, occurrence));
    final int[] numbers = new int[count];
    for (int i = 0; i < count; i++) {
      numbers[i] = random.nextInt(max);
    }
    return numbers;
  }

  static long streamSeed(final long seed, final String key, final int occurrence) {
    return mix64(
        mix64(seed ^ 0x6A09E667F3BCC909L) + fnv1a64(key) * 0x9E3779B97F4A7C15L + occurrence);
  }

  /** 64-bit FNV-1a over UTF-8: stable across JVMs, unlike identity hashes. */
  private static long fnv1a64(final String s) {
    long hash = 0xCBF29CE484222325L;
    for (final byte b : s.getBytes(StandardCharsets.UTF_8)) {
      hash ^= b & 0xFF;
      hash *= 0x100000001B3L;
    }
    return hash;
  }

  /** The splitmix64 finalizer. */
  private static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
