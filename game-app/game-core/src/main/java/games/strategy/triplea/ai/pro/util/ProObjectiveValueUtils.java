package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.IAttachment;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.attachments.RulesAttachment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Values territories by the national-objective income that depends on them.
 *
 * <p>Stock ProAI is blind to national objectives: its only use of {@link RulesAttachment} anywhere
 * is {@code getPlacementAnyTerritory()}, a placement rule. Territory worth is computed purely from
 * {@code TerritoryAttachment.getProduction()} plus victory-city and capital flags. On a map like
 * WW2 Path to Victory, which carries 879 objective attachments, that ignores a large share of both
 * sides' economies -- so the AI will not garrison a territory whose only value is the bonus it
 * unlocks, nor deny the enemy one of theirs.
 *
 * <p>This produces a per-territory bonus expressed in PUs, directly comparable to territory
 * production, so callers can weight it the same way they weight production.
 *
 * <h2>Deliberate approximations</h2>
 *
 * <ul>
 *   <li>Only ownership-based objectives are considered ({@code alliedOwnershipTerritories} and
 *       {@code directOwnershipTerritories}). Objectives conditioned on things like war state or
 *       unit presence carry no territory to attribute value to.
 *   <li>For an all-or-nothing objective the value is split evenly across the listed territories,
 *       rather than modelling the {@code count} threshold. Relative ordering is what matters to the
 *       caller, and an even split preserves it.
 *   <li>Objectives whose territory list is a keyword ({@code original}, {@code enemy}, {@code all},
 *       {@code controlled}) are skipped, since the set is computed at evaluation time.
 *   <li>Every player's objectives count, not just the evaluating player's. A territory named in an
 *       enemy objective is worth holding precisely to deny it, so both raise its value.
 * </ul>
 */
@UtilityClass
public final class ProObjectiveValueUtils {

  /** Objective properties that name territories a player must own. */
  private static final List<String> OWNERSHIP_PROPERTIES =
      List.of("alliedOwnershipTerritories", "directOwnershipTerritories");

  /** Stand-ins for a computed territory set rather than a named one. */
  private static final Set<String> TERRITORY_KEYWORDS =
      Set.of("original", "enemy", "all", "controlled", "controlledNoWater");

  /**
   * Returns the objective-derived value, in PUs, of each territory named by any player's
   * ownership-based national objectives. Territories not named by any objective are absent.
   */
  public static Map<Territory, Double> findObjectiveValues(final GameData data) {
    final Map<Territory, Double> values = new HashMap<>();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      for (final IAttachment attachment : player.getAttachments().values()) {
        if (!(attachment instanceof final RulesAttachment rules)) {
          continue;
        }
        final int objectiveValue = rules.getObjectiveValue();
        if (objectiveValue <= 0) {
          continue;
        }
        final boolean countEach = readCountEach(rules);
        for (final String property : OWNERSHIP_PROPERTIES) {
          final List<Territory> territories = readTerritories(rules, property, data);
          if (territories.isEmpty()) {
            continue;
          }
          // countEach pays out per territory held; otherwise the objective pays once for the whole
          // set, so each territory carries a share of it.
          final double perTerritory =
              countEach ? objectiveValue : (double) objectiveValue / territories.size();
          for (final Territory t : territories) {
            values.merge(t, perTerritory, Double::sum);
          }
        }
      }
    }
    return values;
  }

  private static boolean readCountEach(final RulesAttachment rules) {
    return rules
        .getPropertyOrEmpty("countEach")
        .map(MutableProperty::getValue)
        .filter(Boolean.class::isInstance)
        .map(Boolean.class::cast)
        .orElse(false);
  }

  private static List<Territory> readTerritories(
      final RulesAttachment rules, final String property, final GameData data) {
    final Object value =
        rules.getPropertyOrEmpty(property).map(MutableProperty::getValue).orElse(null);
    if (!(value instanceof final String[] names) || names.length == 0) {
      return List.of();
    }
    if (names.length == 1 && TERRITORY_KEYWORDS.contains(names[0])) {
      return List.of();
    }
    final List<Territory> territories = new ArrayList<>(names.length);
    for (final String name : names) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t != null) {
        territories.add(t);
      }
    }
    return territories;
  }
}
