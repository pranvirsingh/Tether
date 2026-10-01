# Tether — personal game project

A one-touch neon sling climber for Android, written in Kotlin with no engine
(see [README.md](README.md)). One of the owner's six phone-built games; each
game has its own repo and follows the same workflow. Owner: Pranvir Singh
(`pranvirsingh` on GitHub). Personal project, not company work.

## Rules (read before doing anything)

- **The game source is frozen.** `src/`, `res/`, `assets/`, `jvmtest/`,
  `AndroidManifest.xml`, `build.sh`, `kc.sh`, `t.sh`, `zipalign.py` and
  `rules.pro` are exactly what built the working, shipped APK. Never edit,
  reformat, "clean up" or "fix" them unless the owner explicitly asks for a
  code change in the current session. `.claude/hooks/protect_game_source.py`
  enforces this. To unlock it for an approved change, create
  `.claude/allow-source-edits` (gitignored), delete it when done, and label
  the PR `game-code-change` so CI accepts it.
- **Commits, PRs and releases carry only the owner's name.** Never add
  `Co-Authored-By`, `Claude-Session` or "Generated with Claude Code" lines.
  The git guard hook blocks them, both inline and in `--body-file` /
  `--notes-file` / `-F` message files.
- **Everything stays in this repo.** Skills, agents, hooks and docs live here
  so a fresh session on any machine can pick the project up cold. When a
  preference or correction comes up, write it into the relevant doc.
- **Keep this file a short index.** Put longer content in `docs/`.
- **Talk plainly.** Simple everyday language, no unnecessary jargon. End
  every piece of work with a two-to-three-line plain TLDR. No sugarcoating.
- **Finish fully.** Docs, CHANGELOG and status are part of done. Don't expand
  scope beyond what was asked.

## Docs

- [docs/workflow.md](docs/workflow.md): the route every version follows
  (branch → review → re-review → ship), git conventions, CI, signing keys
- [docs/status.md](docs/status.md): where the project is right now and
  what's next. Update it as work lands.
- [CHANGELOG.md](CHANGELOG.md): user-facing release history

## Skills, agents, hooks

- `ship` skill: runs when the owner says **"Ship `<version>`"**. It handles
  review → re-review → commit → PR → CI → merge → tag → release.
- `phase-wrapup` skill: the text-only version (commit message, PR text,
  changelog entry, release notes). Trigger phrase: "finishing touch".
- `reviewer` agent: the review and re-review passes used by `ship`.
- Hooks (`.claude/settings.json`): `guard_git_scope.py` (no force-push or
  history rewrites, only `pranvirsingh/Tether`, no AI attribution) and
  `protect_game_source.py`. Regression tests:
  `py .claude/hooks/test_hooks.py`.
