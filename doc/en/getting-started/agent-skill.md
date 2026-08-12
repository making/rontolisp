# Agent Skill

An AI coding agent that already knows Common Lisp still writes wrong rontolisp:
it reaches for operators the subset does not have, misses the extensions it
does have, and does not know how to run the result on each backend. The
**agent skill** closes that gap. It is this same manual, packaged as a skill:
a `SKILL.md` that carries the delta from Common Lisp plus every page here as a
bundled reference, generated on each deploy so it cannot drift from the
documentation you are reading.

## Install into Claude Code

The skill is published as a plugin. Add the marketplace and install it:

```bash
claude plugin marketplace add https://making.github.io/rontolisp/skill/marketplace.json
claude plugin install rontolisp@rontolisp
```

The same two steps work inside a session as `/plugin marketplace add ...` and
`/plugin install rontolisp@rontolisp`. Add `--scope project` to the first
command to declare the marketplace in the repository instead of your user
settings, so everyone working on the checkout gets the same offer.

Nothing else is needed: the agent consults the skill by itself when a task
involves rontolisp -- a `.lisp` or `.asd` file, or a request that names the
language.

```bash
claude plugin update rontolisp@rontolisp     # take a newer version
claude plugin uninstall rontolisp@rontolisp  # remove it
claude plugin list                           # what is installed
```

Claude Code re-reads the marketplace file from that URL, so a new version
becomes available without you changing anything -- see
[Staying current](#staying-current) for how the version moves.

## Install without the plugin system

A skill is a directory, and Claude Code reads every skill under `~/.claude/skills`
(yours) or `.claude/skills` (the repository's). If you would rather drop it in
than register a marketplace:

```bash
mkdir -p ~/.claude/skills && \
  curl -sSL https://making.github.io/rontolisp/skill/rontolisp-skill.tar.gz | tar xz -C ~/.claude/skills
```

Swap the target for `.claude/skills` to install it into the project instead.
Either way you end up with `skills/rontolisp/SKILL.md` and
`skills/rontolisp/references/`; `/skills` lists what is loaded, and removing the
`rontolisp` directory uninstalls it. Nothing updates it for you, so this is the
path where you check the version yourself.

## Other agents and hosts

| File | Use |
| --- | --- |
| [marketplace.json](https://making.github.io/rontolisp/skill/marketplace.json) | the plugin marketplace, added by URL as above |
| [rontolisp-plugin.zip](https://making.github.io/rontolisp/skill/rontolisp-plugin.zip) | the plugin itself, if you install plugins some other way |
| [rontolisp-skill.tar.gz](https://making.github.io/rontolisp/skill/rontolisp-skill.tar.gz) | the bare skill directory, for a `skills` folder |
| [rontolisp.skill](https://making.github.io/rontolisp/skill/rontolisp.skill) | the same tree as a zip, to upload where a skill is uploaded rather than unpacked |
| [SKILL.md](https://making.github.io/rontolisp/skill/rontolisp/SKILL.md) | the skill body alone, readable in place |
| [rontolisp-full.md](https://making.github.io/rontolisp/skill/rontolisp-full.md) | manual and skill as ONE Markdown file, for a tool that has no skill loader |

## Staying current

The skill is versioned `<release major.minor>.<number of commits that can change
it>`, so it moves exactly when the documentation or the generator does. As a
plugin, `claude plugin update rontolisp@rontolisp` takes the new one. Installed
by hand, compare your copy against the published version and reinstall when they
differ:

```bash
head -3 ~/.claude/skills/rontolisp/SKILL.md            # version: 0.1.391
curl -sSL https://making.github.io/rontolisp/skill/VERSION
```

Reinstalling is the same command as installing -- it overwrites in place.
[version.json](https://making.github.io/rontolisp/skill/version.json) carries the
same version plus the commit it was built from, if you would rather check it
from a script.

## What is inside

`SKILL.md` states the working rule -- Common Lisp knowledge is a *prior* here,
not the truth -- and inlines
[Unsupported Common Lisp Features](../guides/missing-features.md), because that
is what those priors get wrong most often. Under `references/`:

- `operators.md`, an index of every operator in the language by category. One
  lookup answers whether rontolisp has something, which is the question a
  Common Lisp background answers wrongly.
- `contents.md`, every page of this manual by title.
- every page of this manual, verbatim, at the same relative paths -- so a
  detail the skill needs is a file read away, with no network.
