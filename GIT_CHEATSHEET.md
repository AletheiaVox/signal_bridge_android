# Git cheatsheet for Signal Bridge

The minimum you need to keep the GitHub repo and your local hard drive in sync.

## The golden rule

**Pull before you push. Pull before you start editing locally.**

Whenever you edit a file on github.com directly (README, etc.), that change exists *only* on GitHub until you pull it down. If you then make local edits without pulling first, the two histories diverge and git refuses to push until you reconcile them.

## Scenario 1: I edited the README on github.com. How do I get it onto my hard drive?

You're not editing anything locally — just syncing down what's on GitHub.

```
cd C:\Users\tinac\Documents\SignalBridge\signal-bridge-android
git pull --no-edit
```

That's it. `--no-edit` keeps the editor (vim) from popping up if a merge commit is needed.

## Scenario 2: I want to edit the README on github.com AND make local changes

Do these in order — don't skip the pull:

```
cd C:\Users\tinac\Documents\SignalBridge\signal-bridge-android
git pull --no-edit                    # grab the browser edit first
# ... now make your local edits ...
git add -A                            # stage everything you changed
git commit -m "describe what you did" # commit locally
git push origin main                  # push to GitHub
```

## Scenario 3: I made local changes AND edited on github.com, and now I can't push

This is what we just untangled. You'll see:

```
! [rejected] main -> main (non-fast-forward)
```

It means your local branch and the remote have both moved forward independently. Fix:

```
git pull --no-edit origin main        # merge the remote changes into your local
git push origin main                  # now the push works
```

If the pull succeeds and shows `Merge made by the 'ort' strategy`, you're done. If it says `CONFLICT` anywhere, ask Claude — that means the same lines were edited in both places and need hand-resolution.

## Help, vim opened and I'm trapped

If a git command opens vim (you'll see a screen full of `~` characters down the left side):

- Press **Esc** (makes sure you're in command mode)
- Type **:wq** and press **Enter** (save and quit)

If you want to bail without saving instead:
- Press **Esc**
- Type **:cq** and press **Enter** (quit with error code, aborts whatever git was doing)

**Don't close the vim window from the taskbar X.** That leaves git in a half-finished state that's annoying to clean up.

To prevent vim opening in the first place, use `--no-edit` on `git pull` and `--no-edit` on `git commit` when you're merging.

## Help, I think I broke something

```
git reflog
```

Shows every position your HEAD has been at, with timestamps. Find the SHA from before things went sideways, then:

```
git reset --hard <that-sha>
```

The reflog keeps 90 days of history, so you can almost always get back to a working state.

There's also a permanent safety tag at `backup-pre-cleanup-2026-06-02` pointing at the state before our June 2026 cleanup. Restore with:

```
git reset --hard backup-pre-cleanup-2026-06-02
```

(Warning: that wipes everything done since the cleanup. Only use if all else fails.)

## Quick error glossary

| Message | What it means | Fix |
|---|---|---|
| `! [rejected] (non-fast-forward)` | Remote has commits you don't have locally | `git pull --no-edit origin main` then push again |
| `You have not concluded your merge (MERGE_HEAD exists)` | A merge is half-finished | `git commit --no-edit` to complete it, or `git merge --abort` to bail |
| `error: failed to push some refs` | Same as non-fast-forward, usually | Same fix |
| `fatal: Exiting because of unfinished merge` | Same as MERGE_HEAD message | Same fix |
| `Please tell me who you are` | Git doesn't know your name/email | `git config user.name "Aletheia"` and `git config user.email "voxaletheia@gmail.com"` |
| `CONFLICT (content): Merge conflict in ...` | Same lines edited locally and on remote | Ask for help — needs hand-resolution |

## The "did it work" checks

After any sequence of git commands, run:

```
git status
```

What you want to see:
- `On branch main`
- `Your branch is up to date with 'origin/main'.` *(after a push)*
- `nothing to commit, working tree clean`

If you see all three of those, you're synced and clean.

```
git log --oneline -5
```

Shows the last 5 commits. The top one should match what's on github.com.
