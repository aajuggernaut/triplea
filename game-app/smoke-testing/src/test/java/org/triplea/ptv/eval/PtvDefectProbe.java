package org.triplea.ptv.eval;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.util.ProMatches;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts specific, named misbehaviours in a live game state.
 *
 * <p>The point is to make behavioural defects <b>pass/fail in a single game</b> rather than
 * statistical. The material metrics ({@code primary_vc} and friends) need ~26 games per arm to
 * resolve a 2-victory-city change, which is hours; but "did this bot leave loaded transports
 * bobbing next to a friendly beach?" is a yes/no question answerable in one game, and a fix either
 * drives the count down or it does not. Only once a behaviour is confirmed changed is it worth
 * spending the expensive material comparison on whether the change actually helps.
 *
 * <p>Counts are attributed per player so a fix aimed at one power can be seen in isolation.
 */
public final class PtvDefectProbe {

  private PtvDefectProbe() {}

  /** One observed defect, with enough context to go and look at it in a saved game. */
  public record Occurrence(int round, String player, String defect, String detail) {
    @Override
    public String toString() {
      return "r" + round + " " + player + " " + defect + ": " + detail;
    }
  }

  /**
   * Transports that ended the round still carrying cargo while sitting next to land they could have
   * unloaded onto.
   *
   * <p>Deliberately not "any loaded transport at sea": a multi-turn crossing legitimately ends a
   * round loaded, and counting those would drown the signal. The defect is specifically cargo left
   * afloat when a friendly beach was <em>adjacent</em> -- the transport had the option and did not
   * take it, leaving the cargo able to be sunk with the ship.
   *
   * <p>⚠️ Adjacency uses {@link ProMatches#territoryCanMoveLandUnitsAndIsAllied}, the same
   * predicate the AI unloads by, <b>not</b> {@code Matches.isTerritoryAllied}. In PTV the two are
   * very different: the {@code Allied} relationship does not set {@code
   * canMoveLandUnitsOverOwnedLand}, so American ground units cannot enter Soviet territory at all.
   * Scoring adjacency by alliance reported "could unload to Soviet Far East" and called correct
   * play a defect.
   */
  public static List<Occurrence> loadedTransportsNextToFriendlyLand(
      final GameData data, final int round) {
    final List<Occurrence> found = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      for (final Unit unit : t.getUnits()) {
        final List<Unit> cargo = unit.getTransporting();
        if (cargo.isEmpty()) {
          continue;
        }
        final GamePlayer owner = unit.getOwner();
        final List<String> beaches =
            data
                .getMap()
                .getNeighbors(t, ProMatches.territoryCanMoveLandUnitsAndIsAllied(owner))
                .stream()
                .map(Territory::getName)
                .sorted()
                .toList();
        if (!beaches.isEmpty()) {
          found.add(
              new Occurrence(
                  round,
                  owner.getName(),
                  "CARGO_AFLOAT",
                  cargo.size() + " units in " + t.getName() + ", could unload to " + beaches));
        }
      }
    }
    return found;
  }

  /**
   * Every transport ending the round with cargo aboard, adjacent beach or not.
   *
   * <p>The narrower {@link #loadedTransportsNextToFriendlyLand} check counts only provably
   * avoidable cases and so understates the problem: a transport that wandered into open ocean still
   * loaded has no adjacent beach to be blamed for, yet that is the worse outcome, not the better
   * one. This is the raw exposure count; the two together separate "sat next to a beach it ignored"
   * from "took the cargo somewhere with no beach at all".
   */
  public static List<Occurrence> loadedTransportsAtSea(final GameData data, final int round) {
    final List<Occurrence> found = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      for (final Unit unit : t.getUnits()) {
        final List<Unit> cargo = unit.getTransporting();
        if (!cargo.isEmpty()) {
          final boolean beachAdjacent =
              data.getMap().getNeighbors(t).stream()
                  .anyMatch(
                      n ->
                          !n.isWater()
                              && ProMatches.territoryCanMoveLandUnitsAndIsAllied(unit.getOwner())
                                  .test(n));
          found.add(
              new Occurrence(
                  round,
                  unit.getOwner().getName(),
                  "CARGO_AFLOAT_ANY",
                  cargo.size()
                      + " units in "
                      + t.getName()
                      + (beachAdjacent ? " (beach adjacent)" : " (open sea)")));
        }
      }
    }
    return found;
  }

  /**
   * The 16 territories whose PTV guerrilla triggers spawn a Chinese infantry when Japanese-owned
   * and empty of Japanese ground units. Read from the map XML's {@code
   * triggerAttachment_Chinese_Guerilla_*} placement options; the garrison test mirrors the
   * trigger's own {@code unitPresence} list -- ground types only, so an aaGun does not garrison.
   */
  private static final List<String> GUERRILLA_TERRITORIES =
      List.of(
          "Anhwe",
          "Chahar",
          "Eastern Sinkiang",
          "Honan",
          "Hopei",
          "Hunan",
          "Inner Mongolia",
          "Kansu",
          "Kiangsi",
          "Kweichow",
          "Northern Sinkiang",
          "Shansi",
          "Szechwan",
          "Tsinghai",
          "Western Sinkiang",
          "Yunnan");

  private static final List<String> GARRISON_UNIT_TYPES =
      List.of("infantry", "artillery", "mech_infantry", "armour", "marine");

  /**
   * W4 exposure: Japanese-owned guerrilla territories with no Japanese ground garrison at round end
   * -- each is a guaranteed free Chinese infantry next trigger check. Human baseline from the
   * 15-game corpus: ~0.7 actual spawns per game; the bot is measured on the exposure count, which
   * upper-bounds spawns and is readable from the board.
   */
  public static List<Occurrence> ungarrisonedGuerrillaTerritories(
      final GameData data, final int round) {
    final List<Occurrence> found = new ArrayList<>();
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    if (japanese == null) {
      return found;
    }
    for (final String name : GUERRILLA_TERRITORIES) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t == null || !t.getOwner().equals(japanese)) {
        continue;
      }
      final boolean garrisoned =
          t.getUnits().stream()
              .anyMatch(
                  u ->
                      u.getOwner().equals(japanese)
                          && GARRISON_UNIT_TYPES.contains(u.getType().getName()));
      if (!garrisoned) {
        found.add(
            new Occurrence(
                round, japanese.getName(), "GUERRILLA_EXPOSED", name + " held but ungarrisoned"));
      }
    }
    return found;
  }

  /**
   * W12 tempo: first round each watched territory is Japanese-owned. Corpus benchmark:
   * south-committed human Japan takes Manilla r1-r2 (8 of 10-11 games) and Kwangtung r1-r2 (9).
   * Caller keeps {@code alreadyCaptured} across rounds so each capture reports once.
   */
  public static List<Occurrence> japaneseCaptures(
      final GameData data, final int round, final java.util.Set<String> alreadyCaptured) {
    final List<Occurrence> found = new ArrayList<>();
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    if (japanese == null) {
      return found;
    }
    for (final String name : List.of("Manilla", "Kwangtung", "Davao")) {
      if (alreadyCaptured.contains(name)) {
        continue;
      }
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t != null && t.getOwner().equals(japanese)) {
        alreadyCaptured.add(name);
        found.add(
            new Occurrence(round, japanese.getName(), "JAPAN_TOOK", name + " by round " + round));
      }
    }
    return found;
  }

  /**
   * W6 exposure: combat ships ending the round in a sea zone with no land neighbor at all -- where
   * no base can support them and nothing can be threatened amphibiously from them. Deliberately raw
   * (a deliberate out-of-range hover also counts): it is an exposure count for A/B comparison, not
   * an accusation per instance.
   */
  public static List<Occurrence> fleetsAdriftAtSea(final GameData data, final int round) {
    final List<Occurrence> found = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      final boolean hasLandNeighbor =
          data.getMap().getNeighbors(t).stream().anyMatch(n -> !n.isWater());
      if (hasLandNeighbor) {
        continue;
      }
      final Map<GamePlayer, Long> shipsByOwner = new LinkedHashMap<>();
      for (final Unit unit : t.getUnits()) {
        final String type = unit.getType().getName();
        if (List.of("destroyer", "cruiser", "carrier", "battleship", "submarine").contains(type)) {
          shipsByOwner.merge(unit.getOwner(), 1L, Long::sum);
        }
      }
      shipsByOwner.forEach(
          (owner, count) ->
              found.add(
                  new Occurrence(
                      round,
                      owner.getName(),
                      "FLEET_ADRIFT",
                      count + " warships in landless " + t.getName())));
    }
    return found;
  }

  /**
   * W5 exposure: owned territories worth keeping (production >= 2 or a victory city) that end the
   * round with no allied ground defender while an enemy stands next door -- capture valued,
   * retention not. Threat = an adjacent enemy-owned land territory, or an adjacent sea zone holding
   * units at war with the owner (amphibious reach approximated by adjacency).
   */
  public static List<Occurrence> undefendedValuableTerritories(
      final GameData data, final int round) {
    final List<Occurrence> found = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || t.getOwner().isNull()) {
        continue;
      }
      final var ta = games.strategy.triplea.attachments.TerritoryAttachment.get(t).orElse(null);
      if (ta == null || (ta.getProduction() < 2 && ta.getVictoryCity() == 0)) {
        continue;
      }
      final GamePlayer owner = t.getOwner();
      final boolean defended =
          t.getUnits().stream().anyMatch(ProMatches.unitIsAlliedLandAndNotInfra(owner));
      if (defended) {
        continue;
      }
      final boolean threatened =
          data.getMap().getNeighbors(t).stream()
              .anyMatch(
                  n ->
                      (!n.isWater()
                              && !n.getOwner().isNull()
                              && data.getRelationshipTracker().isAtWar(owner, n.getOwner()))
                          || (n.isWater()
                              && n.getUnits().stream()
                                  .anyMatch(
                                      u ->
                                          data.getRelationshipTracker()
                                              .isAtWar(owner, u.getOwner()))));
      if (threatened) {
        found.add(
            new Occurrence(
                round,
                owner.getName(),
                "UNDEFENDED_VALUE",
                t.getName()
                    + " (prod "
                    + ta.getProduction()
                    + (ta.getVictoryCity() > 0 ? ", VC" : "")
                    + ") empty with enemy adjacent"));
      }
    }
    return found;
  }

  /**
   * W5 churn: a territory that changed hands and flipped back within two rounds -- ground taken and
   * immediately lost, the sharpest board-readable retention signature. Caller keeps both maps
   * across rounds: {@code owners} is last round's owner per territory, {@code captures} records
   * (round, capturer-side marker) for the flip-back window.
   */
  public static List<Occurrence> retakenFast(
      final Map<String, String> owners,
      final Map<String, Integer> captures,
      final GameData data,
      final int round) {
    final List<Occurrence> found = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        continue;
      }
      final String now = t.getOwner().getName();
      final String before = owners.put(t.getName(), now);
      if (before == null || before.equals(now)) {
        continue;
      }
      final Integer capturedRound = captures.get(t.getName());
      if (capturedRound != null && round - capturedRound <= 2) {
        found.add(
            new Occurrence(
                round,
                data.getPlayerList().getPlayerId(before) == null ? before : before,
                "RETAKEN_FAST",
                t.getName() + " taken r" + capturedRound + ", lost r" + round));
      }
      captures.put(t.getName(), round);
    }
    return found;
  }

  /**
   * W14 watch: Norway and Finland specifically -- the objective-bearing Scandinavian periphery.
   * German-held-but-empty is the exposure; ownership flipping away from Germans is the loss event
   * (the human record's game 15: "Allies held Norway for much of the game, Axis were out-incomed").
   *
   * <p>Counted per territory since the W5b post-mortem (2026-08-14): the two are different defect
   * classes. Norway has no enemy land neighbour, so its losses belong to the coastal blind spot
   * DEFEND_AMPHIB_THREAT addresses; Finland borders Russia, so its losses are ordinary land-front
   * attrition the stock machinery already sees. The combined SCANDINAVIA_LOST count hid that
   * split, and W5b was aimed -- and measured flat -- against the aggregate. Historical
   * continuity: old SCANDINAVIA_LOST = SCANDINAVIA_LOST_NORWAY + SCANDINAVIA_LOST_FINLAND, same
   * for _UNDEFENDED.
   */
  public static List<Occurrence> scandinaviaWatch(final GameData data, final int round) {
    final List<Occurrence> found = new ArrayList<>();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    if (germans == null) {
      return found;
    }
    for (final String name : List.of("Norway", "Finland")) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t == null) {
        continue;
      }
      final String suffix = name.toUpperCase(java.util.Locale.ROOT);
      if (t.getOwner().equals(germans)) {
        final boolean defended =
            t.getUnits().stream().anyMatch(ProMatches.unitIsAlliedLandAndNotInfra(germans));
        if (!defended) {
          found.add(
              new Occurrence(
                  round,
                  germans.getName(),
                  "SCANDINAVIA_UNDEFENDED_" + suffix,
                  name + " held but empty"));
        }
      } else if (data.getRelationshipTracker().isAtWar(germans, t.getOwner())) {
        found.add(
            new Occurrence(
                round,
                germans.getName(),
                "SCANDINAVIA_LOST_" + suffix,
                name + " held by " + t.getOwner().getName()));
      }
    }
    return found;
  }

  /** Every stateless check, run together. */
  public static List<Occurrence> all(final GameData data, final int round) {
    final List<Occurrence> found = new ArrayList<>();
    found.addAll(loadedTransportsNextToFriendlyLand(data, round));
    found.addAll(loadedTransportsAtSea(data, round));
    found.addAll(ungarrisonedGuerrillaTerritories(data, round));
    found.addAll(fleetsAdriftAtSea(data, round));
    return found;
  }

  /** Where a loaded transport was at the end of a round: {@code transport id -> territory name}. */
  public static Map<String, String> loadedTransportPositions(final GameData data) {
    final Map<String, String> positions = new LinkedHashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      for (final Unit unit : t.getUnits()) {
        if (!unit.getTransporting().isEmpty()) {
          positions.put(
              unit.getId() + "|" + unit.getOwner().getName(),
              t.getName() + "|" + unit.getTransporting().size());
        }
      }
    }
    return positions;
  }

  /**
   * Transports that stayed loaded in the <em>same</em> sea zone from one round to the next.
   *
   * <p>This is the sharp form of the defect. A loaded transport in open water is not by itself
   * wrong -- an ocean crossing takes several turns and legitimately ends each of them loaded. What
   * is wrong is cargo that is not going anywhere: same ship, same water, same cargo, round after
   * round, sinkable the whole time and contributing nothing. Consecutive-round stalling separates
   * the two without needing to model intent.
   */
  public static List<Occurrence> stalledCargo(
      final Map<String, String> previousRound,
      final Map<String, String> thisRound,
      final int round) {
    final List<Occurrence> found = new ArrayList<>();
    thisRound.forEach(
        (transport, position) -> {
          if (position.equals(previousRound.get(transport))) {
            final String[] who = transport.split("\\|", 2);
            final String[] where = position.split("\\|", 2);
            found.add(
                new Occurrence(
                    round,
                    who.length > 1 ? who[1] : "?",
                    "CARGO_STALLED",
                    where[1] + " units parked in " + where[0] + " for a second round"));
          }
        });
    return found;
  }

  /** Groups occurrences as {@code defect -> player -> count} for a compact report. */
  public static Map<String, Map<String, Integer>> summarize(final List<Occurrence> occurrences) {
    final Map<String, Map<String, Integer>> byDefect = new LinkedHashMap<>();
    for (final Occurrence o : occurrences) {
      byDefect
          .computeIfAbsent(o.defect(), k -> new LinkedHashMap<>())
          .merge(o.player(), 1, Integer::sum);
    }
    return byDefect;
  }
}
