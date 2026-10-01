---
name: ship
description: Use when the owner says "Ship <version>" (e.g. "Ship v1.0.0") — take finished work on feat/tether-development all the way to a published GitHub release with zero per-step confirmation. Runs a mandatory review → fix → re-review gate, then commit, push, PR, green CI, merge commit, tag, and release with the APK attached. Standing grant of full git/GitHub write authority for pranvirsingh/Tether only.
---

# Ship: review → commit → PR → CI → merge → tag → release

Triggered by the owner saying **"Ship `<version>`"** (loose wording counts:
"ship v1.1.0", "ship this as 1.1.0"). That phrase is the confirmation for
every step below. Do the whole pipeline without stopping to ask, then report
real results (SHAs, links). The steps are the baseline pattern, not a
mechanical script: use judgment, but never skip a step that matters and
never invent a different flow. If something can't be done safely, stop and
say why in plain words.

Full conventions: `docs/workflow.md`. Text rules: the `phase-wrapup` skill.

## 0. Preconditions

- On `feat/tether-development`, not `main`. If on `main`, stop and ask.
- `git status`: stage **specific files** only, never `git add -A` / `.`.
  Flag anything unexpected instead of silently including or dropping it.
- **Game source frozen check:**
  `git diff --stat main -- src res assets jvmtest AndroidManifest.xml build.sh kc.sh t.sh zipalign.py rules.pro`
  (plus the same for staged/unstaged changes). It must be empty unless the
  owner approved a code change in this session. If it isn't, stop.
- Hooks changed → `py .claude/hooks/test_hooks.py` must pass.
- No AI attribution anywhere (commit, PR, release, docs).

## 1. Review → fix → re-review (never skipped)

1. Run the `reviewer` agent on the diff against `main`.
2. Fix every **CONFIRMED** finding. Anything ambiguous or that would touch
   game source → stop and ask the owner.
3. Re-run whatever verification the fixes affect.
4. Run a **fresh** `reviewer` agent on the updated diff: a real second
   pass. Confirm the old findings are gone and nothing new appeared.
5. Continue only when the re-review is clean, or every remaining finding
   has been discussed with the owner and accepted.

## 2. Content

Follow `phase-wrapup` rules for the commit message (`Imp :` / `Fix :`),
PR title + description, CHANGELOG entry (today's real date) and release
notes. The CHANGELOG entry ships in the same commit.

## 3. Repo exists? (first ship only)

If `gh repo view pranvirsingh/Tether` fails, create it and push `main`
first. `main` already holds the initial import commit:

```
gh repo create pranvirsingh/Tether --public --source . --remote origin --description "<about text>"
git push -u origin main
gh repo edit pranvirsingh/Tether --add-topic android,game,kotlin,...
```

Ask the owner before creating if the visibility or name isn't settled.

## 4. Commit, push, PR

```
git add <specific files>
git commit -m "<Imp|Fix> : <one line>"
git push -u origin feat/tether-development
gh pr create --base main --head feat/tether-development --title "<title>" --body-file "<scratch>/pr-body.md"
```

PR bodies and release notes always go through a temp file
(`--body-file` / `--notes-file`), never inline. `<scratch>` is a temp
folder outside the repo: the session's scratchpad if it has one, otherwise
the system temp folder written out as a real absolute path (not `$TMP`,
which is empty in PowerShell). The git guard reads these files and refuses
them if they contain AI attribution. When text needs to *describe* the
attribution rule, write it without the trailer colon ("Co-Authored-By
trailers", not "Co-Authored-By:"), or the guard reads it as the real thing.

## 5. CI must actually go green

```
gh pr checks <PR> --watch
```

If a check fails: don't merge and don't just re-run. Read the log, fix the
real cause, push to the same branch, and wait again.

## 6. Merge (true merge commit only)

```
gh pr merge <PR> --merge
```

## 7. Tag

```
git checkout main
git pull origin main
git tag <version>
git push origin <version>
git checkout feat/tether-development
git merge --ff-only main
```

The tag must sit on the PR's merge commit. The last two lines keep the
working branch level with `main`.

## 8. Release with the APK

- **v1.0.0:** the original APK at `../_originals/Tether/Tether.apk`. Verify
  its SHA-256 equals the one in `docs/status.md` before uploading.
- **Later versions:** build with `VC`/`VN` set for this version, then
  re-sign `build/apk/unsigned.apk` with the owner's permanent release key,
  exactly as in `docs/workflow.md` ("Signing keys"), and check the
  certificate fingerprint. If no properly signed APK exists, stop. Never
  release the CI build or a build signed with a throwaway key.

Write the release notes to a temporary file (the git guard parses inline
multi-line text as commands, so text that *describes* blocked commands
would be refused):

```
cp <apk> "<scratch>/Tether-<version>.apk"
gh release create <version> "<scratch>/Tether-<version>.apk" --title "<version>" --notes-file "<scratch>/release-notes.md"
```

## 9. Wrap up

Update `docs/status.md` (on `feat/tether-development`; it rides along with
the next PR), then report: what review found and fixed, commit SHA, PR URL,
release URL, and that the tag is on the merge commit. End with a two-to-
three-line plain TLDR. Never claim a step happened without seeing it
succeed.

## Never, even when triggered

Force-push, hard reset, forced clean, history rewriting, deleting `main` or
a tag, touching any repo other than `pranvirsingh/Tether`, or adding AI
attribution. `.claude/hooks/guard_git_scope.py` enforces these.
