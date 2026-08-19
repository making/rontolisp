# 453. The docs stop teaching `--optimize` as opt-in (en + ja)

Difficulty: Medium

Child of `.todo/448`; needs `.todo/449`'s spelling to exist first.

**Folds `.todo/414`** (the compiling pages' dispatch-gate list is stale). That
item rewrites the same two passages of the same two pages, and doing them
separately means editing `compiling/{jvm,wasm}.md` x2 languages twice. Read
`.todo/414` in full before starting -- its "verify against the code, not against
this file" instruction and its "re-run `-Drontolisp.debug.dispatchgate=true` and
paste what it actually prints" instruction both apply here unchanged. Close 414
in the same pass (remove the file, record the row).

## What has to change

`--optimize` appears on 14 pages per language, ~50 occurrences each. Three
kinds, and only the first is a rewrite:

**1. The two passages that TEACH the flag** -- `doc/en/compiling/jvm.md`
(L29-L76) and `doc/en/compiling/wasm.md` (L89-L300), plus their `doc/ja` twins.
Today they open with "By default a compiled class embeds the **entire** runtime
... Add `--optimize` to drop ...". They have to open with what the compiler
does, and mention `--optimize=off` as the way to decline. The level list gains
an `off` row beside `default` and `size`. The phrase "is opt-in and
behavior-preserving" keeps its second half and loses its first.

The `off` row needs a REASON to exist, or a reader will read it as vestigial.
What it is actually for: comparing emitted output against a previous build,
and bisecting a suspected shaker bug. Say that, and say that a program whose
functions are reached only through a name the compiler cannot read needs
`--dynamic`, not `--optimize=off` -- the funcall-dispatch gate is not part of
what the level switches (`.todo/448` measured this), so `off` does not bring
such a name back.

**2. The command lines that pass the flag** -- one in each of
`guides/{wasm-browser,wit-contracts,wasm-nogc,wasm-component,simd-acceleration,clack,wasm-host-boundary,http-handler,http-fetch,clock-and-random}.md`
and two reference pages. Bare `--optimize` there is now redundant but not
wrong. Strip it where it is noise and KEEP it where the surrounding prose is
about the flag; `--optimize=size` is never redundant and always stays. Decide
per page, not by sweep -- a command line that loses the flag and keeps a
sentence explaining it reads worse than one that keeps both.

**3. The one-line mentions** -- `README.md` L82 (the flag list) and
`docs-tool/src/main/resources/skill/SKILL.template.md` L76 (the "flags that
change the emitted artifact" list). The skill is a VIEW of `doc/`, so its
language rule is written on the doc page and inlined; do not restate the new
default in the template beyond the flag's name.

## Also

- `.kb/optimize-dead-code-elimination.md`'s level table is `.todo/449`'s edit,
  not this one. What belongs HERE is nothing -- the `.kb` is agent-facing and
  the flip is one line there. Do not mirror the doc prose into it.
- `doc/en/compiling/dynamic.md` describes `--dynamic`; check whether it
  contrasts itself against "unoptimized" output anywhere.

## Procedure

`doc/en` and `doc/ja` in the SAME commit: same file set, same headings,
byte-identical code fences, only prose and titles translated
(`.kb/documentation-site.md`). Then:

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixShownResults test
./mvnw -Dtest=DocExamplesTest test
./mvnw -f docs-tool/pom.xml test
```

## Acceptance

No sentence in `doc/`, `README.md`, the skill template or either
`printUsage()` describes `--optimize` as something you add to get a smaller
artifact; every one of them describes `--optimize=off` as the way to decline.
`.todo/414`'s two consequences (the stale gate list, the `INTERN` example
output) are resolved in the same pass, and its file is removed with a row in
`.todo/.history.md`.
