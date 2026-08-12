# Agent Skill

An AI coding agent that already knows Common Lisp still writes wrong rontolisp:
it reaches for operators the subset does not have, misses the extensions it
does have, and does not know how to run the result on each backend. The
**agent skill** closes that gap. It is this same manual, packaged as a skill:
a `SKILL.md` that carries the delta from Common Lisp plus every page here as a
bundled reference, generated on each deploy so it cannot drift from the
documentation you are reading.

## Install into Claude Code

Skills live in a `skills` directory. Install for every project:

```bash
mkdir -p ~/.claude/skills && \
  curl -sSL https://making.github.io/rontolisp/skill/rontolisp-skill.tar.gz | tar xz -C ~/.claude/skills
```

Or only for the project you are in, by extracting into the repository instead --
useful when the whole team should get it from the checkout:

```bash
mkdir -p .claude/skills && \
  curl -sSL https://making.github.io/rontolisp/skill/rontolisp-skill.tar.gz | tar xz -C .claude/skills
```

Either way you end up with `skills/rontolisp/SKILL.md` and
`skills/rontolisp/references/`. Run `/skills` in Claude Code to confirm
`rontolisp` is listed. Nothing else is needed: the agent consults the skill by
itself when a task involves rontolisp -- a `.lisp` or `.asd` file, or a request
that names the language.

To remove it, delete the `rontolisp` directory from that `skills` directory.

## Other agents and hosts

| File | Use |
| --- | --- |
| [rontolisp-skill.tar.gz](https://making.github.io/rontolisp/skill/rontolisp-skill.tar.gz) | the skill directory, for a `skills` folder as above |
| [rontolisp.skill](https://making.github.io/rontolisp/skill/rontolisp.skill) | the same tree as a zip, to upload where a skill is uploaded rather than unpacked |
| [SKILL.md](https://making.github.io/rontolisp/skill/rontolisp/SKILL.md) | the skill body alone, readable in place |
| [rontolisp-full.md](https://making.github.io/rontolisp/skill/rontolisp-full.md) | manual and skill as ONE Markdown file, for a tool that has no skill loader |

## Staying current

The skill is versioned `<release major.minor>.<number of commits that can change
it>`, so it moves exactly when the documentation or the generator does. Compare
your copy against the published one and reinstall when they differ:

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
[Unsupported Common Lisp Features](../guides/missing-features.md), because that is what
those priors get wrong most often. Under `references/`:

- `operators.md`, an index of every operator in the language by category. One
  lookup answers whether rontolisp has something, which is the question a
  Common Lisp background answers wrongly.
- `contents.md`, every page of this manual by title.
- every page of this manual, verbatim, at the same relative paths -- so a
  detail the skill needs is a file read away, with no network.
