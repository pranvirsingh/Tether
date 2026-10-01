import json
import os
import re
import shlex
import sys

data = json.load(sys.stdin)
command = data.get("tool_input", {}).get("command", "")

# Full git/GitHub write access is intentional (the owner's standing
# "Ship <version>" instruction — see CLAUDE.md,
# .claude/skills/ship/SKILL.md and docs/workflow.md). This hook is NOT a
# write-blocker — it only stops a short list of things that are never
# part of the owner's shipping pattern (a clean feature-branch -> PR ->
# `gh pr merge --merge` -> tag -> release flow, no rebasing, no forced
# pushes, no history rewriting, no tag deletion, no remote/config
# rewrites), AI attribution in commits/PRs/releases, and anything
# pointed at a repo other than this game's own.
#
# This is a best-effort static check on a command string, not a real
# shell parser. Line continuations are joined, `bash -c` / `pwsh -Command`
# strings are checked as commands of their own, and message files
# (--body-file, --notes-file, -F) are read and checked for attribution.
# What it can't inspect fails CLOSED (deny) rather than guess:
#   - command substitution ($(...) / `...`) anywhere near a git/gh word;
#   - encoded PowerShell commands and deeply nested shells;
#   - a message file that can't be read.
# CI's checks and GitHub itself are the backstop for anything a static
# check can't see.
# Every flag check below matches `--flag value`, `--flag=value`, and (for
# push's `-f`) a squashed short-option cluster like `-uf` — git/gh accept
# all of these, and a check that only covers one form is a real bypass,
# not a theoretical one. Regression cases live in test_hooks.py.

ALLOWED_REPO = "pranvirsingh/tether"  # this game's repo, lower-cased

FORCE_PUSH_FLAG_NAMES = ("-f", "--force", "--force-with-lease", "--force-if-includes")
CONFIG_READONLY_FLAGS = {
    "--get",
    "--get-all",
    "--get-regexp",
    "--list",
    "-l",
    "--list-all",
    "--show-origin",
    "--show-scope",
}
MAIN_REF_NAMES = {"main", "refs/heads/main"}

# Global options that take their value as a SEPARATE following token
# (`-c foo=bar`, not `-c=foo=bar`) — anything else starting with "-"
# is either boolean or uses `=value` inline, so just skipping the one
# token is correct for those.
GIT_GLOBAL_VALUE_FLAGS = {"-c", "-C", "--git-dir", "--work-tree", "--namespace", "--super-prefix", "--exec-path"}
GH_GLOBAL_VALUE_FLAGS = {"--hostname"}
OWNER_REPO_TOKEN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

# Anywhere a git/gh word appears near command substitution syntax, refuse
# outright rather than trying to parse what's inside it.
SUBSTITUTION_PATTERN = re.compile(r"\$\(|`")
GIT_OR_GH_WORD = re.compile(r"\bgit\b|\bgh\b")


def base_tool_name(tok: str) -> str:
    """`git`, `/usr/bin/git`, `C:\\...\\git.exe`, `gh.exe` all resolve to
    their bare tool name — a path- or extension-qualified invocation is
    just as real an invocation as the bare word, and treating it as
    unrecognized (and therefore unchecked) is the bug, not a feature."""
    name = tok.replace("\\", "/").rsplit("/", 1)[-1]
    if name.lower().endswith(".exe"):
        name = name[:-4]
    return name.lower()


POWERSHELL = data.get("tool_name") == "PowerShell"


def tokens_of(segment: str) -> list[str]:
    try:
        toks = shlex.split(segment, posix=not POWERSHELL)
    except ValueError:
        toks = segment.split()
    if POWERSHELL:
        # non-POSIX mode keeps backslashes (Windows paths) but also the quotes
        toks = [t[1:-1] if len(t) >= 2 and t[0] == t[-1] and t[0] in "'\"" else t for t in toks]
        # --flag="value": PowerShell strips these quotes before gh/git see them
        toks = [re.sub(r"^(--?[\w-]+=)(['\"])(.*)\2$", r"\1\3", t) for t in toks]
    return toks


def has_flag(args: list[str], names: tuple[str, ...], glued: bool = False) -> tuple[bool, str | None]:
    """True if any of `names` appears in `args`, as `--flag value`,
    `--flag=value`, or bare (value None either way). Long options also
    match an abbreviation git/gh would accept (`--amen` for `--amend`,
    at least four characters). With `glued`, a short option also matches
    with its value attached (`-XDELETE`, `-Rowner/repo`)."""
    for i, tok in enumerate(args):
        head, eq, tail = tok.partition("=")
        for name in names:
            nxt = args[i + 1] if i + 1 < len(args) else None
            if name.startswith("--"):
                if head.startswith("--") and len(head) >= 4 and name.startswith(head):
                    return True, (tail if eq else nxt)
            else:
                if tok == name:
                    return True, nxt
                if tok.startswith(name + "="):
                    return True, tok[len(name) + 1 :]
                if glued and tok.startswith(name) and not tok.startswith("--") and len(tok) > len(name):
                    return True, tok[len(name) :]
    return False, None


def has_short_flag_char(args: list[str], char: str) -> bool:
    """True if `char` appears inside a squashed short-option cluster
    (e.g. `-uf`, `-fu` both carry `f`) — git's own option parser treats
    these identically to the flag given on its own."""
    for tok in args:
        if tok.startswith("-") and not tok.startswith("--") and char in tok[1:]:
            return True
    return False


def strip_global_options(rest: list[str], value_flags: set[str]) -> list[str]:
    """Skip leading global options (`git -c x=y push ...`,
    `gh --hostname x repo ...`) so the real subcommand is what ends up
    treated as the verb. Without this, any global flag would let a
    dangerous verb slip past every check below it."""
    i = 0
    while i < len(rest) and rest[i].startswith("-"):
        i += 2 if rest[i] in value_flags else 1
    return rest[i:]


def is_dangerous_delete_refspec(tok: str) -> bool:
    # `git push origin :<ref>` deletes <ref> on the remote. No remote ref is
    # ever deleted in this repo's pattern, so any such refspec counts.
    return tok.startswith(":")


# One-off `git -c key=value` settings that are purely cosmetic. Anything
# else (alias.*, remote.*, url.*, push.*, core.hooksPath, ...) can change
# what a command really does, so it's refused.
SAFE_CONFIG_PREFIXES = ("core.pager", "color.", "core.quotepath", "pager.", "advice.", "column.", "log.")


def check_one_off_config(rest: list[str]) -> str | None:
    i = 0
    while i < len(rest) and rest[i].startswith("-"):
        tok = rest[i]
        value = None
        if tok == "-c" and i + 1 < len(rest):
            value = rest[i + 1]
        elif tok.startswith("-c") and len(tok) > 2:
            value = tok[2:]
        elif tok.startswith("--config-env"):
            return "`git --config-env` — can change what the command really does"
        if value is not None:
            key = value.split("=", 1)[0].strip().lower()
            if not key.startswith(SAFE_CONFIG_PREFIXES):
                return f"a one-off `git -c {key}=...` setting — can change what the command really does"
        i += 2 if tok in GIT_GLOBAL_VALUE_FLAGS else 1
    return None


def check_git(rest: list[str]) -> str | None:
    config_reason = check_one_off_config(rest)
    if config_reason:
        return config_reason
    rest = strip_global_options(rest, GIT_GLOBAL_VALUE_FLAGS)
    if not rest:
        return None
    verb = rest[0]
    args = rest[1:]

    if verb == "push":
        if has_flag(args, FORCE_PUSH_FLAG_NAMES)[0] or has_short_flag_char(args, "f"):
            return "a force push — never part of this repo's normal shipping pattern"
        if any(a.startswith("+") for a in args):
            return "a force push (via a `+ref` refspec)"
        if has_flag(args, ("--mirror", "--prune"))[0]:
            return "a mirror/prune push — can overwrite or delete remote branches and tags"
        if has_flag(args, ("--delete", "-d"))[0] or has_short_flag_char(args, "d"):
            return "deleting a remote branch or tag — never part of this repo's shipping pattern"
        if any(is_dangerous_delete_refspec(a) for a in args):
            return "deleting a remote branch or tag (via a `:ref`-style refspec)"

    elif verb == "commit":
        if has_flag(args, ("--amend",))[0]:
            return "amending a commit — rewrites history; make a new commit instead"

    elif verb == "rebase":
        return "a rebase — rewrites history; this repo only uses merge commits"

    elif verb == "reset":
        if "--hard" in args:
            return "a hard reset — discards uncommitted work"

    elif verb == "clean":
        if has_short_flag_char(args, "f") or has_flag(args, ("--force",))[0]:
            return "a forced clean — permanently deletes untracked files"

    elif verb in ("filter-branch", "filter-repo", "replace", "update-ref", "symbolic-ref"):
        return "directly rewriting refs/history — never part of this repo's shipping pattern"

    elif verb == "branch":
        # `-D` is shorthand for `--delete --force` — both spellings must
        # be caught, not just the short one.
        force_delete = has_flag(args, ("-D",))[0] or (
            has_flag(args, ("--delete", "-d"))[0] and (has_flag(args, ("--force", "-f"))[0] or has_short_flag_char(args, "f"))
        )
        if force_delete and "main" in args:
            return "force-deleting the main branch"
        force_move = has_flag(args, ("-f", "--force", "-M", "-C"))[0] or has_short_flag_char(args, "f")
        if force_move and "main" in args:
            return "force-moving or overwriting the main branch"

    elif verb == "tag":
        if has_flag(args, ("-d", "-D", "--delete"))[0]:
            return "deleting a git tag — tags are shipped release markers, never deleted in this repo's pattern"
        if has_flag(args, ("-f", "--force"))[0] or has_short_flag_char(args, "f"):
            return "force-moving a git tag — a shipped tag never moves"

    elif verb == "remote":
        write_verbs = ("add", "remove", "rm", "rename", "set-url", "set-head", "set-branches", "prune")
        if args[:1] and args[0] in write_verbs:
            return "changing the git remote — never needed for shipping this repo, and can silently redirect where pushes go"

    elif verb == "config":
        # A read is `git config [--get|--list|...] <key>` — the flag, if
        # any, is the first argument. Checking membership anywhere in
        # `args` (rather than specifically `args[0]`) would wrongly treat
        # a write whose VALUE happens to spell a flag name (e.g. defining
        # an alias whose body is the string "--get-all") as a read.
        first_is_readonly_flag = bool(args) and args[0] in CONFIG_READONLY_FLAGS
        if not first_is_readonly_flag:
            bare = [a for a in args if not a.startswith("-")]
            if len(bare) > 1 or any(a.startswith("-") for a in args):
                return "a git config write — never part of this repo's pattern, and can silently redirect where pushes/clones go (e.g. a url.insteadOf rewrite, or a malicious alias)"

    return None


def check_gh(rest: list[str]) -> str | None:
    has_repo_flag, repo_value = has_flag(rest, ("--repo", "-R"), glued=True)
    if has_repo_flag and repo_value:
        target = repo_value.strip().strip("/").lower()
        if target and target != ALLOWED_REPO:
            return f"references a repo other than pranvirsingh/Tether ('{repo_value}')"

    rest = strip_global_options(rest, GH_GLOBAL_VALUE_FLAGS)
    group, sub = (rest + ["", ""])[:2]

    if group == "repo":
        if sub in ("delete", "rename", "archive", "unarchive"):
            return f"`gh repo {sub}` — never part of this repo's pattern"
        if sub in ("create", "edit", "fork", "sync", "set-default"):
            # The repo is the first positional (`gh repo create <name>`), but
            # also catch any `owner/repo`-shaped token given later.
            # (skip flag values such as --description "Kotlin/Android")
            targets = [t for i, t in enumerate(rest[2:], start=2) if OWNER_REPO_TOKEN.match(t) and not rest[i - 1].startswith("-")]
            if len(rest) > 2 and not rest[2].startswith("-"):
                targets.insert(0, rest[2] if "/" in rest[2] else "pranvirsingh/" + rest[2])
            for t in targets:
                if t.strip().strip("/").lower() != ALLOWED_REPO:
                    return f"`gh repo {sub}` on a repo other than pranvirsingh/Tether ('{t}')"

    elif group == "release":
        if sub in ("delete", "delete-asset"):
            return "deleting a release or its asset — shipped releases are never removed"
        if sub == "upload" and has_flag(rest, ("--clobber",))[0]:
            return "overwriting a shipped release asset (`--clobber`)"
        if sub == "edit" and has_flag(rest, ("--tag", "--target"))[0]:
            return "re-pointing a shipped release at a different tag or commit"

    elif group == "pr":
        if sub == "merge" and (has_flag(rest, ("--squash", "-s", "--rebase", "-r"))[0]):
            return "a squash/rebase merge — this repo only uses true merge commits (`gh pr merge --merge`)"
        if sub == "merge" and has_flag(rest, ("--delete-branch", "-d"))[0]:
            return "deleting the working branch on merge — feat/tether-development is long-lived"

    elif group == "api":
        # Raw API calls are for reading only. Every write this repo needs has
        # a proper gh command (repo edit, pr, release), and a raw write can
        # force-move main or a tag, or delete things outright.
        has_method, method = has_flag(rest, ("-X", "--method"), glued=True)
        method = (method or "GET").upper() if has_method else "GET"
        if method == "GET" and has_flag(rest, ("-f", "-F", "--field", "--raw-field", "--input"), glued=True)[0]:
            method = "POST"
        # GraphQL reads are POSTs too; only a mutation writes.
        is_graphql_read = len(rest) > 1 and rest[1] == "graphql" and not any("mutation" in t.lower() for t in rest)
        if method != "GET" and not is_graphql_read:
            return f"a {method} call through `gh api` — raw API writes are never part of this repo's pattern"

    return None


# Commits, PRs and releases carry only the owner's identity — never an AI
# co-author trailer or a "generated with" footer (owner's standing rule,
# see CLAUDE.md).
ATTRIBUTION_PATTERN = re.compile(
    r"co-authored-by:\s*\S|generated with \[?claude code|claude-session:|noreply@anthropic\.com",
    re.IGNORECASE,
)


GITHUB_URL_PATTERN = re.compile(r"github\.com[:/]([\w.-]+/[\w.-]+?)(?:\.git)?(?:[/\s'\"]|$)", re.IGNORECASE)


def check_github_url(line: str) -> str | None:
    for found in GITHUB_URL_PATTERN.findall(line):
        normalized = found.strip().strip("/").lower()
        if normalized and normalized != ALLOWED_REPO:
            return f"references a repo other than pranvirsingh/Tether ('{found}')"
    return None


SHELLS = {"bash", "sh", "zsh", "dash", "ksh", "pwsh", "powershell", "cmd"}
SHELL_COMMAND_FLAGS = {"-c", "/c", "-command", "-encodedcommand", "-e"}
MESSAGE_FILE_FLAGS = ("--body-file", "--notes-file", "--file", "-F")
session_cwd = data.get("cwd") or os.getcwd()


def message_file_reason(toks: list[str]) -> str | None:
    """PR bodies, release notes and commit messages passed as files get the
    same attribution check as inline text."""
    for i, tok in enumerate(toks):
        path = None
        for flag in MESSAGE_FILE_FLAGS:
            if tok == flag and i + 1 < len(toks):
                path = toks[i + 1]
            elif flag.startswith("--") and tok.startswith(flag + "="):
                path = tok.split("=", 1)[1]
            elif flag == "-F" and tok.startswith("-F") and len(tok) > 2:
                path = tok[2:]
        if not path or path == "-":
            continue
        path = os.path.expandvars(re.sub(r"\$env:(\w+)", r"${\1}", path, flags=re.IGNORECASE))
        m = re.match(r"^/([a-zA-Z])/(.*)$", path)
        if os.name == "nt" and m:
            path = f"{m.group(1)}:/{m.group(2)}"
        elif os.name == "nt" and path.startswith("/tmp/"):
            # Git Bash maps /tmp to the user's temp folder
            path = os.path.join(os.environ.get("TEMP", "/tmp"), path[5:])
        full = os.path.join(session_cwd, path)
        try:
            with open(full, encoding="utf-8", errors="replace") as fh:
                text = fh.read(1_000_000)
        except OSError:
            return f"message file '{path}' can't be read to check it for AI attribution"
        if ATTRIBUTION_PATTERN.search(text):
            return f"AI attribution inside message file '{path}' — commits, PRs and releases carry only the owner's name"
    return None


ENCODED = object()
POSIX_SHELLS = {"bash", "sh", "zsh", "dash", "ksh"}


def shell_command_string(toks: list[str]):
    """The command string given to `bash -c` / `bash -lc` / `pwsh -NoProfile
    -Command` / `cmd /c`, wherever the flag sits; None if there isn't one."""
    for i, t in enumerate(toks):
        name = base_tool_name(t)
        if name not in SHELLS:
            continue
        for j in range(i + 1, len(toks)):
            a = toks[j].lower()
            if name in ("pwsh", "powershell") and a in ("-encodedcommand", "-e", "-enc", "-ec"):
                return ENCODED
            if a in SHELL_COMMAND_FLAGS or (name in POSIX_SHELLS and re.match(r"^-[a-z]*c[a-z]*$", a)):
                return " ".join(toks[j + 1 :])
            if not a.startswith(("-", "/")):
                break
    return None


CD_VERBS = {"cd", "pushd", "set-location", "sl", "chdir", "push-location"}


def native_path(path: str) -> str:
    """Expand ~, $VAR / $env:VAR and turn Git Bash `/c/...` into `C:/...`."""
    path = os.path.expandvars(re.sub(r"\$env:(\w+)", r"${\1}", path, flags=re.IGNORECASE))
    path = os.path.expanduser(path)
    m = re.match(r"^/([a-zA-Z])/(.*)$", path)
    if os.name == "nt" and m:
        path = f"{m.group(1)}:/{m.group(2)}"
    return path


def outside_project(path: str, base: str) -> bool:
    project = os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd()
    full = os.path.normcase(os.path.abspath(os.path.join(base, native_path(path))))
    root = os.path.normcase(os.path.abspath(project))
    return full != root and not full.startswith(root + os.sep)


def check_command(command: str, depth: int = 0) -> str | None:
    if depth > 3:
        return "a deeply nested shell command — too complex to verify here"
    # A trailing backslash (bash) or backtick (PowerShell) continues the line.
    command = re.sub(r"(?:\\|`)\r?\n", " ", command)
    base = session_cwd

    if GIT_OR_GH_WORD.search(command) and ATTRIBUTION_PATTERN.search(command):
        return "AI attribution (Co-Authored-By / Generated with Claude) in a git/gh command — commits, PRs and releases carry only the owner's name"

    for raw_line in command.splitlines():
        if SUBSTITUTION_PATTERN.search(raw_line) and GIT_OR_GH_WORD.search(raw_line):
            return (
                "command substitution ($(...) or `...`) appears alongside a git/gh "
                "invocation — too complex to safely verify here, refused rather than guessed"
            )

        for segment in re.split(r"&&|\|\||;|\||(?<![>&])&(?![&>])", raw_line):
            toks = [t.lstrip("({") for t in tokens_of(segment)]
            toks = [t for t in toks if t]
            if not toks:
                continue

            # Follow `cd` so a git/gh command aimed at another folder is caught.
            if base_tool_name(toks[0]) in CD_VERBS:
                dest = next((a for a in toks[1:] if not a.startswith("-")), "")
                if dest:
                    base = os.path.join(base, native_path(dest))
                continue

            # `bash -c "..."`, `pwsh -Command "..."`: check the inner command.
            shell_inner = shell_command_string(toks)
            if shell_inner == ENCODED:
                return "an encoded PowerShell command — can't be verified here"
            if shell_inner is not None:
                inner = check_command(shell_inner, depth + 1)
                if inner:
                    return inner

            try:
                tool_index = next(i for i, t in enumerate(toks) if base_tool_name(t) in ("git", "gh"))
            except StopIteration:
                continue

            tool, rest = base_tool_name(toks[tool_index]), toks[tool_index + 1 :]
            if outside_project(".", base):
                return f"`{tool}` run from a folder outside this repo — only pranvirsingh/Tether is in scope"
            if tool == "git":
                leading = rest[: len(rest) - len(strip_global_options(rest, GIT_GLOBAL_VALUE_FLAGS))]
                for flag in ("-C", "--git-dir", "--work-tree"):
                    has_dir, git_dir = has_flag(leading, (flag,))
                    if has_dir and git_dir and outside_project(git_dir, base):
                        return f"`git {flag} {git_dir}` points outside this repo — only pranvirsingh/Tether is in scope"
            found = check_git(rest) if tool == "git" else check_gh(rest)
            if found:
                return found

            # Only commands whose -F/--file really is a message file
            # (`git grep -F` means "fixed strings", for example).
            verbs = strip_global_options(rest, GIT_GLOBAL_VALUE_FLAGS if tool == "git" else GH_GLOBAL_VALUE_FLAGS)
            takes_message_file = (tool == "git" and verbs[:1] in (["commit"], ["tag"], ["notes"], ["merge"])) or (
                tool == "gh" and verbs[:1] in (["pr"], ["release"], ["issue"])
            )
            if takes_message_file:
                found = message_file_reason(rest)
                if found:
                    return found

            url_reason = check_github_url(segment)
            if url_reason:
                return url_reason
    return None


reason = check_command(command)

if reason:
    print(
        json.dumps(
            {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": "Blocked: " + reason + ".",
                }
            }
        )
    )
    sys.exit(0)

print("{}")
