package games.strategy.triplea.ai.pro.util;

import static java.util.function.Predicate.not;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.AbstractMoveDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TerritoryEffectHelper;
import games.strategy.triplea.delegate.move.validation.MoveValidator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import lombok.experimental.UtilityClass;
import org.triplea.java.PredicateBuilder;

/** Pro AI matches. */
@UtilityClass
public final class ProMatches {

  public static BiPredicate<Territory, Territory> noCanalsBetweenTerritories(
      final GamePlayer player) {
    return (startTerritory, endTerritory) -> {
      final Route r = new Route(startTerritory, endTerritory);
      return new MoveValidator(player.getData(), false).validateCanal(r, null, player) == null;
    };
  }

  public static Predicate<Territory> territoryCanLandAirUnits(
      final GamePlayer player,
      final boolean isCombatMove,
      final List<Territory> enemyTerritories,
      final List<Territory> alliedTerritories) {
    Predicate<Territory> match =
        Matches.airCanLandOnThisAlliedNonConqueredLandTerritory(player)
            .and(
                Matches.territoryIsPassableAndNotRestrictedAndOkByRelationships(
                    player, isCombatMove, false, false, true, true))
            .and(not(enemyTerritories::contains));
    if (!isCombatMove) {
      match =
          match.and(
              Matches.territoryIsNeutralButNotWater()
                  .or(Matches.isTerritoryEnemyAndNotUnownedWaterOrImpassableOrRestricted(player))
                  .negate());
    }
    return ((Predicate<Territory>) alliedTerritories::contains).or(match);
  }

  public static Predicate<Territory> territoryCanMoveAirUnits(
      final GameState data, final GamePlayer player, final boolean isCombatMove) {
    return Matches.territoryDoesNotCostMoneyToEnter(data.getProperties())
        .and(
            Matches.territoryIsPassableAndNotRestrictedAndOkByRelationships(
                player, isCombatMove, false, false, true, false));
  }

  public static Predicate<Territory> territoryCanPotentiallyMoveAirUnits(final GamePlayer player) {
    return Matches.territoryDoesNotCostMoneyToEnter(player.getData().getProperties())
        .and(Matches.territoryIsPassableAndNotRestricted(player));
  }

  public static Predicate<Territory> territoryCanMoveAirUnitsAndNoAa(
      final GameState data, final GamePlayer player, final boolean isCombatMove) {
    return territoryCanMoveAirUnits(data, player, isCombatMove)
        .and(Matches.territoryHasEnemyAaForFlyOver(player).negate());
  }

  public static Predicate<Territory> territoryCanMoveSpecificLandUnit(
      final GamePlayer player, final boolean isCombatMove, final Unit unit) {
    return t -> {
      final Predicate<Territory> territoryMatch =
          Matches.territoryDoesNotCostMoneyToEnter(player.getData().getProperties())
              .and(
                  Matches.territoryIsPassableAndNotRestrictedAndOkByRelationships(
                      player, isCombatMove, true, false, false, false));
      final Predicate<Unit> unitMatch =
          Matches.unitIsOfTypes(
                  TerritoryEffectHelper.getUnitTypesForUnitsNotAllowedIntoTerritory(t))
              .negate();
      return territoryMatch.test(t) && unitMatch.test(unit);
    };
  }

  public static Predicate<Territory> territoryCanPotentiallyMoveSpecificLandUnit(
      final GamePlayer player, final Unit u) {
    return t -> {
      final Predicate<Territory> territoryMatch =
          Matches.territoryDoesNotCostMoneyToEnter(player.getData().getProperties())
              .and(Matches.territoryIsPassableAndNotRestricted(player));
      final Predicate<Unit> unitMatch =
          Matches.unitIsOfTypes(
                  TerritoryEffectHelper.getUnitTypesForUnitsNotAllowedIntoTerritory(t))
              .negate();
      return territoryMatch.test(t) && unitMatch.test(u);
    };
  }

  public static Predicate<Territory> territoryCanMoveLandUnits(
      final GamePlayer player, final boolean isCombatMove) {
    return Matches.territoryDoesNotCostMoneyToEnter(player.getData().getProperties())
        .and(
            Matches.territoryIsPassableAndNotRestrictedAndOkByRelationships(
                player, isCombatMove, true, false, false, false));
  }

  public static Predicate<Territory> territoryCanPotentiallyMoveLandUnits(final GamePlayer player) {
    return Matches.territoryIsLand()
        .and(Matches.territoryDoesNotCostMoneyToEnter(player.getData().getProperties()))
        .and(Matches.territoryIsPassableAndNotRestricted(player));
  }

  public static Predicate<Territory> territoryCanMoveLandUnitsAndIsAllied(final GamePlayer player) {
    return Matches.isTerritoryAllied(player).and(territoryCanMoveLandUnits(player, false));
  }

  public static Predicate<Territory> territoryCanMoveLandUnitsThrough(
      final GamePlayer player,
      final Unit u,
      final Territory startTerritory,
      final boolean isCombatMove,
      final List<Territory> enemyTerritories) {
    return t -> {
      if (isCombatMove
          && Matches.unitCanBlitz().test(u)
          && TerritoryEffectHelper.unitKeepsBlitz(u, startTerritory)) {
        final Predicate<Territory> alliedWithNoEnemiesMatch =
            Matches.isTerritoryAllied(player).and(Matches.territoryHasNoEnemyUnits(player));
        final Predicate<Territory> alliedOrBlitzableMatch =
            alliedWithNoEnemiesMatch.or(territoryIsBlitzable(player, u));
        return territoryCanMoveSpecificLandUnit(player, isCombatMove, u)
            .and(alliedOrBlitzableMatch)
            .and(not(enemyTerritories::contains))
            .test(t);
      }
      return territoryCanMoveSpecificLandUnit(player, isCombatMove, u)
          .and(Matches.isTerritoryAllied(player))
          .and(Matches.territoryHasNoEnemyUnits(player))
          .and(not(enemyTerritories::contains))
          .test(t);
    };
  }

  public static Predicate<Territory> territoryCanMoveLandUnitsThroughIgnoreEnemyUnits(
      final GamePlayer player,
      final Unit u,
      final Territory startTerritory,
      final boolean isCombatMove,
      final List<Territory> blockedTerritories,
      final List<Territory> clearedTerritories) {
    Predicate<Territory> alliedMatch =
        Matches.isTerritoryAllied(player).or(clearedTerritories::contains);
    if (isCombatMove
        && Matches.unitCanBlitz().test(u)
        && TerritoryEffectHelper.unitKeepsBlitz(u, startTerritory)) {
      alliedMatch =
          Matches.isTerritoryAllied(player)
              .or(clearedTerritories::contains)
              .or(territoryIsBlitzable(player, u));
    }
    return territoryCanMoveSpecificLandUnit(player, isCombatMove, u)
        .and(alliedMatch)
        .and(not(blockedTerritories::contains));
  }

  private static Predicate<Territory> territoryIsBlitzable(final GamePlayer player, final Unit u) {
    return t ->
        Matches.territoryIsBlitzable(player).test(t) && TerritoryEffectHelper.unitKeepsBlitz(u, t);
  }

  public static Predicate<Territory> territoryCanMoveSeaUnits(
      final GamePlayer player, final boolean isCombatMove) {
    return t -> {
      final GameProperties properties = player.getData().getProperties();
      final boolean navalMayNotNonComIntoControlled =
          Properties.getWW2V2(properties)
              || Properties.getNavalUnitsMayNotNonCombatMoveIntoControlledSeaZones(properties);
      if (!isCombatMove
          && navalMayNotNonComIntoControlled
          && Matches.isTerritoryEnemyAndNotUnownedWater(player).test(t)) {
        return false;
      }
      final Predicate<Territory> match =
          Matches.territoryDoesNotCostMoneyToEnter(properties)
              .and(
                  Matches.territoryIsPassableAndNotRestrictedAndOkByRelationships(
                      player, isCombatMove, false, true, false, false));
      return match.test(t);
    };
  }

  public static Predicate<Territory> territoryCanMoveSeaUnitsThrough(
      final GamePlayer player, final boolean isCombatMove) {
    return territoryCanMoveSeaUnits(player, isCombatMove).and(territoryHasOnlyIgnoredUnits(player));
  }

  public static Predicate<Territory> territoryCanMoveSeaUnitsThroughOrClearedAndNotInList(
      final GamePlayer player,
      final boolean isCombatMove,
      final List<Territory> clearedTerritories,
      final List<Territory> notTerritories) {
    final Predicate<Territory> onlyIgnoredOrClearedMatch =
        territoryHasOnlyIgnoredUnits(player).or(clearedTerritories::contains);
    return territoryCanMoveSeaUnits(player, isCombatMove)
        .and(onlyIgnoredOrClearedMatch)
        .and(not(notTerritories::contains));
  }

  private static Predicate<Territory> territoryHasOnlyIgnoredUnits(final GamePlayer player) {
    return t -> {
      Predicate<Unit> nonBlockingUnit =
          Matches.unitIsInfrastructure()
              .or(Matches.unitCanBeMovedThroughByEnemies())
              .or(Matches.enemyUnit(player).negate());
      if (Properties.getIgnoreTransportInMovement(player.getData().getProperties())) {
        // Ignore transports or land units they are transporting.
        nonBlockingUnit =
            nonBlockingUnit
                .or(Matches.unitIsSeaTransportButNotCombatSeaTransport())
                .or(Matches.unitIsLand());
      }
      return t.getUnitCollection().allMatch(nonBlockingUnit)
          || Matches.territoryHasNoEnemyUnits(player).test(t);
    };
  }

  public static Predicate<Territory> territoryIsBlockedSea(final GamePlayer player) {
    final Predicate<Unit> transport =
        not(Matches.unitIsSeaTransportButNotCombatSeaTransport()).and(not(Matches.unitIsLand()));
    final Predicate<Unit> unitCond =
        PredicateBuilder.of(Matches.unitIsInfrastructure().negate())
            .and(Matches.alliedUnit(player).negate())
            .and(Matches.unitCanBeMovedThroughByEnemies().negate())
            .andIf(
                Properties.getIgnoreTransportInMovement(player.getData().getProperties()),
                transport)
            .build();
    return Matches.territoryHasUnitsThatMatch(unitCond).negate().and(Matches.territoryIsWater());
  }

  public static Predicate<Territory> territoryHasEnemyUnitsOrCantBeHeld(
      final GamePlayer player, final List<Territory> territoriesThatCantBeHeld) {
    return Matches.territoryHasEnemyUnits(player).or(territoriesThatCantBeHeld::contains);
  }

  public static Predicate<Territory> territoryHasPotentialEnemyUnits(
      final GamePlayer player, final List<GamePlayer> players) {
    return Matches.territoryHasEnemyUnits(player)
        .or(Matches.territoryHasUnitsThatMatch(Matches.unitIsOwnedByAnyOf(players)));
  }

  public static Predicate<Territory> territoryHasNoEnemyUnitsOrCleared(
      final GamePlayer player, final List<Territory> clearedTerritories) {
    return Matches.territoryHasNoEnemyUnits(player).or(clearedTerritories::contains);
  }

  public static Predicate<Territory> territoryIsEnemyOrHasEnemyUnitsOrCantBeHeld(
      final GamePlayer player, final List<Territory> territoriesThatCantBeHeld) {
    return Matches.isTerritoryEnemyAndNotUnownedWater(player)
        .or(Matches.territoryHasEnemyUnits(player))
        .or(territoriesThatCantBeHeld::contains);
  }

  public static Predicate<Territory> territoryHasInfraFactoryAndIsLand() {
    final Predicate<Unit> infraFactory =
        Matches.unitCanProduceUnits().and(Matches.unitIsInfrastructure());
    return Matches.territoryIsLand().and(Matches.territoryHasUnitsThatMatch(infraFactory));
  }

  public static Predicate<Territory> territoryHasInfraFactoryAndIsEnemyLand(
      final GamePlayer player) {
    return territoryHasInfraFactoryAndIsLand().and(Matches.isTerritoryEnemy(player));
  }

  static Predicate<Territory> territoryHasInfraFactoryAndIsOwnedByPlayersOrCantBeHeld(
      final GamePlayer player,
      final List<GamePlayer> players,
      final List<Territory> territoriesThatCantBeHeld) {
    final Predicate<Territory> ownedAndCantBeHeld =
        Matches.isTerritoryOwnedBy(player).and(territoriesThatCantBeHeld::contains);
    final Predicate<Territory> enemyOrOwnedCantBeHeld =
        Matches.isTerritoryOwnedByAnyOf(players).or(ownedAndCantBeHeld);
    return territoryHasInfraFactoryAndIsLand().and(enemyOrOwnedCantBeHeld);
  }

  public static Predicate<Territory> territoryHasFactoryAndIsNotConqueredOwnedLand(
      final GamePlayer player) {
    return territoryIsNotConqueredOwnedLand(player).and(territoryHasFactoryAndIsOwnedLand(player));
  }

  private static Predicate<Territory> territoryHasFactoryAndIsOwnedLand(final GamePlayer player) {
    final Predicate<Unit> factoryMatch =
        Matches.unitIsOwnedBy(player).and(Matches.unitCanProduceUnits());
    return Matches.isTerritoryOwnedBy(player)
        .and(Matches.territoryIsLand())
        .and(Matches.territoryHasUnitsThatMatch(factoryMatch));
  }

  public static Predicate<Territory> territoryHasNonMobileFactoryAndIsNotConqueredOwnedLand(
      final GamePlayer player) {
    return territoryHasNonMobileInfraFactory()
        .and(territoryHasFactoryAndIsNotConqueredOwnedLand(player));
  }

  private static Predicate<Territory> territoryHasNonMobileInfraFactory() {
    final Predicate<Unit> nonMobileInfraFactoryMatch =
        Matches.unitCanProduceUnits()
            .and(Matches.unitIsInfrastructure())
            .and(Matches.unitHasMovementLeft().negate());
    return Matches.territoryHasUnitsThatMatch(nonMobileInfraFactoryMatch);
  }

  public static Predicate<Territory> territoryHasInfraFactoryAndIsOwnedLand(
      final GamePlayer player) {
    final Predicate<Unit> infraFactoryMatch =
        Matches.unitIsOwnedBy(player)
            .and(Matches.unitCanProduceUnits())
            .and(Matches.unitIsInfrastructure());
    return Matches.isTerritoryOwnedBy(player)
        .and(Matches.territoryIsLand())
        .and(Matches.territoryHasUnitsThatMatch(infraFactoryMatch));
  }

  public static Predicate<Territory> territoryHasInfraFactoryAndIsAlliedLand(
      final GamePlayer player) {
    final Predicate<Unit> infraFactoryMatch =
        Matches.unitCanProduceUnits().and(Matches.unitIsInfrastructure());
    return Matches.isTerritoryAllied(player)
        .and(Matches.territoryIsLand())
        .and(Matches.territoryHasUnitsThatMatch(infraFactoryMatch));
  }

  public static Predicate<Territory> territoryHasInfraFactoryAndIsOwnedLandAdjacentToSea(
      final GamePlayer player) {
    return territoryHasInfraFactoryAndIsOwnedLand(player)
        .and(
            Matches.territoryHasNeighborMatching(
                player.getData().getMap(), Matches.territoryIsWater()));
  }

  public static Predicate<Territory> territoryHasNoInfraFactoryAndIsNotConqueredOwnedLand(
      final GamePlayer player) {
    return territoryIsNotConqueredOwnedLand(player)
        .and(territoryHasInfraFactoryAndIsOwnedLand(player).negate());
  }

  public static Predicate<Territory> territoryHasNeighborOwnedByAndHasLandUnit(
      final GameMap gameMap, final List<GamePlayer> players) {
    final Predicate<Territory> territoryMatch =
        Matches.isTerritoryOwnedByAnyOf(players)
            .and(Matches.territoryHasUnitsThatMatch(Matches.unitIsLand()));
    return Matches.territoryHasNeighborMatching(gameMap, territoryMatch);
  }

  /**
   * PTV {@code CLAIM_CUSTODIAL_TERRITORIES} (W28): a land territory owned by a friendly power whose
   * relationship lets this player take ownership by moving in -- the Dutch East Indies mechanic
   * ({@code Custodianship}: allied archetype + {@code canTakeOverOwnedTerritory}). Allied ground is
   * excluded from attack targeting by construction and its production counts for the owner, so
   * without special handling the claim prices at zero and is never made -- while every human game
   * claims the DEI by round 2. Keys off the engine's own relationship flag, not a territory list,
   * so it covers whatever the map marks claimable.
   */
  public static Predicate<Territory> territoryIsClaimableCustodial(
      final GameState data, final GamePlayer player) {
    // Relationships are symmetric pairs, so without this guard the passive shell on the other
    // end of a Friendly_Neutral pairing "claims" the active power's land right back --
    // Neutral_Axis reading as a claimant of every German territory. A passive player takes no
    // turns and can claim nothing.
    if (ProUtils.isPassiveNeutralPlayer(player)) {
      return t -> false;
    }
    return t -> {
      final GamePlayer owner = t.getOwner();
      if (t.isWater() || owner.isNull() || owner.equals(player)) {
        return false;
      }
      return data.getRelationshipTracker().isAllied(player, owner)
          && Matches.relationshipTypeCanTakeOverOwnedTerritory()
              .test(data.getRelationshipTracker().getRelationshipType(player, owner));
    };
  }

  /**
   * PTV {@code DEFEND_AMPHIB_THREAT} (W5/W14): a land territory worth keeping -- production of at
   * least 2 or a victory city -- with sea units of a potential enemy within three water moves of
   * its coast (standard transport movement plus the unload step). This is the sailing counterpart
   * of {@link #territoryHasNeighborOwnedByAndHasLandUnit}: the defend-with-one-unit pass runs
   * before the territory manager computes real enemy attack options, so like the stock land test it
   * must read threat straight off the board. Range matters because of turn order -- a fleet two
   * zones out and a transport three zones out assault on the enemy's next turn without ever being
   * adjacent during this player's planning.
   */
  public static Predicate<Territory> territoryIsValuableAndAmphibiouslyThreatened(
      final GameState data, final GamePlayer player) {
    final Predicate<Unit> potentialEnemySeaPresence =
        Matches.unitIsOwnedByAnyOf(ProUtils.getPotentialEnemyPlayers(player));
    return t -> {
      if (t.isWater()) {
        return false;
      }
      final TerritoryAttachment ta = TerritoryAttachment.get(t).orElse(null);
      if (ta == null || (ta.getProduction() < 2 && ta.getVictoryCity() == 0)) {
        return false;
      }
      return data.getMap().getNeighbors(t, 3, Matches.territoryIsWater()).stream()
          .anyMatch(z -> z.anyUnitsMatch(potentialEnemySeaPresence));
    };
  }

  static Predicate<Territory> territoryIsAlliedLandAndHasNoEnemyNeighbors(final GamePlayer player) {
    final Predicate<Territory> alliedLand =
        territoryCanMoveLandUnits(player, false).and(Matches.isTerritoryAllied(player));
    final Predicate<Territory> hasNoEnemyNeighbors =
        Matches.territoryHasNeighborMatching(
                player.getData().getMap(), territoryIsEnemyNotPassiveNeutralLand(player))
            .negate();
    return alliedLand.and(hasNoEnemyNeighbors);
  }

  public static Predicate<Territory> territoryIsEnemyLand(final GamePlayer player) {
    return territoryCanMoveLandUnits(player, false).and(Matches.isTerritoryEnemy(player));
  }

  public static Predicate<Territory> territoryIsEnemyNotPassiveNeutralLand(
      final GamePlayer player) {
    return territoryIsEnemyLand(player)
        .and(Matches.territoryIsNeutralButNotWater().negate())
        .and(t -> !ProUtils.isPassiveNeutralPlayer(t.getOwner()));
  }

  public static Predicate<Territory> territoryIsEnemyNotNeutralLand(final GamePlayer player) {
    return territoryIsEnemyLand(player)
        .and(Matches.territoryIsNeutralButNotWater().negate())
        .and(t -> !ProUtils.isNeutralPlayer(t.getOwner()));
  }

  public static Predicate<Territory> territoryIsOrAdjacentToEnemyNotNeutralLand(
      final GamePlayer player) {
    final Predicate<Territory> isMatch =
        territoryIsEnemyLand(player)
            .and(Matches.territoryIsNeutralButNotWater().negate())
            .and(t -> !ProUtils.isPassiveNeutralPlayer(t.getOwner()));
    final Predicate<Territory> adjacentMatch =
        territoryCanMoveLandUnits(player, false)
            .and(Matches.territoryHasNeighborMatching(player.getData().getMap(), isMatch));
    return isMatch.or(adjacentMatch);
  }

  public static Predicate<Territory> territoryIsEnemyNotPassiveNeutralOrAllied(
      final GamePlayer player) {
    return territoryIsEnemyNotPassiveNeutralLand(player)
        .or(Matches.territoryIsLand().and(Matches.isTerritoryAllied(player)));
  }

  public static Predicate<Territory> territoryIsEnemyOrCantBeHeld(
      final GamePlayer player, final List<Territory> territoriesThatCantBeHeld) {
    return Matches.isTerritoryEnemyAndNotUnownedWater(player)
        .or(territoriesThatCantBeHeld::contains);
  }

  public static Predicate<Territory> territoryIsPotentialEnemy(
      final GamePlayer player, final List<GamePlayer> players) {
    return Matches.isTerritoryEnemyAndNotUnownedWater(player)
        .or(Matches.isTerritoryOwnedByAnyOf(players));
  }

  public static Predicate<Territory> territoryIsPotentialEnemyOrHasPotentialEnemyUnits(
      final GamePlayer player, final List<GamePlayer> players) {
    return territoryIsPotentialEnemy(player, players)
        .or(territoryHasPotentialEnemyUnits(player, players));
  }

  public static Predicate<Territory> territoryIsEnemyOrCantBeHeldAndIsAdjacentToMyLandUnits(
      final GamePlayer player, final List<Territory> territoriesThatCantBeHeld) {
    final Predicate<Unit> myUnitIsLand = Matches.unitIsOwnedBy(player).and(Matches.unitIsLand());
    final Predicate<Territory> territoryIsLandAndAdjacentToMyLandUnits =
        Matches.territoryIsLand()
            .and(
                Matches.territoryHasNeighborMatching(
                    player.getData().getMap(), Matches.territoryHasUnitsThatMatch(myUnitIsLand)));
    return territoryIsLandAndAdjacentToMyLandUnits.and(
        territoryIsEnemyOrCantBeHeld(player, territoriesThatCantBeHeld));
  }

  public static Predicate<Territory> territoryIsNotConqueredOwnedLand(final GamePlayer player) {
    return t ->
        !AbstractMoveDelegate.getBattleTracker(player.getData()).wasConquered(t)
            && Matches.isTerritoryOwnedBy(player).and(Matches.territoryIsLand()).test(t);
  }

  public static Predicate<Territory> territoryIsWaterAndAdjacentToOwnedFactory(
      final GamePlayer player) {
    final Predicate<Territory> hasOwnedFactoryNeighbor =
        Matches.territoryHasNeighborMatching(
            player.getData().getMap(), ProMatches.territoryHasInfraFactoryAndIsOwnedLand(player));
    return hasOwnedFactoryNeighbor.and(territoryCanMoveSeaUnits(player, true));
  }

  public static Predicate<Unit> unitCanBeMovedAndIsOwned(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player).and(Matches.unitHasMovementLeft());
  }

  public static Predicate<Unit> unitCanBeMovedAndIsOwnedAir(
      final GamePlayer player, final boolean isCombatMove) {
    return u ->
        (!isCombatMove || !Matches.unitCanNotMoveDuringCombatMove().test(u))
            && unitCanBeMovedAndIsOwned(player).and(Matches.unitIsAir()).test(u);
  }

  public static Predicate<Unit> unitCanBeMovedAndIsOwnedLand(
      final GamePlayer player, final boolean isCombatMove) {
    return u ->
        (!isCombatMove || !Matches.unitCanNotMoveDuringCombatMove().test(u))
            && unitCanBeMovedAndIsOwned(player)
                .and(Matches.unitIsLand())
                .and(Matches.unitIsBeingTransported().negate())
                .test(u);
  }

  public static Predicate<Unit> unitCanBeMovedAndIsOwnedSea(
      final GamePlayer player, final boolean isCombatMove) {
    return u ->
        (!isCombatMove || !Matches.unitCanNotMoveDuringCombatMove().test(u))
            && unitCanBeMovedAndIsOwned(player).and(Matches.unitIsSea()).test(u);
  }

  public static Predicate<Unit> unitCanBeMovedAndIsOwnedTransport(
      final GamePlayer player, final boolean isCombatMove) {
    return u ->
        (!isCombatMove || !Matches.unitCanNotMoveDuringCombatMove().test(u))
            && unitCanBeMovedAndIsOwned(player).and(Matches.unitIsSeaTransport()).test(u);
  }

  public static Predicate<Unit> unitCanBeMovedAndIsOwnedBombard(final GamePlayer player) {
    return u ->
        !Matches.unitCanNotMoveDuringCombatMove().test(u)
            && unitCanBeMovedAndIsOwned(player).and(Matches.unitCanBombard(player)).test(u);
  }

  public static Predicate<Unit> unitCantBeMovedAndIsAlliedDefender(
      final GamePlayer player, final Territory t) {
    final Predicate<Unit> myUnitHasNoMovementMatch =
        Matches.unitIsOwnedBy(player).and(Matches.unitHasMovementLeft().negate());
    final Predicate<Unit> alliedUnitMatch =
        Matches.unitIsOwnedBy(player)
            .negate()
            .and(Matches.isUnitAllied(player))
            .and(
                Matches.unitIsBeingTransportedByOrIsDependentOfSomeUnitInThisList(
                        t.getUnits(), player, false)
                    .negate());
    return myUnitHasNoMovementMatch.or(alliedUnitMatch);
  }

  public static Predicate<Unit> unitCantBeMovedAndIsAlliedDefenderAndNotInfra(
      final GamePlayer player, final Territory t) {
    return unitCantBeMovedAndIsAlliedDefender(player, t).and(Matches.unitIsNotInfrastructure());
  }

  public static Predicate<Unit> unitIsAlliedLandAndNotInfra(final GamePlayer player) {
    return Matches.unitIsLand()
        .and(Matches.isUnitAllied(player))
        .and(Matches.unitIsNotInfrastructure());
  }

  public static Predicate<Unit> unitIsAlliedNotOwned(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player).negate().and(Matches.isUnitAllied(player));
  }

  public static Predicate<Unit> unitIsAlliedNotOwnedAir(final GamePlayer player) {
    return unitIsAlliedNotOwned(player).and(Matches.unitIsAir());
  }

  static Predicate<Unit> unitIsAlliedAir(final GamePlayer player) {
    return Matches.isUnitAllied(player).and(Matches.unitIsAir());
  }

  public static Predicate<Unit> unitIsEnemyAir(final GamePlayer player) {
    return Matches.enemyUnit(player).and(Matches.unitIsAir());
  }

  public static Predicate<Unit> unitIsEnemyAndNotInfa(final GamePlayer player) {
    return Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure());
  }

  public static Predicate<Unit> unitIsEnemyNotLand(final GamePlayer player) {
    return Matches.enemyUnit(player).and(Matches.unitIsNotLand());
  }

  static Predicate<Unit> unitIsEnemyNotNeutral(final GamePlayer player) {
    return Matches.enemyUnit(player).and(unitIsNeutral().negate());
  }

  private static Predicate<Unit> unitIsNeutral() {
    return u -> ProUtils.isNeutralPlayer(u.getOwner());
  }

  static Predicate<Unit> unitIsOwnedAir(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player).and(Matches.unitIsAir());
  }

  public static Predicate<Unit> unitIsOwnedAndMatchesTypeAndIsTransporting(
      final GamePlayer player, final UnitType unitType) {
    return Matches.unitIsOwnedBy(player)
        .and(Matches.unitIsOfType(unitType))
        .and(Unit::isTransporting);
  }

  public static Predicate<Unit> unitIsOwnedAndMatchesTypeAndNotTransporting(
      final GamePlayer player, final UnitType unitType) {
    return Matches.unitIsOwnedBy(player)
        .and(Matches.unitIsOfType(unitType))
        .and(Predicate.not(Unit::isTransporting));
  }

  public static Predicate<Unit> unitIsOwnedCarrier(final GamePlayer player) {
    return unit ->
        unit.getUnitAttachment().getCarrierCapacity() != -1
            && Matches.unitIsOwnedBy(player).test(unit);
  }

  public static Predicate<Unit> unitIsOwnedNotLand(final GamePlayer player) {
    return Matches.unitIsNotLand().and(Matches.unitIsOwnedBy(player));
  }

  public static Predicate<Unit> unitIsOwnedTransport(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player).and(Matches.unitIsSeaTransport());
  }

  public static Predicate<Unit> unitIsOwnedTransportableUnit(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player)
        .and(Matches.unitCanBeTransported())
        .and(Matches.unitCanMove());
  }

  public static Predicate<Unit> unitIsOwnedCombatTransportableUnit(final GamePlayer player) {
    return unitIsOwnedTransportableUnit(player)
        .and(Matches.unitCanNotMoveDuringCombatMove().negate());
  }

  public static Predicate<Unit> unitIsOwnedTransportableUnitAndCanBeLoaded(
      final GamePlayer player, final Unit transport, final boolean isCombatMove) {
    return u ->
        (!isCombatMove
                || (!Matches.unitCanNotMoveDuringCombatMove().test(u)
                    && u.getUnitAttachment().canInvadeFrom(transport)))
            && unitIsOwnedTransportableUnit(player)
                .and(Matches.unitHasNotMoved())
                .and(Matches.unitHasMovementLeft())
                .and(Matches.unitIsBeingTransported().negate())
                .test(u);
  }

  public static Predicate<Unit> unitHasLessMovementThan(final Unit unit) {
    return u -> u.getMovementLeft().compareTo(unit.getMovementLeft()) < 0;
  }
}
