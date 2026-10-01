# Status

**Current:** v1.0.0, the first public release (prepared 2026-10-02).

## v1.0.0

- `main` holds the initial import commit: the source zip contents, byte for
  byte, plus `.gitattributes` (no line-ending conversion) and `.gitignore`.
- `feat/tether-development` adds the README (with screenshots rendered by
  the game's own `Shots` test), CHANGELOG, LICENSE (MIT for the code; the
  bundled Space Grotesk fonts are OFL 1.1, text in `docs/licenses/`),
  project docs, the Claude Code skills, agent and hooks, and CI.
- Game source checks before release: the source compiles with Kotlin 2.3.10.
  Monkey, AudioTest, Sim and Shots all pass. The game strings and fonts in
  the original APK match this source.
- Release asset: the original APK, renamed `Tether-v1.0.0.apk`
  (SHA-256 `ab377abe76ece086b7dd1920763e61f93801eed2d635b80e11f659ac7ba57b05`).

## Next

Nothing planned. Before the first release built from source, create the new
permanent signing key (see [workflow.md](workflow.md#signing-keys)).
