package games.strategy.triplea.odds.calculator;

import static games.strategy.triplea.Constants.EDIT_MODE;

import com.google.common.base.Preconditions;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.TerritoryEffect;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.GameDataUtils;
import games.strategy.engine.random.DeterministicRun;
import games.strategy.triplea.delegate.battle.BattleResults;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.MustFightBattle;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import lombok.Setter;

class BattleCalculator implements IBattleCalculator {
  @Nonnull private final GameData gameData;
  // Use a single TuvCostsCalculator so its computations are cached.
  private final TuvCostsCalculator tuvCalculator = new TuvCostsCalculator();
  @Setter private boolean keepOneAttackingLandUnit = false;
  @Setter private boolean amphibious = false;
  @Setter private int retreatAfterRound = -1;
  @Setter private int retreatAfterXUnitsLeft = -1;
  @Setter private String attackerOrderOfLosses = null;
  @Setter private String defenderOrderOfLosses = null;

  /**
   * This worker's position in {@link ConcurrentBattleCalculator}'s pool. Mixed into the simulation
   * seed under {@link games.strategy.engine.random.DeterministicRun} so the workers sharing a
   * battle draw different dice instead of all replaying the same one.
   */
  @Setter private int workerIndex = 0;

  private volatile boolean cancelled = false;
  private final AtomicBoolean isRunning = new AtomicBoolean(false);

  BattleCalculator(GameData data) {
    gameData =
        GameDataUtils.cloneGameData(data, GameDataManager.Options.forBattleCalculator())
            .orElseThrow();
  }

  BattleCalculator(byte[] data) {
    gameData = GameDataUtils.createGameDataFromBytes(data).orElseThrow();
    gameData.getProperties().set(EDIT_MODE, false);
  }

  @Override
  public AggregateResults calculate(
      final GamePlayer attacker,
      final GamePlayer defender,
      final Territory location,
      final Collection<Unit> attacking,
      final Collection<Unit> defending,
      final Collection<Unit> bombarding,
      final Collection<TerritoryEffect> territoryEffects,
      final boolean retreatWhenOnlyAirLeft,
      final int runCount) {
    Preconditions.checkState(
        !isRunning.getAndSet(true), "Can't calculate while operation is still running!");
    try {
      final GamePlayer attacker2 =
          attacker == null
              ? gameData.getPlayerList().getNullPlayer()
              : gameData.getPlayerList().getPlayerId(attacker.getName());
      final GamePlayer defender2 =
          defender == null
              ? gameData.getPlayerList().getNullPlayer()
              : gameData.getPlayerList().getPlayerId(defender.getName());
      final Territory location2 = gameData.getMap().getTerritoryOrNull(location.getName());
      final Collection<Unit> attackingUnits =
          translateCollectionIntoOtherGameData(attacking, gameData);
      final Collection<Unit> defendingUnits =
          translateCollectionIntoOtherGameData(defending, gameData);
      final Collection<Unit> bombardingUnits =
          translateCollectionIntoOtherGameData(bombarding, gameData);
      final Collection<TerritoryEffect> territoryEffects2 =
          translateCollectionIntoOtherGameData(territoryEffects, gameData);
      gameData.performChange(ChangeFactory.removeUnits(location2, location2.getUnits()));
      gameData.performChange(
          ChangeFactory.addUnits(location2, mergeUnitCollections(attackingUnits, defendingUnits)));
      final long start = System.currentTimeMillis();
      final AggregateResults aggregateResults = new AggregateResults(runCount);
      final BattleTracker battleTracker = new BattleTracker();
      final List<Unit> attackerOrderOfLosses =
          OrderOfLossesInputPanel.getUnitListByOrderOfLoss(
              this.attackerOrderOfLosses, attackingUnits, gameData);
      final List<Unit> defenderOrderOfLosses =
          OrderOfLossesInputPanel.getUnitListByOrderOfLoss(
              this.defenderOrderOfLosses, defendingUnits, gameData);
      final long battleContext =
          battleContextHash(
              attacker2, defender2, location2, attackingUnits, defendingUnits, bombardingUnits);
      for (int i = 0; i < runCount && !cancelled; i++) {
        final long simulationSeed = battleContext * 1_000_003L + workerIndex * 10_007L + i;
        // DummyPlayer's own policy draws must come from this simulation, not from a stream
        // shared with the other workers running concurrently.
        final SplittableRandom enclosingScope = DeterministicRun.beginScope(simulationSeed);
        try {
          final CompositeChange allChanges = new CompositeChange();
          final DummyDelegateBridge bridge =
              new DummyDelegateBridge(
                  attacker2,
                  gameData,
                  allChanges,
                  attackerOrderOfLosses,
                  defenderOrderOfLosses,
                  keepOneAttackingLandUnit,
                  retreatAfterRound,
                  retreatAfterXUnitsLeft,
                  retreatWhenOnlyAirLeft,
                  tuvCalculator,
                  simulationSeed);
          final MustFightBattle battle =
              new MustFightBattle(location2, attacker2, gameData, battleTracker);
          battle.setHeadless(true);
          if (amphibious) {
            attackingUnits.forEach(unit -> unit.setWasAmphibious(true));
          }
          battle.setUnits(
              defendingUnits, attackingUnits, bombardingUnits, defender2, territoryEffects2);
          bridge.setBattle(battle);
          battle.fight(bridge);
          aggregateResults.addResult(new BattleResults(battle, gameData));
          // restore the game to its original state
          gameData.performChange(allChanges.invert());
          battleTracker.clear();
          battleTracker.clearBattleRecords();
        } finally {
          // finally, not a trailing call: a throw here would otherwise leave a stale scope
          // installed on this worker thread and silently seed the next battle from it.
          DeterministicRun.endScope(enclosingScope);
        }
      }
      aggregateResults.setTime(System.currentTimeMillis() - start);
      aggregateResults.setRunThreadName(Thread.currentThread().getName());
      cancelled = false;
      return aggregateResults;
    } finally {
      isRunning.set(false);
    }
  }

  /**
   * Identifies <em>what</em> is being simulated, so {@link
   * games.strategy.engine.random.DeterministicRun} can seed the simulation's dice from the battle
   * rather than from a call counter. Keying on the battle means both arms of a patched-vs-stock
   * comparison roll the same dice for the same battle, even once their calculator call counts have
   * diverged.
   *
   * <p>Unit contributions are summed rather than folded in sequence, so the hash does not depend on
   * the order the caller happened to build its collections in.
   */
  private static long battleContextHash(
      final GamePlayer attacker,
      final GamePlayer defender,
      final Territory location,
      final Collection<Unit> attacking,
      final Collection<Unit> defending,
      final Collection<Unit> bombarding) {
    long hash = location.getName().hashCode();
    hash = hash * 31L + attacker.getName().hashCode();
    hash = hash * 31L + defender.getName().hashCode();
    hash = hash * 31L + unitsHash(attacking);
    hash = hash * 31L + unitsHash(defending) * 7L;
    hash = hash * 31L + unitsHash(bombarding) * 13L;
    return hash;
  }

  private static long unitsHash(final Collection<Unit> units) {
    long hash = 0;
    for (final Unit unit : units) {
      hash +=
          unit.getType().getName().hashCode() * 31L + unit.getHits() * 7L + unit.getUnitDamage();
    }
    return hash;
  }

  private <T> Collection<T> translateCollectionIntoOtherGameData(
      Collection<T> collection, GameData otherData) {
    // translateIntoOtherGameData() uses serialization, so if the collection is not serializable,
    // copy it into one that is. In particular, HashMap.keySet() and similar are not serializable.
    if (!(collection instanceof Serializable)) {
      collection = new ArrayList<>(collection);
    }
    return GameDataUtils.translateIntoOtherGameData(collection, otherData);
  }

  private Collection<Unit> mergeUnitCollections(Collection<Unit> c1, Collection<Unit> c2) {
    // LinkedHashSet, not HashSet: Unit hashes on a random id, so a plain HashSet would hand the
    // battle its units in a different order every run. Still rejects duplicates below.
    var combined = new LinkedHashSet<>(c1);
    combined.addAll(c2);
    Preconditions.checkState(
        combined.size() == c1.size() + c2.size(),
        "Attackers and defenders collections must be distinct with no duplicates. "
            + "This helps catch logic errors in AI code that would otherwise be hard to debug.");
    return combined;
  }

  public void cancel() {
    cancelled = true;
  }
}
