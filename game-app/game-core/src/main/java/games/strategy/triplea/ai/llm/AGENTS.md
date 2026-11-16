# AGENTS.md — LLM bridge (`games.strategy.triplea.ai.llm`)

Fork-only package (branch `llm-bridge`), not part of upstream TripleA. It connects TripleA to an
external LLM game agent. The project's main instructions, status and eval scripts live in the
companion repo **`aajuggernaut/triplea-llm-agent`** (cloned next to this one as
`../triplea-llm-agent`): read its `AGENTS.md` and `STATUS.md` before changing anything here.

## Files

| File | Role |
|---|---|
| `LlmBridgeAi` | Player type "LLM Bridge (AI)" (`--candidate llm` in `PtvEvalRunner`). Extends stock `ProAi`. |
| `LlmBriefingBuilder` | Map-generic JSON briefing (schema `llm-briefing/v0`). No map, nation or unit names in code. |
| `LlmDecisionLog` | JSONL decision log; on only with `-Dtriplea.ai.llm.logDir=DIR`. |

## Rules

- **The game must never stall on the bridge.** Every path to the external agent has a ProAi
  fallback (timeout, error, illegal answer, too many retries), and briefing failures are caught.
- **Logging mode must not change play.** With no agent wired in, `LlmBridgeAi` must reproduce stock
  ProAi exactly; `bash eval/smoke-test.sh` in the companion repo checks this (PASS/FAIL).
- **Briefings must be byte-stable** for a given position: iterate in map/player-list order or sort;
  never emit `HashSet`/`HashMap` iteration order. The agent caches answers by briefing hash.
- **Do not extend `PtvProAi`** or read `PtvFeature`; PtV patches would leak into other maps.
- **ProAi plans its whole turn inside `purchase()`** (`AbstractProAi`), so overriding a purchase
  means intercepting the commit in `ProPurchaseAi` (wrap the `IPurchaseDelegate`), and any
  strategic hints must be in place before that simulation runs.
- Run `./gradlew spotlessApply` before committing; push after every commit.
