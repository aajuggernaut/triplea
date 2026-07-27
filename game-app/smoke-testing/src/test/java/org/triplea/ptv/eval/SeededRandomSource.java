package org.triplea.ptv.eval;

import games.strategy.engine.random.IRandomSource;
import java.util.SplittableRandom;

/**
 * A deterministic {@link IRandomSource}, seeded explicitly so that evaluation runs are
 * reproducible.
 *
 * <p>This is the difference between "the patched bot won 6 more games" and "the dice were kinder
 * this time". Stock {@link games.strategy.engine.random.PlainRandomSource} seeds itself, so a
 * win-rate delta measured against it confounds bot changes with dice luck. Replaying the same seed
 * list across iterations removes that confound.
 *
 * <p>Uses {@link SplittableRandom} to match the algorithm (splitmix64) that {@code
 * PlainRandomSource} uses, so dice distributions are identical -- only the seeding differs.
 */
public final class SeededRandomSource implements IRandomSource {
  private final Object lock = new Object();
  private final SplittableRandom random;

  public SeededRandomSource(final long seed) {
    this.random = new SplittableRandom(seed);
  }

  @Override
  public int getRandom(final int max, final String annotation) {
    if (max <= 0) {
      throw new IllegalArgumentException("max must be > 0 (" + annotation + ")");
    }
    synchronized (lock) {
      return random.nextInt(max);
    }
  }

  @Override
  public int[] getRandom(final int max, final int count, final String annotation) {
    if (count <= 0) {
      throw new IllegalArgumentException("count must be > 0 (" + annotation + ")");
    }
    final int[] numbers = new int[count];
    for (int i = 0; i < count; i++) {
      numbers[i] = getRandom(max, annotation);
    }
    return numbers;
  }
}
