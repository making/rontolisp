# A Scheme reference: one page per procedure, syntactic keyword and constant

Difficulty: Medium

Common Lisp has `reference/functions/` (a `_catalog.yaml`, per-package table pages and one
page per name). Scheme has only `doc/<lang>/scheme/libraries.md`'s per-library tables
(`.todo/859`). Add the same kind of reference for everything `Scheme.providedNames()`
answers:
`SchemeBuiltins` entries, constants and `SchemeLowering.syntaxNames()`. That is about 260
names as of 2026-09-18 (212 table entries).

## Layout

Put it under the Scheme section `.todo/859` creates. `Catalog.java` finds any
`_catalog.yaml` under the language directory, so the site generator needs no change;
confirm that with `./mvnw -f docs-tool/pom.xml test`.

- `doc/<lang>/scheme/reference/_catalog.yaml`: one category per R7RS library
  (`(scheme base)` split into sub-categories such as numbers, pairs and lists, characters,
  strings, vectors, control, and output; then `write`, `read`, `inexact`, `cxr`, `lazy`,
  `process-context`, `eval`, `repl`), then `r5rs` and `sicp`. Each category has an
  `index_page` holding a table of name, example and result, as
  `reference/functions/cl.md` does. Syntactic keywords are a category of their own,
  since `define`, `cond` and `import` are not procedures.
- Each category is the library the name is really exported from. That is the
  `SchemeBuiltins` tag, as corrected by `.todo/857`, whose retagging should land first.
  If it has not, write R7RS's library.
- Give Scheme pages their own slugs so they never collide with the Common Lisp
  ones (`scheme/reference/car.md` vs `reference/functions/car.md`). Check whether
  `SearchIndex` shows two `car` hits in a way a reader can tell apart, and label
  the Scheme ones if not.
- A detail page gives the signature, the behavior, and every deviation stated in
  Scheme terms (current port only, escape-only `call/cc`, `sqrt` of a negative
  refused, `#!unspecific`, and so on). These deviations currently live in
  `doc/<lang>/scheme/deviations.md` and in `.kb/scheme-frontend.md`. The page must not describe
  how the name is lowered; that belongs in `.kb`.
- Sidebar: one entry "Reference" in the Scheme section, with the category table pages as
  `subpages:`, the way `reference/functions.md` does.

## Verified examples

`DocExamplesTest` checks a ```` ```scheme ```` block only as a whole program against the
stdout block that follows it. A reference page needs the per-form `; =>` check the
`lisp` fences have:

- Extend `DocExamplesTest` so that a `; =>` annotation in a `scheme` block is compared
  with the form's value printed by `%scheme-write`, the text a Scheme REPL echoes.
  `fixShownResults` must rewrite it the same way. The easy route is a `SchemeSession`
  per block, whose echo is already that text. Pin the new branch with a
  deliberately wrong annotation that must fail.
- Every table row's example must be backed by a checked block on the detail page.

## Completeness pin

A test must assert that the catalog's names equal `Scheme.providedNames()` in both
languages, with nothing missing and nothing extra. Otherwise a builtin added later
silently has no page. Put it where the core module can see `doc/`, as `DocExamplesTest`
does, and name it in `.kb/scheme-frontend.md` and `.kb/adding-primitives.md`: a new
Scheme builtin needs a reference page.

## Rules

- `doc/en` and `doc/ja` in the same commit: same file set, same headings,
  byte-identical code fences. `_catalog.yaml` category titles are translated.
- Once the reference exists, the guide's procedure paragraph becomes a link to it
  (`.todo/859`'s `libraries.md` shrinks to a per-library summary). Do not keep two
  lists.
- Verify with `./mvnw -Dtest=DocExamplesTest test` and
  `./mvnw -f docs-tool/pom.xml test`.

Order: after `.todo/859` (the section it lives in) and the retagging of `.todo/857`.
