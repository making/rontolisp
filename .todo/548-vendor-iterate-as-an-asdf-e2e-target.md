# Vendor iterate as an ASDF E2E target

Difficulty: Medium

`(ql:quickload :iterate)` works and 22 clause shapes were verified by hand on
`java -jar` (commit "Run iterate: the reader, the three list functions and the hash
iterator it needs"). What the repo does NOT have is the standing proof the other
supported libraries carry: a vendored copy under `src/test/resources/` and an
`AsdfLibraryE2eSupport` subclass running it on ALL FOUR backends, the way
`EsrapE2eTest` / `TriviaE2eTest` / `MitoE2eTest` do. Without it a regression in any
of the four surfaces iterate leans on -- the `,.` splice spelling, the native `#L`
reader macro, the `ldiff`/`sublis`/`gentemp` prelude defuns,
`with-hash-table-iterator`, and `macro-function` answering nil for `while` -- shows
up only when someone quickloads iterate again.

## What to do

- Vendor iterate unmodified (MIT; quicklisp dist `iterate-release-d27d7ff4-git`) under
  `src/test/resources/iterate`, `package.lisp` + `iterate.lisp` + the `.asd`, the way
  esrap's tree is laid out.
- An `IterateE2eTest extends AsdfLibraryE2eSupport` whose acceptance list is the clause
  set already exercised by hand: `for ... from/to/downto/below/by`, `in`, `on`,
  `in-string`, `in-vector`, `in-hashtable`; `collect`/`collecting` with and without
  `into`, `sum`, `multiply`, `maximize`, `minimize`, `counting`, `always`, `thereis`,
  `appending`, `reducing`; `repeat`, `with`, `while`, `until`, `if-first-time`,
  `finally`, and a nested `iter` with `(in outer ...)`. Every expected value must be
  checked against the same sources on another CL before it is baked in.
- Whether iterate's own `iterate-test.lisp` can ride along is worth one look; it is
  written against `rt`, which is not vendored here, so the acceptance list above is the
  fallback and probably the answer.

## What to watch for

- The compile path, not just the interpreter: `#L` lowers in the reader (so all four
  backends get it free), but the prelude splice and the `with-hash-table-iterator`
  expansion are pre-passes -- `AsdfLibraryE2eSupport` already runs them, and that is
  exactly what makes the four-backend run worth having.
- iterate defines ~250 names at load time and walks every body it is given; if the
  WASM backend's module size or the JVM method-size cliff bites, that is a finding
  about `.kb/jvm-method-size-limits.md`, not a reason to trim the acceptance list.
