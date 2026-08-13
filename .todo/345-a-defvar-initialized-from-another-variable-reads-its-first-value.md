# `(defvar *b* *a*)` reads `*a*`'s FIRST value on both compile paths

Difficulty: Medium

Found while adding the `character-index-is-not-linear-in-the-index` ci-spec case for
`.todo/185`, whose string builder was `(defvar *long* *short*)` after a loop had grown
`*short*`. The interpreter built a 131,072-character string; both compile backends built
2,048 and the case failed on its own arithmetic, not on what it was testing.

## Repro

```lisp
(defvar *s* "ab")
(setq *s* (concatenate 'string *s* *s*))   ; *s* is now "abab"
(defvar *v1* *s*)
(defvar *v2* (progn *s*))
(defvar *v3* (identity *s*))
(defparameter *v4* *s*)
(princ (list (length *v1*) (length *v2*) (length *v3*) (length *v4*)))
```

| backend | result |
| --- | --- |
| interpreter | `(4 4 4 4)` |
| JVM | `(2 4 4 2)` |
| WASM Preview 1 | `(2 4 4 2)` |

So it is exactly the BARE VARIABLE REFERENCE in the initializer position of `defvar` /
`defparameter`: wrap the same reference in anything (`progn`, `identity`) and the value is
current. `*v1*` gets `*s*`'s value as of its own `defvar`, i.e. the top-level `setq`
before it did not happen.

## What is already ruled out

- **Not new.** Reproduces identically on `f4f80509`, before any of `.todo/185`.
- **Not the backend.** `new JvmLispCompiler("X").compile(LispPreludeLibrary.process(
  LispReader.readAllFromString(src)))` prints the CORRECT `4`. It is the CLI's own
  pipeline that introduces it, so the compiler under test is fine and the bug travels
  through `RontoLispCli` -- which is also why `JvmLispCompilerTest` cannot see it.
- **Not `UserMacroExpander.expand`, `LispPreludeLibrary.process` or
  `CompileTimeBoundp.fold`**: adding each (and all three) to that bare harness still
  prints `4`.
- **Not the tree-shaker or late binding**: `--no-prune` and `--dynamic` both still print
  `2`.

Bisect the rest of the `RontoLispCli` chain from there (the `loaded = ...` splices,
`WitExportInliner`, `LibraryDefunPruner`) -- something is treating a defvar whose init
form is a lone symbol as a reference to that symbol's INITIAL form rather than to its
value, which is the shape of an alias/propagation rewrite.

## When it is fixed

`src/test/resources/ci-spec.yaml`'s `character-index-is-not-linear-in-the-index` case
builds its long string by doubling a literal 13 times, with a comment pointing here,
precisely because `(defvar *scan-long* *scan-short*)` could not be trusted. Restore the
shorter spelling then -- it is the natural one, and its absence is the sign this is still
open.
