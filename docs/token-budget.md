# Token budget (Orbis)

## Hard limits (v2)

Copied house pattern from Commentarii / NoteToDo — keep agents cheap.

1. Steps of at most ~30 turns, each in a fresh `claude -p` session.
2. Sonnet runs with `--max-budget-usd 1.50`.
3. No Sonnet helper subagents; helpers are Haiku only (`gradle-runner`, `Explore`).
4. Read by line range; never read a file over 200 lines whole.
5. Full Gradle builds run outside the main Claude session (operator or `gradle-runner`).
6. Opus reviews a pre-generated diff of 1–3 key files only.
7. Pin every model explicitly; do not rely on opusplan in `-p` mode.
8. Aim under 8 session points per medium feature.