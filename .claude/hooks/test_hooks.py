"""Regression tests for this repo's two Claude Code hooks.

Run from the repo root:  py .claude/hooks/test_hooks.py
Exits non-zero if any case is decided the wrong way.
"""

import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GIT_HOOK = os.path.join(ROOT, ".claude", "hooks", "guard_git_scope.py")
SRC_HOOK = os.path.join(ROOT, ".claude", "hooks", "protect_game_source.py")

OTHER = "someone-else/other-repo"
ALLOWED = "pranvirsingh/Tether"

# ---- guard_git_scope.py ---------------------------------------------------

GIT_ALLOW = [
    "git add README.md docs/status.md",
    "git commit -m 'Imp : Professional README, docs and CI'",
    "git push -u origin feat/tether-development",
    "gh pr create --title test --body test --base main --head feat/tether-development",
    "gh pr merge 1 --merge",
    "gh pr checks 1 --watch",
    "git tag v1.0.0",
    "git tag -l",
    "git push origin v1.0.0",
    "gh release create v1.0.0 --title v1.0.0 --notes notes",
    "git checkout -b feat/tether-development",
    "git log --oneline -5 && gh pr list",
    "git branch -d some-old-merged-branch",
    "git config user.name",
    "git config --get user.email",
    "git clean -n -d --exclude=config",
    f"gh pr create --repo {ALLOWED} --title x --body y",
    f"gh repo create {ALLOWED} --public --source . --remote origin",
    "/usr/bin/git status",
    "git.exe log --oneline -5",
    "git -C . status",
    "git -c core.pager=cat log --oneline -5",
    "gh --hostname github.com pr list",
    "git commit -m 'Fix : handle the edge case (see the linked issue)'",
    'gh repo create pranvirsingh/Tether --public --source . --remote origin --description "One-touch neon sling climber for Android. Kotlin, no engine, procedural art and synthesised audio."',
    "git push -u origin main",
    "gh repo edit pranvirsingh/Tether --add-topic android --add-topic kotlin",
    "gh repo edit --add-topic android",
    "git merge --ff-only main",
    "git checkout main && git pull origin main",
    "gh api repos/pranvirsingh/Tether",
    "gh repo view someone-else/other-repo",
    'git grep -F "versionName"',
    "git log --oneline --follow -- README.md",
    "git log --grep=Co-Authored-By --oneline",
    "curl -fsSL -o kotlinc.zip https://github.com/JetBrains/kotlin/releases/download/v2.3.10/kotlin-compiler-2.3.10.zip",
    "cd docs && git status",
    f'cd "{ROOT}" && git push -u origin feat/tether-development',
    "gh pr merge 1 --merge",
    'gh repo edit pranvirsingh/Tether --description "Kotlin/Android sling climber"',
    "gh api graphql -f query='query { viewer { login } }'",
    "./build.sh 2>&1 &",
]

GIT_BLOCK = [
    "git push --force origin main",
    "git push -f origin main",
    "git push --force-with-lease=refs/heads/main:deadbeef origin main",
    "git reset --hard HEAD~3",
    "git clean -fd",
    "git clean --force",
    "git branch -D main",
    "git tag -d v1.0.0",
    "git tag -D v1.0.0",
    "git push origin :main",
    "git push origin :refs/heads/main",
    "git config url.https://github.com/other/.insteadOf https://github.com/pranvirsingh/",
    "git config --global core.hooksPath /tmp/evil",
    f"gh repo delete {ALLOWED}",
    f"gh pr create --repo {OTHER} --title x --body y",
    f"gh pr create --repo={OTHER} --title x --body y",
    "gh pr create --repo pranvirsingh/ClaimLens --title x --body y",
    f"git push https://github.com/{OTHER}.git main",
    f"git remote add evil https://github.com/{OTHER}.git",
    "echo $(git push --force origin main)",
    "echo `git push --force origin main`",
    "/usr/bin/git push --force origin main",
    "git.exe push --force origin main",
    "git push -uf origin main",
    "git push -fu origin main",
    'git config alias.foo "--get-all"',
    "git replace deadbeef cafef00d",
    "git update-ref refs/heads/main deadbeef",
    "git symbolic-ref HEAD refs/heads/main",
    "git remote set-head origin main",
    "git remote prune origin",
    "git -c foo=bar push --force origin main",
    "git -C . reset --hard HEAD~3",
    f"gh --hostname github.com repo delete {ALLOWED}",
    "git branch --delete --force main",
    "git branch -d -f main",
    "git push origin :refs/tags/v1.0.0",
    # AI attribution never goes into commits, PRs or releases
    "git commit -m 'Imp : x' -m 'Co-Authored-By: Claude <noreply@anthropic.com>'",
    "git commit -m 'Imp : x\n\nco-authored-by: someone'",
    "gh pr create --title x --body 'Summary\n\nGenerated with [Claude Code](https://claude.com/claude-code)'",
    "gh release create v1.0.0 --notes 'notes\nClaude-Session: https://claude.ai/code/session_x'",
    # force pushes and remote deletions in every spelling
    "git push origin +main",
    "git push origin +feat/tether-development:main",
    "git push --mirror origin",
    "git push --prune origin",
    "git push origin --delete v1.0.0",
    "git push origin -d v1.0.0",
    "git push origin --delete feat/tether-development",
    "git push origin :feat/tether-development",
    "git push origin HEAD:refs/tags/v1.0.0 +v1.0.0",
    # history rewrites and moving shipped refs
    "git tag -f v1.0.0",
    "git tag --force v1.0.0 HEAD",
    "git commit --amend -m 'Imp : x'",
    "git commit --amend --no-edit",
    "git rebase main",
    "git rebase -i HEAD~3",
    "git branch -f main HEAD",
    "git branch -M feat/tether-development main",
    # releases are never removed; merges are never squashed or rebased
    "gh release delete v1.0.0 --yes",
    "gh release delete-asset v1.0.0 Tether-v1.0.0.apk",
    "gh pr merge 1 --squash",
    "gh pr merge 1 --rebase",
    # other repos through positional args and the raw API
    f"gh repo create {OTHER} --public",
    "gh repo create SomethingElse --public",
    f"gh repo edit {OTHER} --visibility public",
    f"gh repo edit --visibility public {OTHER}",
    "gh repo rename Tether2",
    "gh repo archive pranvirsingh/Tether",
    f"gh api -X DELETE repos/{OTHER}",
    "gh api -X DELETE repos/pranvirsingh/Tether/git/refs/tags/v1.0.0",
    f"gh api repos/{OTHER}/issues -f title=x",
    f"gh api --method PATCH repos/{OTHER} -f private=false",
    "gh api -X PATCH repos/pranvirsingh/Tether -f description=x",
    "gh api -XDELETE repos/pranvirsingh/Tether",
    "gh api --method=DELETE repos/pranvirsingh/Tether",
    "gh api -X PATCH repos/pranvirsingh/Tether/git/refs/heads/main -f sha=abc -F force=true",
    "gh api repos/pranvirsingh/Tether/git/refs -f ref=refs/tags/v1.0.0 -f sha=abc",
    "gh release upload v1.0.0 x.apk --clobber",
    "gh release edit v1.0.0 --tag v0.9.0",
    "gh pr merge 5 -Rsomeone-else/other-repo --merge",
    # line continuations, subshells and shells-in-shells
    "git push origin main \\\n  --force",
    "(git push --force origin main)",
    'bash -c "git push --force origin main"',
    "sh -c 'git tag -d v1.0.0'",
    'pwsh -Command "git reset --hard HEAD~1"',
    "pwsh -EncodedCommand ZwBpAHQAIABwAHUAcwBoAA==",
    # abbreviated long options git accepts
    "git commit --amen --no-edit",
    "git push --delet origin v1.0.0",
    "git push --mirro origin",
    "git push --prun origin",
    "git push --force-with-leas origin main",
    "git push --forc origin main",
    # one-off config that changes behaviour
    "git -c remote.origin.mirror=true push origin",
    'git -c alias.p="push --force" p origin main',
    "git -c core.hooksPath=/tmp/x commit -m x",
    "git -calias.x=y status",
    "gh pr merge 1 --merge --delete-branch",
    "gh pr merge 1 --merge -d",
    'powershell -NoProfile -Command "git push --force origin main"',
    "pwsh -NoProfile -c 'git tag -d v1.0.0'",
    'bash -lc "git push --force origin main"',
    "cd ../ClaimLens && git push origin main",
    "git -C ../ClaimLens push origin main",
    "cd .. && gh pr merge 1 --merge",
    f'git push "https://github.com/{OTHER}.git" main',
    f"git push 'https://github.com/{OTHER}.git' main",
    "cd ~/source/repos/personal/ClaimLens && git push origin main",
    "cd .. & git push origin main",
    "git --git-dir=../ClaimLens/.git push origin main",
    "gh api graphql -f query='mutation { deleteRef(input: {refId: \"x\"}) { clientMutationId } }'",
]

# Message files (PR bodies, release notes, commit messages) are checked too.
_tmpdir = tempfile.mkdtemp()
CLEAN_FILE = os.path.join(_tmpdir, "clean.md")
DIRTY_FILE = os.path.join(_tmpdir, "dirty.md")
with open(CLEAN_FILE, "w", encoding="utf-8") as fh:
    fh.write("First public release.\n\n- The hooks block git push --force, history rewrites and Co-Authored-By trailers\n\n## Test plan\n- [x] CI green\n")
with open(DIRTY_FILE, "w", encoding="utf-8") as fh:
    fh.write("Summary\n\nGenerated with [Claude Code](https://claude.com/claude-code)\n")
CLEAN = CLEAN_FILE.replace("\\", "/")
DIRTY = DIRTY_FILE.replace("\\", "/")
GIT_ALLOW += [
    f"gh pr create --base main --head feat/tether-development --title 'First release' --body-file {CLEAN}",
    f"gh release create v1.0.0 {CLEAN} --title v1.0.0 --notes-file {CLEAN}",
    f'git commit -F "{CLEAN}"',
    f"gh pr create --title x -F {CLEAN}",
]
GIT_BLOCK += [
    f"gh pr create --base main --head feat/tether-development --title x --body-file {DIRTY}",
    f"gh release create v1.0.0 --title v1.0.0 --notes-file={DIRTY}",
    f"git commit -F {DIRTY}",
    f"git commit --file={DIRTY}",
    f"gh pr create --title x -F{DIRTY}",
    "gh pr create --title x --body-file C:/definitely/missing/body.md",
]

# ---- protect_game_source.py -----------------------------------------------

SRC_ALLOW_BASH = [
    "cat src/com/pranvir/tether/Game.kt",
    "grep -rn haptic src/",
    "ls res/values",
    "./build.sh",
    "./t.sh Monkey AudioTest Sim Shots",
    "rm -rf build shots",
    "rm -rf build/jvm",
    "cp src/com/pranvir/tether/Game.kt /tmp/game-copy.kt",
    "git add README.md docs/status.md",
    "git diff --stat main -- src/",
    "git checkout main",
    "git commit -m 'Imp : README describes the res and src layout'",
    "echo hello > docs/note.md",
    "py .claude/hooks/test_hooks.py",
    "sed -n 1,40p src/com/pranvir/tether/World.kt",
    "cd src && ls",
    "cd docs && echo x > notes.md",
    "./build.sh 2>&1 | tee build.log",
    "mkdir -p shots docs/licenses",
    "rm -f *.log",
    "rm -rf build/*",
    'find src -name "*.kt"',
    "curl -fsSL -o docs/licenses/OFL.txt https://example.com/x",
    f'cd "{ROOT}" && git status',
    "env | grep JAVA",
    "git commit -m 'Imp : x' && git push -u origin feat/tether-development 2>&1",
]

SRC_BLOCK_BASH = [
    "rm src/com/pranvir/tether/Game.kt",
    "rm -rf src",
    "rm -rf ./res/",
    "mv build.sh build.old",
    "sed -i 's/a/b/' src/com/pranvir/tether/Core.kt",
    "sed --in-place 's/a/b/' rules.pro",
    "echo x > AndroidManifest.xml",
    "echo x >> src/com/pranvir/tether/Fx.kt",
    "cp /tmp/x.kt src/com/pranvir/tether/World.kt",
    "touch jvmtest/New.kt",
    "git checkout HEAD~1 -- src/com/pranvir/tether/Game.kt",
    "git restore src/",
    "git rm kc.sh",
    "git apply fix.patch",
    "patch -p1 < fix.patch",
    "py -c \"open('src/com/pranvir/tether/Game.kt','w').write('')\"",
    "Set-Content -Path res/values/strings.xml -Value x",
    "Remove-Item assets/fonts/sg_med.ttf",
    "cat x | tee zipalign.py",
    "echo hi >src/com/pranvir/tether/Game.kt",
    "echo hi >>rules.pro",
    "cd src && rm com/pranvir/tether/Game.kt",
    "cd ./res/values && echo x > strings.xml",
    "cd jvmtest; sed -i s/a/b/ Monkey.kt",
    "rm \\\n  src/com/pranvir/tether/Game.kt",
    "sudo rm src/com/pranvir/tether/Game.kt",
    "env rm src/com/pranvir/tether/Game.kt",
    "env FOO=1 rm -f build.sh",
    'bash -c "rm src/com/pranvir/tether/Game.kt"',
    "sh -c 'echo x > rules.pro'",
    'find src -name "*.kt" -delete',
    "find . -path ./src -exec rm {} +",
    "curl -o src/com/pranvir/tether/Game.kt https://example.com/x",
    "wget -O build.sh https://example.com/x",
    "rm -rf sr?",
    "rm -f *.sh",
    "rm -rf ./j*test",
    "Set-Location -Path src; Remove-Item com/pranvir/tether/Game.kt",
    f'cd "{ROOT}/src" && rm com/pranvir/tether/Game.kt',
    f'rm "{ROOT}/build.sh"',
    "(rm src/com/pranvir/tether/Game.kt)",
    "unzip -o x.zip -d src",
    "mkdir src/new",
    "python -c \"p='src/com/pranvir/tether/Core.kt'; s=open(p).read(); open(p,'w').write(s.replace('a','b'))\"",
    "python3 -c \"from pathlib import Path; f=Path('src/com/pranvir/tether/Core.kt'); f.write_text('x')\"",
    "sed -Ei 's/a/b/' src/com/pranvir/tether/Core.kt",
    "perl -pi -e 's/a/b/' src/com/pranvir/tether/Core.kt",
    "sed -i.bak 's/a/b/' rules.pro",
    'cd "$CLAUDE_PROJECT_DIR" && rm src/com/pranvir/tether/Game.kt',
    "git commit -m 'Fix : don'\\''t crash' && sed -i 's/a/b/' src/com/pranvir/tether/Core.kt",
    "echo 'it'\\''s' ; rm -rf src",
    "echo don\\'t ; rm src/com/pranvir/tether/Game.kt",
    "python3 - <<'PY'\nopen('src/com/pranvir/tether/Core.kt','w').write('')\nPY",
    "echo x >| src/com/pranvir/tether/Core.kt",
]

SRC_ALLOW_EDIT = [
    "README.md",
    "CHANGELOG.md",
    "docs/status.md",
    ".github/workflows/ci.yml",
    ".claude/skills/ship/SKILL.md",
    "docs/res/notes.md",
]

SRC_BLOCK_EDIT = [
    "src/com/pranvir/tether/Game.kt",
    "res/values/strings.xml",
    "assets/fonts/sg_bold.ttf",
    "jvmtest/Monkey.kt",
    "AndroidManifest.xml",
    "build.sh",
    "kc.sh",
    "t.sh",
    "zipalign.py",
    "rules.pro",
]


def run(hook: str, payload: dict, project_dir: str = ROOT) -> bool:
    payload = {"cwd": project_dir, **payload}
    env = dict(os.environ, CLAUDE_PROJECT_DIR=project_dir)
    result = subprocess.run(
        [sys.executable, hook], input=json.dumps(payload), capture_output=True, text=True, env=env
    )
    if result.returncode != 0:
        print(result.stderr)
    return '"permissionDecision": "deny"' in result.stdout


failures = 0


def expect(label: str, blocked: bool, should_block: bool, case: str) -> None:
    global failures
    ok = blocked == should_block
    if not ok:
        failures += 1
    want = "block" if should_block else "allow"
    print(f"[{label}] {'ok' if ok else 'FAIL (should ' + want + ')'}: {case}")


for cmd in GIT_ALLOW:
    expect("GIT ALLOW", run(GIT_HOOK, {"tool_input": {"command": cmd}}), False, cmd)
for cmd in GIT_BLOCK:
    expect("GIT BLOCK", run(GIT_HOOK, {"tool_input": {"command": cmd}}), True, cmd)

for tool in ("Bash", "PowerShell"):
    for cmd in SRC_ALLOW_BASH:
        expect(f"SRC ALLOW {tool}", run(SRC_HOOK, {"tool_name": tool, "tool_input": {"command": cmd}}), False, cmd)
    for cmd in SRC_BLOCK_BASH:
        expect(f"SRC BLOCK {tool}", run(SRC_HOOK, {"tool_name": tool, "tool_input": {"command": cmd}}), True, cmd)

for tool in ("Edit", "Write"):
    for rel in SRC_ALLOW_EDIT:
        path = os.path.join(ROOT, rel)
        expect(f"SRC ALLOW {tool}", run(SRC_HOOK, {"tool_name": tool, "tool_input": {"file_path": path}}), False, rel)
    for rel in SRC_BLOCK_EDIT:
        path = os.path.join(ROOT, rel)
        expect(f"SRC BLOCK {tool}", run(SRC_HOOK, {"tool_name": tool, "tool_input": {"file_path": path}}), True, rel)

# Relative paths are resolved against the project, not trusted as-is.
expect("SRC BLOCK Edit", run(SRC_HOOK, {"tool_name": "Edit", "tool_input": {"file_path": "src/com/pranvir/tether/Game.kt"}}), True, "relative src path")
expect("SRC BLOCK Edit", run(SRC_HOOK, {"tool_name": "Edit", "tool_input": {"file_path": os.path.join(ROOT, "docs", "..", "build.sh")}}), True, "docs/../build.sh")

# Message-file paths written the PowerShell way (backslashes, $env:TEMP).
ps_clean = CLEAN_FILE if os.name == "nt" else CLEAN
expect("GIT ALLOW PowerShell", run(GIT_HOOK, {"tool_name": "PowerShell", "tool_input": {"command": f"gh pr create --title x --body-file {ps_clean}"}}), False, "unquoted backslash body-file path")
os.environ["HOOKTEST_DIR"] = _tmpdir
expect("GIT ALLOW PowerShell", run(GIT_HOOK, {"tool_name": "PowerShell", "tool_input": {"command": 'gh pr create --title x --body-file "$env:HOOKTEST_DIR/clean.md"'}}), False, "$env: body-file path")
expect("GIT BLOCK PowerShell", run(GIT_HOOK, {"tool_name": "PowerShell", "tool_input": {"command": 'gh pr create --title x --body-file "$env:HOOKTEST_DIR/dirty.md"'}}), True, "$env: dirty body-file path")
expect("GIT ALLOW PowerShell", run(GIT_HOOK, {"tool_name": "PowerShell", "tool_input": {"command": 'gh pr create --repo="pranvirsingh/Tether" --title x --body x'}}), False, '--repo="pranvirsingh/Tether"')
expect("GIT ALLOW PowerShell", run(GIT_HOOK, {"tool_name": "PowerShell", "tool_input": {"command": f"gh pr create --title x --body-file='{CLEAN}'"}}), False, "--body-file='<path>'")

# A session whose working directory is already inside the game source
# (from a `cd` in an earlier command): writes there are blocked.
src_dir = os.path.join(ROOT, "src", "com", "pranvir", "tether")
expect("SRC BLOCK cwd", run(SRC_HOOK, {"tool_name": "Bash", "cwd": src_dir, "tool_input": {"command": "rm Game.kt"}}), True, "rm Game.kt (cwd = src/...)")
expect("SRC BLOCK cwd", run(SRC_HOOK, {"tool_name": "Bash", "cwd": src_dir, "tool_input": {"command": "echo x > Core.kt"}}), True, "echo x > Core.kt (cwd = src/...)")
expect("SRC ALLOW cwd", run(SRC_HOOK, {"tool_name": "Bash", "cwd": src_dir, "tool_input": {"command": "cat Game.kt"}}), False, "cat Game.kt (cwd = src/...)")
expect("SRC ALLOW cwd", run(SRC_HOOK, {"tool_name": "Bash", "cwd": os.path.join(ROOT, "docs"), "tool_input": {"command": "rm notes.md"}}), False, "rm notes.md (cwd = docs)")

# Unlock file: with .claude/allow-source-edits present, edits are allowed.
with tempfile.TemporaryDirectory() as tmp:
    os.makedirs(os.path.join(tmp, ".claude"))
    target = os.path.join(tmp, "src", "com", "pranvir", "tether", "Game.kt")
    expect("SRC LOCKED", run(SRC_HOOK, {"tool_name": "Edit", "tool_input": {"file_path": target}}, tmp), True, "temp project, no unlock file")
    open(os.path.join(tmp, ".claude", "allow-source-edits"), "w").close()
    expect("SRC UNLOCKED", run(SRC_HOOK, {"tool_name": "Edit", "tool_input": {"file_path": target}}, tmp), False, "temp project, unlock file present")

print(f"\n{failures} failures")
sys.exit(1 if failures else 0)
