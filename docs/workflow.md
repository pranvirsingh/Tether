# Workflow: how every version gets made and shipped

The same route is used in all six game repos. Nothing here is improvised per
version. If a step can't be done safely, stop and say why.

## Branches

- `main` holds released code only. Nothing is committed to it directly
  (the one exception is the repo's initial import commit).
- `feat/tether-development` is the long-lived working branch. Every change
  reaches `main` through a pull request from it.
- PRs are merged as a **true merge commit** (`gh pr merge --merge`). Never
  squash, never rebase, never force-push.

## Versions

- Tags are `vMAJOR.MINOR.PATCH`: MAJOR for a big overhaul, MINOR for new
  features, PATCH for fixes.
- An APK built for a release gets a matching version from `build.sh`'s
  `VN` (version name) and `VC` (version code, +1 every release), e.g.
  `VC=2 VN=1.1.0 ./build.sh`. The scripts themselves don't change for this.
  That build is signed with a throwaway key, so it is then re-signed with
  the permanent key (see "Signing keys").
- Every user-visible change gets a `CHANGELOG.md` entry in plain language.

## The route for every version

1. **Work** on `feat/tether-development`. Game source changes happen only
   when the owner asks (see "Game source is frozen" in `CLAUDE.md`).
2. **Verify.** Hooks changed → `py .claude/hooks/test_hooks.py`. Game code
   changed → build and run the tests (`./build.sh`,
   `./t.sh Shots Monkey AudioTest Sim`), or let CI do it on the PR.
3. **Review → fix → re-review** with the `reviewer` agent. Two separate
   passes, never skipped.
4. **Ship** when the owner says "Ship `<version>`" (`ship` skill): commit,
   push, PR, wait for CI to go green, merge commit, tag, GitHub release with
   the APK attached, update `docs/status.md`.

## Conventions

- **Commit message:** one line, `Imp : <what>` for additions and
  improvements, `Fix : <what>` for bug fixes. Initial import:
  `Initial commit: Tether v1.0 source`.
- **PR:** short plain-language title. The description is a one-line summary,
  3–5 short bullets and a short `## Test plan` checklist.
- **Release:** title `vX.Y.Z`. Notes are a `## vX.Y.Z — <name>` heading, one
  or two sentences and one-line bullets. Asset: `Tether-vX.Y.Z.apk`.
- **Author:** only the owner's own git identity (`pranvirsingh`). No AI
  attribution anywhere.
- Describe the shipped result, not the struggle to get there. Never credit
  outside inspiration sources.

## CI (`.github/workflows/ci.yml`)

Runs on every PR to `main` and every push to `main`:

- **Game source unchanged:** fails if a PR touches the game source without
  the `game-code-change` label.
- **Build APK & run tests:** recreates the toolchain layout the scripts
  expect (`/home/claude/tc`), runs `build.sh` and `t.sh` unchanged, checks
  the APK and the test output, then confirms no tracked file was modified.
  It also runs the hook tests. The CI APK uses a throwaway key and is never
  released.

## Signing keys

- The key that signed the original APK lived in the phone build environment
  and was never saved. It is gone.
- **v1.0.0 ships the original APK** (byte-identical to the one that was
  installed and played).
- The first release built from source needs a **new permanent key**. Create
  it once, keep it outside the repo (two backups plus the password), and
  never commit it. Android won't install a differently signed APK over the
  old one, so that release's notes must tell players to uninstall v1.0.0
  first (on-device progress is lost once). After that, the new key signs
  every release forever.
- `build.sh` always signs with `./tether.jks` and the password written in
  the script (public, so it must never protect the real key). A release APK
  is therefore made in two steps, without editing the script:

  ```
  VC=<code> VN=<version> ./build.sh          # leaves build/apk/unsigned.apk
  apksigner sign --ks <path-outside-repo>/tether-release.jks \
    --ks-key-alias tether --ks-pass env:TETHER_KS_PASS \
    --out Tether-<version>.apk build/apk/unsigned.apk
  apksigner verify --print-certs Tether-<version>.apk
  ```

  The certificate printed by `verify` must match the release key's
  fingerprint, recorded in `docs/status.md` once the key exists.
