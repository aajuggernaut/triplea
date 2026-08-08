package org.triplea.ptv.eval;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.delegate.Matches;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Prints, for a few sea zones, which land neighbours each candidate "can I unload here?" predicate
 * accepts.
 *
 * <p>Written because the defect probe and the AI disagreed about whether 3 Sea Zone had anywhere to
 * unload, and guessing which was right had already cost one wrong patch. Runs against the parsed
 * map in seconds rather than playing a game.
 */
public final class PtvUnloadRuleCheck {

  private PtvUnloadRuleCheck() {}

  public static void main(final String[] args) throws IOException {
    GameTestUtils.setUp();
    final ServerGame game =
        GameTestUtils.setUpGameWithAis(
            "ww2_path_to_victory.xml",
            data -> {
              final Map<String, PlayerTypes.Type> types = new HashMap<>();
              data.getPlayerList()
                  .getPlayers()
                  .forEach(p -> types.put(p.getName(), PlayerTypes.PRO_AI));
              return types;
            });
    final GameData data = game.getData();
    final GamePlayer americans = data.getPlayerList().getPlayerId("Americans");

    for (final String zone : new String[] {"3 Sea Zone", "16 Sea Zone", "86 Sea Zone"}) {
      final Territory sea = data.getMap().getTerritoryOrNull(zone);
      if (sea == null) {
        System.out.println(zone + ": NOT FOUND");
        continue;
      }
      System.out.println("=== " + zone + " (Americans) ===");
      for (final Territory n : data.getMap().getNeighbors(sea)) {
        if (n.isWater()) {
          continue;
        }
        final boolean allied = Matches.isTerritoryAllied(americans).test(n);
        final boolean canUnload =
            ProMatches.territoryCanMoveLandUnitsAndIsAllied(americans).test(n);
        System.out.printf(
            "  %-28s owner=%-12s isTerritoryAllied=%-5s canMoveLandUnitsAndIsAllied=%s%n",
            n.getName(), n.getOwner().getName(), allied, canUnload);
      }
    }
    System.out.println("=== relationship Americans -> Russians ===");
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
    System.out.println(
        "  type="
            + data.getRelationshipTracker().getRelationshipType(americans, russians).getName());
    game.stopGame();
  }
}
