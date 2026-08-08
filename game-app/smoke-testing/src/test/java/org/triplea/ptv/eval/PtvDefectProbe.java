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

  /** Every check, run together. */
  public static List<Occurrence> all(final GameData data, final int round) {
    final List<Occurrence> found = new ArrayList<>();
    found.addAll(loadedTransportsNextToFriendlyLand(data, round));
    found.addAll(loadedTransportsAtSea(data, round));
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
