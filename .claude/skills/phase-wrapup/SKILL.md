---
name: phase-wrapup
description: Use when a chunk of work on Tether (feature, fix, docs) is finished and the owner wants the hand-off text, or says "finishing touch" / "wrap this up". Produces a one-line commit message, a PR title + short description, a CHANGELOG.md entry, release notes for a version bump, short dev notes, and a TLDR. Text only — the `ship` skill executes; this one just writes.
---

# Phase wrap-up: the hand-off text

Text only. Never runs git/gh. When the owner says "Ship `<version>`",
the `ship` skill takes over and uses these same rules.

Always produce the full set, in this order and format. For a small tweak
to work that's still uncommitted, refresh only the affected bullets
instead of regenerating everything.

## 1. Commit message

One line, in a fenced block. Prefix `Imp : ` (addition/improvement) or
`Fix : ` (bug fix), with a space before and after the colon. No body unless
the commit genuinely bundles unrelated work. No trailers of any kind (no
`Co-Authored-By`, no session links).

## 2. PR title and description

Two separate fenced blocks.

- **Title:** a few plain words naming what the PR does.
- **Description:** short, everyday language. One-line summary, 3–5 short
  bullets, then a short `## Test plan` checklist of what was actually
  verified. Describe the final result only, never the struggle (bugs caught
  before shipping, false starts). A fix for something already released is
  reported as "Fixed: …". No "Generated with Claude Code" footer.

## 3. CHANGELOG.md entry

Add at the top of `CHANGELOG.md` (newest first). Use today's real date;
check it, don't copy the previous entry's. Same plain voice as the PR.
Only things a player would notice. Not every PR is a version: unreleased
changes go under an `## Unreleased` heading until shipped.

```
## vX.Y.Z — YYYY-MM-DD

One-line summary.

### New
- …

### Fixed
- …
```

## 4. Release notes (version bumps only)

```
## vX.Y.Z — Short name

One or two sentences on what this version is about.

- One line per major thing, no sub-bullets
```

When asked to shorten, compress (merge bullets, drop adjectives). Don't
drop real content. A release built with a new signing key must also say:
uninstall the previous version first (on-device progress resets once).

## 5. Dev notes ("How this works")

For each notable piece: **what** it does (one sentence), **how** (the real
mechanism and file), and **why this way** (only if there's a real reason).
Short block per piece, plain words.

## 6. TLDR

Two or three plain sentences: what was done.

## Never

- Credit external inspiration sources (sites, libraries, posts) anywhere.
- Narrate specific bug stories in code, tests or docs. Describe the rule
  the code enforces.
- Add AI attribution.
