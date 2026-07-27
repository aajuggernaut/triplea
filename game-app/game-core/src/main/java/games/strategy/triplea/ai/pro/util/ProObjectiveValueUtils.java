package games.strategy.triplea.ai.pro.util;

import com.google.common.primitives.Ints;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.IAttachment;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.RelationshipTracker;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.attachments.RulesAttachment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Values territories by the national-objective income that turns on them.
 *
 * <p>Stock ProAI is blind to national objectives: its only use of {@link RulesAttachment} anywhere
 * is {@code getPlacementAnyTerritory()}, a placement rule. Territory worth comes from {@code
 * TerritoryAttachment.getProduction()} plus victory-city and capital flags. WW2 Path to Victory
 * carries 879 objective attachments, so a large share of both sides' economies is invisible.
 *
 * <h2>Marginal value, not an even split</h2>
 *
 * <p>Value is the <b>change in payout</b> from taking or losing a territory, given who holds what
 * right now — not the objective's total divided across its territories. The distinction decides
 * every interesting case:
 *
 * <ul>
 *   <li>Holding 3 of a 4-territory objective, the fourth is worth the <b>entire</b> bonus, not a
 *       quarter of it.
 *   <li>If an enemy holds all four, taking <b>any one</b> destroys their bonus outright, and is
 *       likewise worth the whole thing.
 *   <li>Holding none of the four, no single capture pays anything, so each is worth only a fraction
 *       as progress toward the set.
 * </ul>
 *
 * <p>An even split gets the sign right and the magnitude wrong exactly where decisions are decided.
 * The first implementation did that, and measured no benefit at all in a head-to-head against
 * stock.
 *
 * <p>Because the result depends on current ownership it must be recomputed each turn; {@link
 * games.strategy.triplea.ai.pro.ProData} does so per turn rather than caching statically.
 *
 * <h2>Remaining approximations</h2>
 *
 * <ul>
 *   <li>Only ownership-based objectives count ({@code alliedOwnershipTerritories} and {@code
 *       directOwnershipTerritories}). Objectives keyed on war state or unit presence have no
 *       territory to attribute value to.
 *   <li>Non-territorial conditions attached to the same objective are not evaluated, so an
 *       objective may be counted as reachable when some other condition blocks it.
 *   <li>Inverted objectives are skipped: their territory list does not describe ground worth
 *       holding.
 *   <li>Keyword territory sets ({@code original}, {@code enemy}, {@code all}, {@code controlled})
 *       are skipped, since the set is computed at evaluation time.
 * </ul>
 */
@UtilityClass
public final class ProObjectiveValueUtils {

  /** Objective properties naming territories a player must own, and whether allies count. */
  private static final Map<String, Boolean> OWNERSHIP_PROPERTIES =
      Map.of("alliedOwnershipTerritories", true, "directOwnershipTerritories", false);

  /** Stand-ins for a computed territory set rather than a named one. */
  private static final Set<String> TERRITORY_KEYWORDS =
      Set.of("original", "enemy", "all", "controlled", "controlledNoWater", "each");

  /**
   * Returns how much each territory is worth to {@code viewer} in objective PUs, counting both
   * objectives the viewer can complete and enemy objectives the viewer can deny. Territories no
   * objective turns on are absent.
   */
  public static Map<Territory, Double> findObjectiveValues(
      final GameData data, final GamePlayer viewer) {
    final Map<Territory, Double> values = new HashMap<>();
    final RelationshipTracker relationships = data.getRelationshipTracker();

    for (final GamePlayer owner : data.getPlayerList().getPlayers()) {
      // Objectives of players irrelevant to the viewer -- neutrals it is not fighting -- carry no
      // information about what is worth contesting.
      final boolean isFriendly = owner.equals(viewer) || relationships.isAllied(viewer, owner);
      if (!isFriendly && !relationships.isAtWar(viewer, owner)) {
        continue;
      }
      for (final IAttachment attachment : owner.getAttachments().values()) {
        if (!(attachment instanceof final RulesAttachment rules)) {
          continue;
        }
        final int objectiveValue = rules.getObjectiveValue();
        if (objectiveValue <= 0 || readBooleanProperty(rules, "invert")) {
          continue;
        }
        final boolean countEach = readBooleanProperty(rules, "countEach");
        for (final Map.Entry<String, Boolean> property : OWNERSHIP_PROPERTIES.entrySet()) {
          addMarginalValues(
              values,
              data,
              relationships,
              rules,
              property.getKey(),
              property.getValue(),
              owner,
              objectiveValue,
              countEach);
        }
      }
    }
    return values;
  }

  private static void addMarginalValues(
      final Map<Territory, Double> values,
      final GameData data,
      final RelationshipTracker relationships,
      final RulesAttachment rules,
      final String property,
      final boolean alliesCount,
      final GamePlayer owner,
      final int objectiveValue,
      final boolean countEach) {

    final String[] names = readNames(rules, property);
    if (names == null) {
      return;
    }
    final List<Territory> territories = new ArrayList<>(names.length);
    for (final String name : names) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t != null) {
        territories.add(t);
      }
    }
    if (territories.isEmpty()) {
      return;
    }

    // countEach pays per territory held, so each one independently swings the payout by the full
    // amount and there is no threshold to reason about.
    if (countEach) {
      for (final Territory t : territories) {
        values.merge(t, (double) objectiveValue, Double::sum);
      }
      return;
    }

    // The count threshold arrives as a leading integer in the territory list; absent it, the
    // objective needs the whole set. See AbstractRulesAttachment's territory list parsing.
    final int required = readLeadingCount(names, territories.size());

    int held = 0;
    for (final Territory t : territories) {
      if (satisfiesOwnership(t, owner, alliesCount, relationships)) {
        held++;
      }
    }

    for (final Territory t : territories) {
      final boolean isHeld = satisfiesOwnership(t, owner, alliesCount, relationships);
      final double marginal;
      if (held >= required) {
        // Objective is currently paying. Losing a held territory only matters if it breaks the
        // threshold; slack territories are worth nothing at the margin, and taking more adds
        // nothing.
        marginal = (isHeld && held - 1 < required) ? objectiveValue : 0.0;
      } else if (held == required - 1) {
        // One short: any missing territory completes the set and is worth the whole bonus.
        marginal = isHeld ? 0.0 : objectiveValue;
      } else {
        // Several short: no single capture pays out, so value it as progress toward the set.
        marginal = isHeld ? 0.0 : (double) objectiveValue / (required - held);
      }
      if (marginal > 0.0) {
        values.merge(t, marginal, Double::sum);
      }
    }
  }

  private static boolean satisfiesOwnership(
      final Territory t,
      final GamePlayer owner,
      final boolean alliesCount,
      final RelationshipTracker relationships) {
    final GamePlayer territoryOwner = t.getOwner();
    if (territoryOwner == null) {
      return false;
    }
    return territoryOwner.equals(owner)
        || (alliesCount && relationships.isAllied(owner, territoryOwner));
  }

  /** Reads the count threshold from a leading integer in the list, or defaults to the whole set. */
  private static int readLeadingCount(final String[] names, final int fallback) {
    if (names.length > 0) {
      final Integer parsed = Ints.tryParse(names[0]);
      if (parsed != null && parsed > 0) {
        return parsed;
      }
    }
    return fallback;
  }

  private static String[] readNames(final RulesAttachment rules, final String property) {
    final Object value =
        rules.getPropertyOrEmpty(property).map(MutableProperty::getValue).orElse(null);
    if (!(value instanceof final String[] names) || names.length == 0) {
      return null;
    }
    if (names.length == 1 && TERRITORY_KEYWORDS.contains(names[0])) {
      return null;
    }
    return names;
  }

  private static boolean readBooleanProperty(final RulesAttachment rules, final String property) {
    return rules
        .getPropertyOrEmpty(property)
        .map(MutableProperty::getValue)
        .filter(Boolean.class::isInstance)
        .map(Boolean.class::cast)
        .orElse(false);
  }
}
