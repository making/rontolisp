# 392. A few examples test themselves with rove instead of a needle in the manifest

Difficulty: Medium

`.todo/372` made rove real on all four backends (`RoveE2eTest`,
`doc/{en,ja}/guides/testing.md`). Nothing in the tree USES it outside that
test's own demo project, and the examples are the natural first consumer:
today an example that checks itself prints and hopes.

## What the examples do today (surveyed 2026-08-16)

- There is **no `.asd` anywhere under `examples/`**, no `t/` or `tests/`
  directory, no `:perform (test-op ...)`, and not one `(assert ...)` call.
  The single `*test*.lisp` (`deep-learning-from-scratch/ch06/batch-norm-test.lisp`)
  is the book's experiment script, not a test.
- Three shapes come closest to a self-test, all `format`-based, all exiting 0
  whether they pass or fail:
  - the seven `examples/cloudflare-workers/*/check.lisp` drivers -- they build a
    request, call `handle-request`, and print the result;
  - the PASS/FAIL printers `deep-learning-from-scratch/{ch05/gradient-check,
    ch06/batch-norm-gradient-check, ch07/gradient-check}.lisp`;
  - the self-cross-checks `console/{roman,sorting,calc}.lisp` (round-trip,
    vs. the built-in `sort`, vs. the built-in `eval`).
- The verdict does not live in the Lisp at all: it lives in
  `examples/examples.yaml`'s `expect:` (`equals` / `file:` ->
  `examples/.expected/*.txt` / `contains`), read by
  `src/test/java/am/ik/rontolisp/e2e/ExamplesE2eTest.java` -- 97 entries x the
  backends each declares (RUN: `interpreter`/`jvm`/`wasm`; COMPILE-only:
  `jvm-compile`/`wasm-component`/`no-gc`/`no-gc-simd`).
- `ExamplesE2eTest` is opt-in (`-Drontolisp.examples=true` or
  `-Drontolisp.binary=...`), and `ci.yaml` neither opts in nor triggers on
  `examples/**`, so **no example is verified by CI at all**. Widening that is
  NOT this item (see non-goals) -- but do not write anything here that claims
  CI covers it.
- `examples/browser/minesweeper/minesweeper-core.lisp` calls itself a pure
  state machine that touches neither screen nor randomness, and is **not in
  `examples.yaml`**: nothing verifies it today.

## The work

Convert THREE examples to rove, chosen for zero external resources and full
determinism, and leave the rest alone:

1. `examples/console/roman.lisp` -- the 1..3999 round-trip is already the
   assertion, spelled as an `all-match` flag.
2. `examples/cloudflare-workers/httpbin/check.lisp` -- already a driver with a
   `try` helper per case, and library-free (plain defuns + a hand-written
   adapter), unlike its `hello-clack` / `ningle` siblings.
3. `examples/browser/minesweeper/minesweeper-core.lisp` -- add a test file
   beside it and put it in the manifest; this one buys NEW coverage.

`console/sorting.lisp` and `console/calc.lisp` are the same shape as (1) and
are fine to add if they cost nothing; the `deep-learning-from-scratch`
gradient checks are deliberately out (they stage `workDir`/`workFiles`, load
`two-layer-net.lisp`, and take seconds on the interpreter).

### How the example gets rove

Decide and record the reason. The two candidates:

- **The vendored copy** (`src/test/resources/{rove,dissect,cl-ppcre}`, already
  checked in for `RoveE2eTest`): the repo stays self-contained and the E2E runs
  offline. Costs a manifest change -- see the `systemPath` defect below.
- **`(ql:quickload "rove")`**, the entry point `guides/testing.md` teaches and
  what ~20 examples already do for other libraries: reads naturally for a user
  who copies the example, but the first run needs the network and the
  `~/.rontolisp/quicklisp` cache.

Recommended: the vendored copy for the manifest/E2E wiring, with the example
source spelled the way a user would write it. Whatever the example source
says, `-Drontolisp.examples=true` must pass with no network.

**Defect that blocks the vendored route**: `ExamplesE2eTest.systemPathFlags()`
does `PROJECT_DIR.resolve(example.systemPath())` and passes ONE value, but
`--system-path` takes a `File.pathSeparator`-joined list
(`RontoLispCli.systemPath()`). Writing `a:b` in the manifest therefore
absolutizes only the first element and leaves the rest relative to a throwaway
CWD. rove needs three directories, so `systemPath` must accept a LIST, each
element absolutized separately and joined with the separator. The single
existing user (`net/http-handler-cl-who.lisp`, one directory) must keep working.

### Constraints rove imposes on the converted examples

- `(setf rove:*enable-colors* nil)` first -- colors are on by default.
- The run must FAIL when an assertion fails: end with
  `(uiop:quit (if (rove:run ...) 0 1))` (real on all four backends since
  `.todo/362`'s exit half). `ExamplesE2eTest` requires exit 0, so a broken
  example then breaks the test, which is the point of this item.
- Do not pin the whole report with `expect: equals`: the printer spells
  symbols with their package qualifier (`.todo/391`), so those lines will
  change when 391 lands. Pin the summary lines, or `contains`. Any
  `.expected/*.txt` for a converted example is regenerated or retired.
- No raw wasm trap in a test body (`(car 1)`, `(/ 1 0)`) -- uncatchable on the
  wasm backends (`.kb/error-handling.md`).
- `deftest`'s `:compile-at :run-time` is interpreter-only (`.todo/384`).
- The wasm legs need `-W exceptions=y`; `ExamplesE2eTest` already passes it.

## Acceptance

- `examples.yaml` accepts a LIST `systemPath`, each element absolutized, and
  the existing single-string spelling still works; pinned by an
  `ExamplesE2eTest`-level test that does not need the examples opt-in if that
  is possible, otherwise by running the converted examples.
- The three converted examples run on every backend they declare, with the
  assertions in the Lisp source, and a deliberately broken assertion makes the
  run exit non-zero (verify by hand once, on all four backends).
- `examples/README.md` says how an example tests itself with rove and points at
  `doc/{en,ja}/guides/testing.md`; the guide gains the "an example does this"
  cross-link. Both doc trees change together.
- `./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true test`
  is green offline, and the normal `./mvnw test` is unaffected.

## Non-goals

- Converting every example, or the `deep-learning-from-scratch` gradient checks.
- Making `ci.yaml` run `ExamplesE2eTest` / trigger on `examples/**`. That gap is
  real and older than this item; a separate todo if it is worth closing.
- A `rontolisp test FILE` CLI (the roswell `rove` script mirror) -- already
  named as a follow-up when `.todo/372` landed.
- Retrofitting `.asd` files onto examples that do not need one: an example that
  loads its test file directly is fine; only reach for a system when the
  example already has several files.
