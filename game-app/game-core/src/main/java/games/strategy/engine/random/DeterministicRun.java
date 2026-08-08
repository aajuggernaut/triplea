package games.strategy.engine.random;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in switch that removes the run-to-run entropy which otherwise makes headless AI evaluation
 * irreproducible. Off by default; normal play is entirely unaffected.
 *
 * <p>Seeding the game's dice via {@link games.strategy.engine.framework.ServerGame#setRandomSource}
 * is <em>not</em> sufficient to make two runs of the same seed agree, because the AI's decisions
 * depend on four further sources of entropy that sit outside the game's random source:
 *
 * <ol>
 *   <li><b>Battle-simulation dice.</b> {@code DummyDelegateBridge} and {@code
 *       ProDummyDelegateBridge} each construct an unseeded {@link PlainRandomSource}. The AI's
 *       predicted odds therefore differ between runs, and so do the moves it chooses.
 *   <li><b>Worker count.</b> {@code ConcurrentBattleCalculator} splits a simulation's run count
 *       across however many workers its time- and memory-based heuristic picked, so the number of
 *       available cores changes the sample the AI averages over.
 *   <li><b>Unit identity.</b> {@link games.strategy.engine.data.Unit} hashes on a {@link
 *       UUID#randomUUID()} field, so every {@code HashSet<Unit>} and {@code HashMap<Unit, ?>} in
 *       the engine and in the AI iterates in a different order each run. Unit is the only core
 *       game-data type that does this -- {@code Territory}, {@code GamePlayer} and {@code UnitType}
 *       all hash on their name, which is stable.
 *   <li><b>AI policy decisions.</b> The AI reaches for {@link Math#random()}, {@link
 *       ThreadLocalRandom} and the no-argument {@link Collections#shuffle(List)} directly when
 *       choosing which unit to buy, whether to declare war, and how many political actions to
 *       attempt. {@code ProPurchaseUtils.randomizePurchaseOption} alone re-rolls on every purchase.
 *       See {@link #nextDouble()}.
 * </ol>
 *
 * <p>When enabled, all four are pinned to the supplied seed. Two runs of the same seed then play
 * the same game, which makes a patched-vs-stock comparison <em>paired</em>: both arms share an
 * identical prefix until the first decision the patch actually changes, so the variance of the
 * difference is far smaller than the variance of either arm on its own.
 *
 * <p>A fifth source is not randomness at all but hash order: {@code ProPurchaseOption} defines no
 * {@code hashCode}, so the {@code HashMap}s keyed by it iterate in identity-hash order and decide
 * which unit a given random draw buys. That one is fixed at its two call sites -- {@code
 * ProPurchaseUtils.randomizePurchaseOption} sorts, and {@code ProPurchaseOptionMap} uses
 * insertion-ordered sets -- rather than here.
 *
 * <p><b>This changes how the bot plays</b>, because iteration orders and simulation dice differ
 * from an unseeded run. That is harmless for evaluation, where both arms run under the same
 * setting, but results measured here are not directly comparable to results measured without it.
 *
 * <p><b>Determinism assumes single-threaded play.</b> The unit-id sequence is atomic, so it is
 * safe, but only reproducible if units are created in a fixed order -- which holds while one game
 * runs at a time on one thread. The battle calculator keeps running in parallel; see {@link
 * #workerCount()} for why it is pinned rather than serialised.
 */
public final class DeterministicRun {

  private static volatile boolean enabled = false;
  private static volatile long baseSeed = 0L;
  private static final AtomicLong unitIdSequence = new AtomicLong();

  private static final Object policyLock = new Object();
  private static SplittableRandom policyRandom = new SplittableRandom(0L);

  /**
   * Deliberately a machine-independent constant rather than a fraction of the core count, so that
   * two runs of a seed agree across machines and across {@code -PptvEvalCores} settings.
   */
  private static final int DEFAULT_WORKER_COUNT = 4;

  private static volatile int workerCount = DEFAULT_WORKER_COUNT;

  private DeterministicRun() {}

  /** Pins every entropy source listed above to {@code seed} and restarts the unit-id sequence. */
  public static void enable(final long seed) {
    baseSeed = seed;
    unitIdSequence.set(0L);
    synchronized (policyLock) {
      policyRandom = new SplittableRandom(seed ^ 0x2545F4914F6CDD1DL);
    }
    enabled = true;
  }

  /**
   * Replaces {@link Math#random()} in AI policy decisions -- which unit to buy, whether to declare
   * war, how many actions to attempt in a turn.
   *
   * <p>Unlike {@link #seedFor}, this is a <em>sequence</em>, not a context-keyed draw, because
   * these callers sample a distribution repeatedly within one turn and would otherwise get the same
   * answer every time. The consequence is that it is position-dependent: a patch that changes how
   * many purchase draws a player makes shifts every later draw in that game. Battle-simulation
   * dice, which dominate the AI's randomness, are context-keyed and keep their pairing regardless.
   */
  public static double nextDouble() {
    if (!enabled) {
      return Math.random();
    }
    synchronized (policyLock) {
      return policyRandom.nextDouble();
    }
  }

  /** Replaces {@code ThreadLocalRandom.current().nextInt(bound)} in AI policy decisions. */
  public static int nextInt(final int bound) {
    if (!enabled) {
      return ThreadLocalRandom.current().nextInt(bound);
    }
    synchronized (policyLock) {
      return policyRandom.nextInt(bound);
    }
  }

  /** Replaces {@code ThreadLocalRandom.current().nextFloat()} in AI policy decisions. */
  public static float nextFloat() {
    if (!enabled) {
      return ThreadLocalRandom.current().nextFloat();
    }
    synchronized (policyLock) {
      return (float) policyRandom.nextDouble();
    }
  }

  /**
   * Replaces {@link Collections#shuffle(List)}, whose no-argument form draws from a shared unseeded
   * generator. The AI shuffles its candidate actions before picking among them, so an unseeded
   * shuffle alone is enough to make two runs of a seed diverge.
   */
  public static void shuffle(final List<?> list) {
    if (!enabled) {
      Collections.shuffle(list);
      return;
    }
    final long shuffleSeed;
    synchronized (policyLock) {
      shuffleSeed = policyRandom.nextLong();
    }
    Collections.shuffle(list, new Random(shuffleSeed));
  }

  /**
   * How many battle-calculator workers to use while pinned. Stock code sizes the pool from
   * wall-clock copy time, free memory and {@code availableProcessors()}, all of which vary run to
   * run; since the simulation count is split across the pool, that alone changes what the AI
   * predicts.
   *
   * <p>A <em>fixed</em> count is what determinism needs -- not a single worker. Forcing one worker
   * also works but costs roughly 5x in wall clock, which defeats the point of making evaluation
   * cheap enough to run often. Pinning the count instead keeps the parallelism and is strictly
   * better than today's "remember to pass the same -PptvEvalCores" discipline, because it no longer
   * depends on the machine.
   */
  public static int workerCount() {
    return workerCount;
  }

  /** Overrides the pinned worker count. Must match across any runs being compared. */
  public static void setWorkerCount(final int count) {
    if (count < 1) {
      throw new IllegalArgumentException("worker count must be >= 1, got " + count);
    }
    workerCount = count;
  }

  /** Restores stock behaviour. Call between games so a leaked flag cannot affect normal play. */
  public static void disable() {
    enabled = false;
  }

  public static boolean isEnabled() {
    return enabled;
  }

  /**
   * Derives a stable simulation seed from a caller-supplied hash of the thing being simulated.
   *
   * <p>Deliberately keyed on <em>what</em> is being simulated rather than on a call counter: the
   * same battle then draws the same dice in both arms of a comparison, even if the patched bot has
   * made a different number of calculator calls beforehand. A counter would shift every subsequent
   * stream on the first extra call and destroy the pairing.
   */
  public static long seedFor(final long contextHash) {
    return mix64(baseSeed * 0x9E3779B97F4A7C15L + contextHash);
  }

  /**
   * Returns the id for a newly created {@link games.strategy.engine.data.Unit} -- a random UUID
   * normally, or a reproducible one drawn from the seeded sequence when enabled.
   */
  public static UUID newUnitId() {
    if (!enabled) {
      return UUID.randomUUID();
    }
    // The low bits are mixed rather than sequential so that UUID.hashCode() still spreads well,
    // and hash-ordered collections do not degenerate into insertion order.
    return new UUID(mix64(baseSeed ^ 0x5851F42D4C957F2DL), mix64(unitIdSequence.getAndIncrement()));
  }

  /** The splitmix64 finalizer, as used by {@link java.util.SplittableRandom}. */
  private static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
