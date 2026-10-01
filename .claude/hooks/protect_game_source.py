import fnmatch
import json
import os
import re
import shlex
import sys

# The game source in this repo is exactly what built the shipped, working
# APK. It is frozen: Claude never edits it unless the owner explicitly
# asks for a code change in the current session (see CLAUDE.md, "Game
# source is frozen"). This hook enforces that for file-editing tools and,
# best-effort, for shell commands. CI's "Game source unchanged" job is the
# backstop for anything a static check can't see.
#
# Unlock: the owner approves a code change -> create the (gitignored)
# file `.claude/allow-source-edits`; delete it again when that change is
# done. While it exists, this hook allows everything.
#
# Shell commands: each segment is tokenized, wrappers (sudo, env, bash -c,
# ...) are unwrapped, and every path a writing command targets is resolved
# against the folder the command runs in (the session's cwd, updated by
# any `cd` earlier in the same command). A write whose target lands in the
# game source is denied. Reading and running the scripts is always allowed.

PROTECTED_DIRS = ("src", "res", "assets", "jvmtest")
PROTECTED_FILES = ("AndroidManifest.xml", "build.sh", "kc.sh", "t.sh", "zipalign.py", "rules.pro")
PROTECTED_TOP = PROTECTED_DIRS + PROTECTED_FILES

data = json.load(sys.stdin)
tool = data.get("tool_name", "")
tool_input = data.get("tool_input", {}) or {}
project_dir = os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd()
session_cwd = data.get("cwd") or project_dir
powershell = tool == "PowerShell"


def unlocked() -> bool:
    return os.path.exists(os.path.join(project_dir, ".claude", "allow-source-edits"))


def to_native(path: str) -> str:
    # Git Bash style /c/Users/... -> C:/Users/... so it resolves on Windows.
    path = os.path.expanduser(path)
    m = re.match(r"^/([a-zA-Z])/(.*)$", path)
    if os.name == "nt" and m:
        return f"{m.group(1)}:/{m.group(2)}"
    return path


def rel_to_project(path: str, base: str) -> str | None:
    """Path relative to the project root with forward slashes, or None if
    it lies outside the project."""
    if not path:
        return None
    full = os.path.normcase(os.path.abspath(os.path.join(to_native(base), to_native(path))))
    root = os.path.normcase(os.path.abspath(project_dir))
    if full == root:
        return ""
    if not full.startswith(root + os.sep):
        return None
    return os.path.relpath(full, root).replace("\\", "/")


def is_protected_rel(rel: str | None) -> bool:
    if not rel:
        return False
    first = rel.split("/", 1)[0].lower()
    if first in {d.lower() for d in PROTECTED_DIRS}:
        return True
    return rel.lower() in {f.lower() for f in PROTECTED_FILES}


def deny(reason: str) -> None:
    print(
        json.dumps(
            {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": "Blocked: "
                    + reason
                    + ". The game source is frozen — it is exactly what built the working APK. "
                    "Only the owner can approve a code change (see CLAUDE.md).",
                }
            }
        )
    )
    sys.exit(0)


# ---- shell commands -------------------------------------------------------

WRAPPERS = {"sudo", "env", "command", "builtin", "exec", "nohup", "time", "nice", "xargs", "doas", "stdbuf", "timeout"}
SHELLS = {"bash", "sh", "zsh", "dash", "ksh", "pwsh", "powershell", "cmd"}
CD_VERBS = {"cd", "pushd", "set-location", "sl", "chdir", "push-location"}
WRITE_VERBS = {
    "rm", "rmdir", "mv", "tee", "truncate", "dd", "touch", "chmod", "chown", "ln", "unlink",
    "install", "shred", "del", "erase", "move", "ren", "rename", "mkdir", "unzip", "tar", "rsync",
    "set-content", "add-content", "out-file", "remove-item", "move-item", "rename-item",
    "new-item", "clear-content", "set-item", "ri", "rd", "mi", "rni", "ni", "sc", "ac",
    "curl", "wget", "invoke-webrequest", "iwr", "export-csv",
}
COPY_VERBS = {"cp", "copy", "copy-item", "cpi", "xcopy", "robocopy"}
INPLACE_EDITORS = {"sed", "perl", "ruby"}
SCRIPT_RUNNERS = {"py", "python", "python3", "node", "perl", "ruby", "deno", "bun"}
SCRIPT_WRITE_HINT = re.compile(r"write|open\([^)]*['\"][wa+x]|unlink|remove|rename|replace\(|shutil|os\.|fs\.", re.IGNORECASE)
GLOB_CHARS = re.compile(r"[*?\[]")
NAME_IN_TEXT = re.compile(
    r"(?<![\w.-])(?:" + "|".join(re.escape(n) for n in PROTECTED_TOP) + r")(?![\w-])", re.IGNORECASE
)


def is_inplace_flag(arg: str) -> bool:
    # -i, -i.bak, --in-place, and clusters like -Ei / -pi / -ri
    if arg.startswith("--"):
        return arg.startswith("--in-place")
    return arg.startswith("-") and "i" in arg[1:].split(".", 1)[0]


def tokens_of(segment: str) -> list[str]:
    try:
        toks = shlex.split(segment, posix=not powershell)
    except ValueError:
        toks = segment.split()
    if powershell:
        toks = [t[1:-1] if len(t) >= 2 and t[0] == t[-1] and t[0] in "'\"" else t for t in toks]
    return toks


def base_name(tok: str) -> str:
    name = tok.replace("\\", "/").rsplit("/", 1)[-1].lower()
    return name[:-4] if name.endswith(".exe") else name


def split_segments(command: str) -> list[str]:
    command = command.replace("\\\r\n", " ").replace("\\\n", " ")
    if powershell:
        command = command.replace("`\r\n", " ").replace("`\n", " ")
    parts = []
    for line in command.splitlines():
        parts.extend(s.strip() for s in split_outside_quotes(line))
    return [p for p in parts if p]


def split_outside_quotes(line: str) -> list[str]:
    """Split on ; && || | & — but not inside quotes, so an inline script
    like `python -c "a; b"` stays one segment."""
    out, buf, quote, i = [], [], None, 0
    while i < len(line):
        ch = line[i]
        if not quote and ch == "\\" and not powershell and i + 1 < len(line):
            buf.append(line[i : i + 2])  # escaped char, e.g. don\'t
            i += 2
            continue
        if quote:
            buf.append(ch)
            if ch == quote:
                quote = None
            elif ch == "\\" and quote == '"' and not powershell and i + 1 < len(line):
                buf.append(line[i + 1])
                i += 1
        elif ch in "'\"":
            quote = ch
            buf.append(ch)
        elif ch in ";|&":
            if ch == "|" and buf and buf[-1] == ">":
                buf.append(ch)  # `>|` forced-overwrite redirect
            elif ch == "&" and i + 1 < len(line) and line[i + 1] == ">":
                buf.append(ch)  # `&>` redirect, not a separator
            elif ch == "&" and buf and buf[-1] in "<>":
                buf.append(ch)  # `2>&1`
            else:
                out.append("".join(buf))
                buf = []
                if i + 1 < len(line) and line[i + 1] == ch and ch in "|&":
                    i += 1
        else:
            buf.append(ch)
        i += 1
    if quote:
        # Unbalanced quote: fall back to a plain split so no command after
        # it gets hidden inside one long "quoted" segment.
        return re.split(r"&&|\|\||;|\||&(?!>)", line)
    out.append("".join(buf))
    return out


def target_protected(tok: str, cwd: str) -> bool:
    tok = tok.strip()
    if not tok or tok.startswith("-"):
        return False
    if "=" in tok and not tok.startswith(("/", ".", "\\")) and re.match(r"^[\w-]+=", tok):
        tok = tok.split("=", 1)[1]  # --file=x, of=x
    if GLOB_CHARS.search(tok):
        # A glob written inside (or at the top of) the project could expand
        # onto a protected name — match it against those names.
        head, _, pattern = tok.replace("\\", "/").rpartition("/")
        base_rel = rel_to_project(head or ".", cwd)
        if base_rel is None:
            return False
        if is_protected_rel(base_rel):
            return True
        if base_rel == "":
            return any(fnmatch.fnmatch(n.lower(), pattern.lower()) for n in PROTECTED_TOP)
        return False
    return is_protected_rel(rel_to_project(tok, cwd))


def redirect_targets(segment: str, toks: list[str]) -> list[str]:
    out = []
    for i, t in enumerate(toks):
        m = re.match(r"^(?:\d|&)?>[>|]?(.*)$", t)
        if m:
            if m.group(1) and not m.group(1).startswith("&"):
                out.append(m.group(1))
            elif not m.group(1) and i + 1 < len(toks):
                out.append(toks[i + 1])
    # `echo x>file` with no space at all
    for m in re.finditer(r"[^\s>&\d]\d?>>?([^\s>&|;]+)", segment):
        out.append(m.group(1).strip("'\""))
    return out


def check_segment(segment: str, cwd: str, depth: int = 0) -> tuple[str | None, str]:
    """Returns (denial reason or None, cwd after this segment)."""
    toks = tokens_of(segment)
    while toks and toks[0] in ("(", "{", "!"):
        toks = toks[1:]
    if toks:
        toks[0] = toks[0].lstrip("({")
    if not toks:
        return None, cwd

    for t in redirect_targets(segment, toks):
        if target_protected(t, cwd):
            return "a shell redirect that writes into the game source", cwd

    # unwrap sudo / env VAR=x / xargs / timeout 5 ...
    while toks and (base_name(toks[0]) in WRAPPERS or re.match(r"^[A-Za-z_]\w*=", toks[0])):
        toks = toks[1:]
        while toks and (toks[0].startswith("-") or re.match(r"^[A-Za-z_]\w*=", toks[0]) or toks[0].isdigit()):
            toks = toks[1:]
    if not toks:
        return None, cwd

    verb = base_name(toks[0])
    args = [a for a in toks[1:] if not re.match(r"^(?:\d|&)?>[>|]?", a)]

    if verb in SHELLS:
        for i, a in enumerate(args):
            if a.lower() in ("-c", "/c", "-command", "-encodedcommand", "-e"):
                if a.lower() in ("-encodedcommand", "-e") and verb in ("pwsh", "powershell"):
                    return "an encoded PowerShell command (can't be checked)", cwd
                inner = " ".join(args[i + 1 :])
                return check_command(inner, cwd, depth + 1), cwd
        return None, cwd

    if verb in CD_VERBS:
        dest = next((a for a in args if not a.startswith("-")), "")
        if dest:
            dest = os.path.expandvars(re.sub(r"\$env:(\w+)", r"${\1}", dest, flags=re.IGNORECASE))
            return None, os.path.join(to_native(cwd), to_native(dest))
        return None, cwd

    if verb in COPY_VERBS:
        dests = [a for a in args if not a.startswith("-")]
        if verb == "robocopy" and len(dests) >= 2:
            dests = [dests[1]]
        if dests and target_protected(dests[-1], cwd):
            return "copying a file over the game source", cwd
        return None, cwd

    if verb == "find":
        if any(a in ("-delete", "-exec", "-execdir", "-ok", "-fprint", "-fprintf") for a in args):
            if any(target_protected(a, cwd) for a in args) or is_protected_rel(rel_to_project(".", cwd)):
                return "`find` with -delete/-exec on the game source", cwd
        return None, cwd

    if verb == "git" and args:
        sub = args[0]
        if sub in ("checkout", "restore", "rm", "mv", "stash", "clean") and any(target_protected(a, cwd) for a in args[1:]):
            return f"`git {sub}` on a game source path (would change the files on disk)", cwd
        if sub in ("apply", "am"):
            return "applying a patch (it could rewrite game source files)", cwd
        return None, cwd

    if verb == "patch":
        return "applying a patch (it could rewrite game source files)", cwd

    writes = (
        verb in WRITE_VERBS
        or (verb in INPLACE_EDITORS and any(is_inplace_flag(a) for a in args))
    )
    if writes:
        here_protected = is_protected_rel(rel_to_project(".", cwd))
        if here_protected or any(target_protected(a, cwd) for a in args):
            return f"`{toks[0]}` on a game source path", cwd
        return None, cwd

    if verb in SCRIPT_RUNNERS and SCRIPT_WRITE_HINT.search(segment):
        if NAME_IN_TEXT.search(segment) or is_protected_rel(rel_to_project(".", cwd)):
            return "an inline script that looks like it writes to the game source", cwd

    return None, cwd


def check_command(command: str, cwd: str, depth: int = 0) -> str | None:
    if depth > 3:
        return "a deeply nested shell command (can't be checked)"
    # A heredoc fed to a script runner (`python - <<'EOF' ... EOF`): the
    # script body spans several lines, so judge the whole text at once.
    if "<<" in command and re.search(r"(?:^|[\s;&|(])(?:" + "|".join(SCRIPT_RUNNERS) + r")(?:\.exe)?\s", command):
        if SCRIPT_WRITE_HINT.search(command) and (NAME_IN_TEXT.search(command) or is_protected_rel(rel_to_project(".", cwd))):
            return "an inline script (heredoc) that looks like it writes to the game source"
    for segment in split_segments(command):
        found, cwd = check_segment(segment, cwd, depth)
        if found:
            return found
    return None


if unlocked():
    print("{}")
    sys.exit(0)

if tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
    path = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
    rel = rel_to_project(path, project_dir)
    if is_protected_rel(rel):
        deny(f"editing `{rel}`")
elif tool in ("Bash", "PowerShell"):
    found = check_command(tool_input.get("command", ""), session_cwd)
    if found:
        deny(found)

print("{}")
