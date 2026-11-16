package games.strategy.triplea.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.ProductionFrontier;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RelationshipTracker;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.UnitUtils;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import games.strategy.triplea.util.TuvUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.triplea.java.collections.IntegerMap;

/**
 * Builds the compact situation report an LLM reads before a strategic decision.
 *
 * <p>Everything is read from the game data at runtime -- no map, nation or unit name appears in
 * this class -- so the same builder serves Global 1940, PtV, or a house-rule variant. Numbers are
 * deliberately crude ({@code attack x rolls} summed over units, reach measured in territory steps
 * rather than legal routes): the briefing orients the model; ProAi and the engine still check
 * everything it decides.
 *
 * <p>v0 covers the purchase decision only.
 */
final class LlmBriefingBuilder {
  /**
   * v0.1 (2026-11-16): adds the {@code budget} block and orders placement rows largest first, after
   * the model misread its bank and fixated on a 3-unit factory under v0 (STATUS.md, anchoring
   * review). Every v0 field is unchanged, so v0 consumers keep working.
   */
  static final String SCHEMA = "llm-briefing/v0.1";

  /** Enemy land units within this many land steps count as able to reach a territory. */
  private static final int LAND_REACH = 2;

  /** Enemy air units within this many steps (land or sea) count as able to strike it. */
  private static final int AIR_REACH = 3;

  /** Enemy warships within this many steps count as able to reach a sea zone. */
  private static final int SEA_REACH = 2;

  /** Fronts and targets are each capped at this many rows, most pressing first. */
  private static final int MAX_ROWS = 25;

  private LlmBriefingBuilder() {}

  static JsonObject purchase(final GameData data, final GamePlayer player, final int pusToSpend) {
    final Context ctx = new Context(data, player);
    final JsonObject b = new JsonObject();
    b.addProperty("schema", SCHEMA);
    b.addProperty("decision", "purchase");
    b.addProperty("map", data.getGameName());
    b.addProperty("round", data.getSequence().getRound());
    b.addProperty("step", data.getSequence().getStep().getName());
    b.addProperty("player", player.getName());
    b.add("alliances", strings(data.getAllianceTracker().getAlliancesPlayerIsIn(player)));
    b.addProperty("pus", pusToSpend);
    final JsonArray placement = placement(ctx);
    b.add("budget", budget(data, pusToSpend, placement));
    b.add("atWarWith", strings(ctx.enemies.stream().map(GamePlayer::getName).toList()));
    b.add("powers", powers(ctx));
    b.add("buildable", buildable(ctx));
    final IntegerMap<UnitType> waiting = player.getUnitCollection().getUnitsByType();
    if (!waiting.isEmpty()) {
      b.add("alreadyWaitingToPlace", counts(waiting));
    }
    b.add("placement", placement);
    b.add("landFronts", landFronts(ctx));
    b.add("seaZonesNearFactories", seaZones(ctx));
    b.add("targets", targets(ctx));
    return b;
  }

  /** Units-to-place by type name, for recording what a purchase bought. */
  static JsonObject counts(final IntegerMap<UnitType> units) {
    final JsonObject o = new JsonObject();
    for (final UnitType type : units.keySet()) {
      if (units.getInt(type) != 0) {
        o.addProperty(type.getName(), units.getInt(type));
      }
    }
    return o;
  }

  /** Every power that holds territory or units: bank, income, victory cities, army value. */
  private static JsonArray powers(final Context ctx) {
    final Map<GamePlayer, List<Unit>> unitsByOwner = new HashMap<>();
    final IntegerMap<GamePlayer> production = new IntegerMap<>();
    final IntegerMap<GamePlayer> victoryCities = new IntegerMap<>();
    for (final Territory t : ctx.map.getTerritories()) {
      for (final Unit u : t.getUnits()) {
        unitsByOwner.computeIfAbsent(u.getOwner(), k -> new ArrayList<>()).add(u);
      }
      if (!t.isWater()) {
        production.add(t.getOwner(), TerritoryAttachment.getProduction(t));
        victoryCities.add(
            t.getOwner(),
            TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0));
      }
    }
    final Resource pus = ctx.data.getResourceList().getResourceOrThrow(Constants.PUS);
    final JsonArray arr = new JsonArray();
    for (final GamePlayer p : ctx.data.getPlayerList().getPlayers()) {
      final List<Unit> units = unitsByOwner.getOrDefault(p, List.of());
      if (production.getInt(p) == 0 && units.isEmpty()) {
        continue;
      }
      final JsonObject o = new JsonObject();
      o.addProperty("name", p.getName());
      o.add("alliances", strings(ctx.data.getAllianceTracker().getAlliancesPlayerIsIn(p)));
      o.addProperty("relation", relation(ctx, p));
      o.addProperty("bank", p.getResources().getQuantity(pus));
      o.addProperty("production", production.getInt(p));
      o.addProperty("victoryCities", victoryCities.getInt(p));
      o.addProperty(
          "unitValue", TuvUtils.getTuv(units, new TuvCostsCalculator().getCostsForTuv(p)));
      final List<Territory> capitals = TerritoryAttachment.getAllCapitals(p, ctx.map);
      if (!capitals.isEmpty()) {
        o.addProperty(
            "capitalHeld", capitals.stream().anyMatch(c -> ctx.rel.isAllied(p, c.getOwner())));
      }
      arr.add(o);
    }
    return arr;
  }

  private static String relation(final Context ctx, final GamePlayer p) {
    if (p.equals(ctx.player)) {
      return "self";
    }
    if (ctx.rel.isAtWar(ctx.player, p)) {
      return "war";
    }
    return ctx.rel.isAllied(ctx.player, p) ? "ally" : "neutral";
  }

  /** The player's production frontier: what can be bought, at what price, with what stats. */
  private static JsonArray buildable(final Context ctx) {
    final JsonArray arr = new JsonArray();
    final ProductionFrontier frontier = ctx.player.getProductionFrontier();
    if (frontier == null) {
      return arr;
    }
    final Resource pus = ctx.data.getResourceList().getResourceOrThrow(Constants.PUS);
    for (final ProductionRule rule : frontier.getRules()) {
      final IntegerMap<NamedAttachable> results = rule.getResults();
      for (final NamedAttachable result : results.keySet()) {
        if (!(result instanceof UnitType type)) {
          continue;
        }
        final UnitAttachment ua = type.getUnitAttachment();
        final JsonObject o = new JsonObject();
        o.addProperty("unit", type.getName());
        o.addProperty("cost", rule.getCosts().getInt(pus));
        if (results.getInt(result) != 1) {
          o.addProperty("quantity", results.getInt(result));
        }
        o.addProperty("domain", ua.isSea() ? "sea" : ua.isAir() ? "air" : "land");
        o.addProperty("attack", ua.getAttack(ctx.player));
        if (ua.getAttackRolls(ctx.player) != 1) {
          o.addProperty("attackRolls", ua.getAttackRolls(ctx.player));
        }
        o.addProperty("defense", ua.getDefense(ctx.player));
        o.addProperty("move", ua.getMovement(ctx.player));
        if (ua.getHitPoints() != 1) {
          o.addProperty("hitPoints", ua.getHitPoints());
        }
        if (ua.getTransportCost() > 0) {
          o.addProperty("transportCost", ua.getTransportCost());
        }
        if (ua.getTransportCapacity() > 0) {
          o.addProperty("transportCapacity", ua.getTransportCapacity());
        }
        if (ua.getCarrierCapacity() > 0) {
          o.addProperty("carrierCapacity", ua.getCarrierCapacity());
        }
        if (ua.isInfrastructure()) {
          o.addProperty("infrastructure", true);
        }
        arr.add(o);
      }
    }
    return arr;
  }

  /**
   * The two numbers a purchase is bounded by, side by side at the top: the whole bank, and the
   * whole placement capacity across every factory. Under v0 the model read a single 3-unit
   * factory's {@code maxUnits} as its limit and quoted budgets that were not in the briefing.
   */
  private static JsonObject budget(
      final GameData data, final int pusToSpend, final JsonArray placement) {
    long capacity = 0;
    for (final JsonElement row : placement) {
      capacity += row.getAsJsonObject().get("maxUnits").getAsLong();
    }
    final JsonObject o = new JsonObject();
    o.addProperty("pusAvailable", pusToSpend);
    o.addProperty("totalPlacementCapacity", capacity);
    o.addProperty("placementTerritories", placement.size());
    o.addProperty(
        "unplacedUnitsCarryOver", data.getProperties().get(Constants.UNPLACED_UNITS_LIVE, false));
    return o;
  }

  /**
   * Where purchased units can be placed and how many per territory, largest capacity first (then by
   * name, for byte-stable output). {@code maxUnits} is per territory; the total is in {@code
   * budget}.
   */
  private static JsonArray placement(final Context ctx) {
    final List<JsonObject> rows = new ArrayList<>();
    final Predicate<Territory> factory =
        ProMatches.territoryHasFactoryAndIsNotConqueredOwnedLand(ctx.player);
    for (final Territory t : ctx.map.getTerritories()) {
      if (!factory.test(t)) {
        continue;
      }
      final JsonObject o = new JsonObject();
      o.addProperty("territory", t.getName());
      o.addProperty(
          "maxUnits",
          UnitUtils.getProductionPotentialOfTerritory(t.getUnits(), t, ctx.player, true, true));
      final List<String> seaZones =
          ctx.map.getNeighbors(t, Matches.territoryIsWater()).stream()
              .map(Territory::getName)
              .sorted()
              .toList();
      if (!seaZones.isEmpty()) {
        o.add("shipsGoTo", strings(seaZones));
      }
      rows.add(o);
    }
    rows.sort(
        Comparator.comparingInt((JsonObject o) -> o.get("maxUnits").getAsInt())
            .reversed()
            .thenComparing(o -> o.get("territory").getAsString()));
    final JsonArray arr = new JsonArray();
    rows.forEach(arr::add);
    return arr;
  }

  /**
   * The player's own land territories that enemy land or air units could reach next turn, with the
   * defence already there. Sorted by pressure (enemy power minus defence), most exposed first.
   */
  private static JsonArray landFronts(final Context ctx) {
    final List<JsonObject> rows = new ArrayList<>();
    for (final Territory t : ctx.map.getTerritories()) {
      if (t.isWater() || !t.isOwnedBy(ctx.player)) {
        continue;
      }
      final int enemyLand = enemyLandPowerNear(ctx, t);
      final int enemyAir = enemyAirPowerNear(ctx, t);
      if (enemyLand + enemyAir == 0) {
        continue;
      }
      final int defense = defensePower(t.getMatches(ctx.friendlyUnit));
      final JsonObject o = new JsonObject();
      o.addProperty("territory", t.getName());
      addValue(o, t);
      o.addProperty("defense", defense);
      o.addProperty("enemyLandNear", enemyLand);
      o.addProperty("enemyAirNear", enemyAir);
      o.addProperty("pressure", enemyLand + enemyAir - defense);
      rows.add(o);
    }
    return sortedTop(rows, "pressure");
  }

  /** Sea zones next to the player's factories: whether new ships would survive there. */
  private static JsonArray seaZones(final Context ctx) {
    // Sorted: getNeighbors returns a HashSet, and briefings must be byte-stable across replays
    // because the agent caches answers by briefing hash.
    final Set<Territory> zones = new TreeSet<>(Comparator.comparing(Territory::getName));
    final Predicate<Territory> factory =
        ProMatches.territoryHasFactoryAndIsNotConqueredOwnedLand(ctx.player);
    for (final Territory t : ctx.map.getTerritories()) {
      if (factory.test(t)) {
        zones.addAll(ctx.map.getNeighbors(t, Matches.territoryIsWater()));
      }
    }
    final JsonArray arr = new JsonArray();
    for (final Territory zone : zones) {
      final JsonObject o = new JsonObject();
      o.addProperty("seaZone", zone.getName());
      o.addProperty(
          "friendlyNavalDefense",
          defensePower(zone.getMatches(ctx.friendlyUnit.and(Matches.unitIsSea()))));
      o.addProperty(
          "enemyNavalNear",
          attackPower(
              unitsWithin(
                  ctx,
                  ctx.map.getNeighbors(zone, SEA_REACH, Matches.territoryIsWater()),
                  zone,
                  ctx.enemyUnit.and(Matches.unitIsSea()))));
      o.addProperty("enemyAirNear", enemyAirPowerNear(ctx, zone));
      arr.add(o);
    }
    return arr;
  }

  /**
   * Enemy land territories bordering the player's own, with their defence and the friendly attack
   * power within reach. Sorted by value (production, then victory city), most valuable first.
   */
  private static JsonArray targets(final Context ctx) {
    final Set<Territory> candidates = new LinkedHashSet<>();
    for (final Territory t : ctx.map.getTerritories()) {
      if (!t.isWater() && t.isOwnedBy(ctx.player)) {
        for (final Territory n : ctx.map.getNeighbors(t, Matches.territoryIsLand())) {
          if (ctx.enemies.contains(n.getOwner())) {
            candidates.add(n);
          }
        }
      }
    }
    final List<JsonObject> rows = new ArrayList<>();
    for (final Territory t : candidates) {
      final JsonObject o = new JsonObject();
      o.addProperty("territory", t.getName());
      o.addProperty("owner", t.getOwner().getName());
      final int value = addValue(o, t);
      o.addProperty("defense", defensePower(t.getMatches(ctx.enemyUnit)));
      o.addProperty(
          "friendlyAttackNear",
          attackPower(
              unitsWithin(
                  ctx,
                  ctx.map.getNeighbors(t, LAND_REACH, Matches.territoryIsLand()),
                  null,
                  ctx.friendlyUnit.and(Matches.unitIsLand()))));
      o.addProperty("value", value);
      rows.add(o);
    }
    return sortedTop(rows, "value");
  }

  private static int enemyLandPowerNear(final Context ctx, final Territory t) {
    final Collection<Territory> overland =
        ctx.map.getNeighbors(t, LAND_REACH, Matches.territoryIsLand());
    // Land units already loaded on transports in an adjacent sea zone can land next turn.
    final Collection<Territory> afloat = ctx.map.getNeighbors(t, Matches.territoryIsWater());
    return attackPower(unitsWithin(ctx, overland, null, ctx.enemyUnit.and(Matches.unitIsLand())))
        + attackPower(unitsWithin(ctx, afloat, null, ctx.enemyUnit.and(Matches.unitIsLand())));
  }

  private static int enemyAirPowerNear(final Context ctx, final Territory t) {
    return attackPower(
        unitsWithin(
            ctx, ctx.map.getNeighbors(t, AIR_REACH), t, ctx.enemyUnit.and(Matches.unitIsAir())));
  }

  private static List<Unit> unitsWithin(
      final Context ctx,
      final Collection<Territory> territories,
      final Territory alsoInclude,
      final Predicate<Unit> match) {
    final List<Unit> units = new ArrayList<>();
    for (final Territory t : territories) {
      units.addAll(t.getMatches(match));
    }
    if (alsoInclude != null) {
      units.addAll(alsoInclude.getMatches(match));
    }
    return units;
  }

  /** Production, victory city and capital flags of a territory; returns a sortable value. */
  private static int addValue(final JsonObject o, final Territory t) {
    final int production = TerritoryAttachment.getProduction(t);
    final int victoryCity =
        TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0);
    final boolean capital =
        TerritoryAttachment.get(t).map(TerritoryAttachment::isCapital).orElse(false);
    if (production != 0) {
      o.addProperty("production", production);
    }
    if (victoryCity != 0) {
      o.addProperty("victoryCity", true);
    }
    if (capital) {
      o.addProperty("capital", true);
    }
    if (t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
      o.addProperty("factory", true);
    }
    return production + (victoryCity != 0 ? 10 : 0) + (capital ? 20 : 0);
  }

  private static int attackPower(final Collection<Unit> units) {
    int power = 0;
    for (final Unit u : units) {
      final UnitAttachment ua = u.getUnitAttachment();
      if (!ua.isInfrastructure()) {
        power += ua.getAttack(u.getOwner()) * Math.max(1, ua.getAttackRolls(u.getOwner()));
      }
    }
    return power;
  }

  private static int defensePower(final Collection<Unit> units) {
    int power = 0;
    for (final Unit u : units) {
      final UnitAttachment ua = u.getUnitAttachment();
      if (!ua.isInfrastructure()) {
        power += ua.getDefense(u.getOwner());
      }
    }
    return power;
  }

  private static JsonArray sortedTop(final List<JsonObject> rows, final String key) {
    rows.sort(
        Comparator.comparingInt((JsonObject o) -> o.get(key).getAsInt())
            .reversed()
            .thenComparing(o -> o.get("territory").getAsString()));
    final JsonArray arr = new JsonArray();
    rows.stream().limit(MAX_ROWS).forEach(arr::add);
    return arr;
  }

  private static JsonArray strings(final Collection<String> values) {
    final JsonArray arr = new JsonArray();
    values.forEach(arr::add);
    return arr;
  }

  /** Per-briefing lookups that every section needs. */
  private static final class Context {
    final GameData data;
    final GameMap map;
    final GamePlayer player;
    final RelationshipTracker rel;
    final Set<GamePlayer> enemies = new LinkedHashSet<>();
    final Predicate<Unit> enemyUnit;
    final Predicate<Unit> friendlyUnit;

    Context(final GameData data, final GamePlayer player) {
      this.data = data;
      this.map = data.getMap();
      this.player = player;
      this.rel = data.getRelationshipTracker();
      for (final GamePlayer p : data.getPlayerList().getPlayers()) {
        if (!p.equals(player) && rel.isAtWar(player, p)) {
          enemies.add(p);
        }
      }
      this.enemyUnit = u -> enemies.contains(u.getOwner());
      this.friendlyUnit = u -> rel.isAllied(player, u.getOwner());
    }
  }
}
