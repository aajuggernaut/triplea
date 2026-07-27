package org.triplea.ptv.eval;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameTestUtils;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.ai.pro.util.ProObjectiveValueUtils;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that objective-derived territory values are actually extracted from WW2 Path to
 * Victory's 879 objective attachments, and reports the highest-valued territories so the numbers
 * can be sanity-checked against the map's rules by eye.
 */
@Slf4j
public class PtvObjectiveValueTest {

  @BeforeAll
  public static void setUp() throws IOException {
    GameTestUtils.setUp();
  }

  @Test
  void objectiveValuesAreExtractedFromPtv() {
    final Path xml = Path.of("src", "test", "resources", "map-xmls", "ww2_path_to_victory.xml");
    final GameData data =
        GameParser.parse(xml, false).orElseThrow(() -> new RuntimeException("PTV parse failed"));

    final Map<Territory, Double> values = ProObjectiveValueUtils.findObjectiveValues(data);

    log.info(
        "Territories carrying objective value: {} of {}",
        values.size(),
        data.getMap().getTerritories().size());
    log.info("Top 20 by objective value (objective PUs vs the territory's own production):");
    values.entrySet().stream()
        .sorted(Map.Entry.<Territory, Double>comparingByValue().reversed())
        .limit(20)
        .forEach(
            e ->
                log.info(
                    String.format(
                        "  %-28s objective=%5.2f  production=%d",
                        e.getKey().getName(),
                        e.getValue(),
                        TerritoryAttachment.getProduction(e.getKey()))));

    // The whole point of the patch: a meaningful share of the map carries value the stock AI
    // cannot see at all.
    assertThat(values.size(), greaterThan(20));
    assertThat(
        values.values().stream().max(Comparator.naturalOrder()).orElseThrow(), greaterThan(0.0));
  }
}
