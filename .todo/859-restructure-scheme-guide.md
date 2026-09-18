# Split `doc/{en,ja}/guides/scheme.md` into a Scheme section of pages

Difficulty: Low

`guides/scheme.md` is one page of about 295 lines, and its H2 headings (`REPL`, `What is
supported`, `eval`, `Deviations`, `Mixing with Common Lisp`) do not match the content:

- **"What is supported"** mixes four kinds of content: the reader, the syntax, the
  import list, and one unbroken paragraph listing every procedure of nine libraries.
  It also holds the SICP-compatibility names, streams, `parallel-execute`, flonum
  printing and a leftover `do`/`call/cc` example.
- **Library membership is lost.** The procedure paragraph only sometimes names the
  library (`(scheme inexact)`, `(scheme lazy)`). Someone writing
  `(import (scheme base) ...)` cannot tell what that import brings in.
- **The SICP-compatibility material is scattered.** `user-initial-environment` appears
  in "What is supported", in `eval` and in the REPL section.
- **"Deviations" mixes deviations with the "Not yet" list.**

## Target

A sidebar section of its own in `nav.yaml` (as `Compiling` is), replacing the single
`guides/scheme.md` entry in the Guides list. Scheme is a language, not a guide topic.
Do not use `subpages:`: those pages stay out of the sidebar
(`.kb/documentation-site.md`). Suggested pages; adjust the split if the content argues
for it:

| page | content |
|---|---|
| `scheme/index.md` Overview | experimental status, running on the four backends, `--source-language`, `--no-gc` refusal, one example, and a page map |
| `scheme/repl.md` | the REPL section |
| `scheme/syntax.md` | reader, special forms, `import` and its modifiers |
| `scheme/libraries.md` | one table per R7RS library (`base`, `write`, `read`, `inexact`, `cxr`, `lazy`, `process-context`, `eval`, `repl`): its procedures, restrictions (the current port only, and so on) and the printing notes (datum labels, flonum layout) |
| `scheme/sicp.md` | the SICP-compatibility names, streams, `parallel-execute` / `test-and-set!`, which environments are visible, and when these names are visible at all |
| `scheme/eval.md` | the `eval` section |
| `scheme/deviations.md` | deviations from R7RS, then a separate "Not yet" list |
| `scheme/common-lisp.md` | mixing with Common Lisp |

Rules (`CLAUDE.md`, `.kb/documentation-site.md`):

- Change `doc/en` and `doc/ja` in the same commit: same file set, same headings,
  byte-identical code fences.
- Keep every runnable example. `DocExamplesTest` must stay green; run it with
  `fixShownResults` only when an output genuinely moved.
- Delete `guides/scheme.md` rather than leaving a stub. Fix every link to it (`grep -rn
  "guides/scheme" doc/ src/ docs-tool/`), including the `--source-language` help text
  if it names the page, `.kb/scheme-frontend.md` ("the title of
  `doc/*/guides/scheme.md`") and `.todo/857`/`.todo/858`, which name the old path.
  The site has no per-page redirects: check whether `SearchIndex` or the agent skill
  (`SkillGen`) needs the new paths.
- Content does not change, except for fixing the library membership of each
  procedure. Use the `SchemeBuiltins` tags, as corrected by `.todo/857` if that has
  landed first; if not, write R7RS's membership and note the mismatch in `.todo/857`.
- Verify with `./mvnw -f docs-tool/pom.xml test` and `./mvnw -Dtest=DocExamplesTest test`.

Order: best done BEFORE `.todo/857` and `.todo/858`. Their doc changes then land on
the right page: `--scheme-standard` in `index.md` and `sicp.md`, `#!fold-case` in
`syntax.md`.

A per-name reference follows in `.todo/860`. Here `libraries.md` keeps the per-library
tables; `.todo/860` later turns them into summaries that link to the reference.
