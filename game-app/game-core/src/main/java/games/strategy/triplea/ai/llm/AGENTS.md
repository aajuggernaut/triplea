# AGENTS.md — LLM bridge (`games.strategy.triplea.ai.llm`)

Fork-only package (branch `llm-bridge`), not part of upstream TripleA. It connects TripleA to an
external LLM game agent. The project's main instructions, status and eval scripts live in the
companion repo **`aajuggernaut/triplea-llm-agent`** (cloned next to this one as
`../triplea-llm-agent`): read its `AGENTS.md` and `STATUS.md` before changing anything here.

## Files

| File | Role |
|---|---|
| `LlmBridgeAi` | Player type "LLM Bridge (AI)" (`--candidate llm` in `PtvEvalRunner`). Extends stock `ProAi`; picks agent / logging / plain mode per purchase. |
| `LlmPurchaseInterceptor` | Proxy over `IPurchaseDelegate`: at ProAi's commit, asks the agent, retries with the engine's error, falls back to ProAi's buy. |
| `LlmAgentClient` | HTTP POST to `<agentUrl>/v1/purchase` (JDK `HttpClient`); never throws, returns a `Reply`. |
| `LlmAgentConfig` | System properties: `triplea.ai.llm.agentUrl`, `.seats`, `.timeoutSeconds`, `.maxAttempts`. |
| `LlmBriefingBuilder` | Map-generic JSON briefing (schema `llm-briefing/v0.2`: v0 plus a `budget` block and largest-first placement; v0.2 dropped `budget.placementTerritories`). No map, nation or unit names in code. |
| `LlmDecisionLog` | JSONL decision log; on only with `-Dtriplea.ai.llm.logDir=DIR`. |
| `LlmTerritoryHints` | Validates `-Dtriplea.ai.llm.hintsFile=PATH`: player → territory-name → positive finite weight; exact-byte SHA-256 prefix for eval provenance. |

Tests: `game-core/src/test/java/games/strategy/triplea/ai/llm/` (unit), and
`bash eval/bridge-acceptance.sh` in the companion repo (five real 2-round games against a scripted
fake agent: stub, agent down, slow, engine-rejected first answer, different buy).

## Rules

- **The game must never stall on the bridge.** Every path to the external agent has a ProAi
  fallback (timeout, error, illegal answer, too many retries), and briefing failures are caught.
- **Logging mode must not change play without hints.** With no agent or hints wired in, `LlmBridgeAi` must reproduce stock
  ProAi exactly; `bash eval/smoke-test.sh` in the companion repo checks this (PASS/FAIL).
- **Briefings must be byte-stable** for a given position: iterate in map/player-list order or sort;
  never emit `HashSet`/`HashMap` iteration order. The agent caches answers by briefing hash.
- **Do not extend `PtvProAi`** or read `PtvFeature`; PtV patches would leak into other maps.
- **ProAi plans its whole turn inside `purchase()`** (`AbstractProAi`), so the override intercepts
  the commit (the `IPurchaseDelegate` proxy), and any future strategic hints must be in place
  before that simulation runs.
- **Hints are static for a game**, independent of purchase seats. Each bridge instance latches its
  file (or one logged failure) before its first simulation. `ProData` reads the hook on real and
  simulated initialization, keys by territory name, and gates lookup on its own player. Empty maps
  skip all hint arithmetic in attack priority and capital/factory pull. Do not change
  `findTerritoryAttackValue`, W2 or `PtvFeature` for hints. Eval validates the file before any game;
  GUI bridge errors fall back to no hints.
- **When the agent's answer equals ProAi's proposal, commit ProAi's own map object.** `IntegerMap`
  keeps insertion order, which fixes unit-creation order and so unit ids; a rebuilt map with the
  same counts can still change the game.
- Run `./gradlew spotlessApply` before committing; push after every commit.
