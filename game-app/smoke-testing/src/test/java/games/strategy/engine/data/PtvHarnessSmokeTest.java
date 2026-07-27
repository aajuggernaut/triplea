package games.strategy.engine.data;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

import games.strategy.engine.data.gameparser.GameParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that WW2 Path to Victory v6.4 parses in the headless test harness, and reports the
 * player and territory inventory we need in order to assign AIs per-player.
 *
 * <p>This is the foundation check for the PTV evaluation harness: {@code
 * HeadlessLaunchAction.setSkipMapResourceLoading(true)} means we only need the game XML, not the
 * map's ~40MB of artwork.
 */
@Slf4j
public class PtvHarnessSmokeTest {
  private static final String PTV_XML = "ww2_path_to_victory.xml";

  @BeforeAll
  public static void setUp() throws IOException {
    GameTestUtils.setUp();
  }

  @Test
  void ptvXmlParses() {
    Path xml = Path.of("src", "test", "resources", "map-xmls", PTV_XML);
    GameData data =
        GameParser.parse(xml, false).orElseThrow(() -> new RuntimeException("PTV parse failed"));

    String players =
        data.getPlayerList().getPlayers().stream()
            .map(GamePlayer::getName)
            .collect(Collectors.joining(", "));
    log.info("PTV players ({}): {}", data.getPlayerList().getPlayers().size(), players);
    log.info("PTV territories: {}", data.getMap().getTerritories().size());

    assertThat(data.getPlayerList().getPlayers().size(), greaterThan(0));
    assertThat(data.getMap().getTerritories().size(), greaterThan(0));
  }
}
