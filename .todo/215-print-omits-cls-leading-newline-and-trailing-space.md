# `print` omits CL's leading newline and trailing space

Found 2026-07-30 while diffing the REPL against SBCL 2.2.9 on the host (the
multiple-value echo work). **Deliberate and documented**, not a regression -- this
item exists so the divergence has a recorded reason and a cost estimate, because
it is the one remaining difference in an otherwise byte-identical REPL transcript.

CLHS: `print` is `(progn (terpri) (prin1 object) (write-char #\space))` -- a
newline BEFORE the object and a SPACE after. Ours is `prin1` text plus a TRAILING
newline (`doc/*/reference/functions/print.md` says so; `.kb/no-gc-scalar-wasm.md`
and `.kb/clos.md` record the same shape for the backends):

```console
$ sbcl --noinform                  $ rontolisp
* (print "hi")                     > (print "hi")
                                   "hi"
"hi"                               "hi"
"hi"
```

(SBCL: blank line from the leading `terpri`, then `"hi" ` with a trailing space,
then the REPL's echo. Ours: `"hi"` + newline, then the echo.)

## Why it is this way, and why changing it is expensive

Our shape is "one datum per line", which is what every example, doc output block,
test expectation and ci-spec `expected:` in the repo is written against. `print` is
the primary output primitive of the whole project: the CL shape would rewrite the
expected output of essentially every test and every documentation page, on four
backends (the WASM ones emit the newline from hand-written bytecode funnels, and
`--no-gc` from a literal pool), and every program a user has written against the
documented behavior.

## Scope, if it is ever done

- Change the four implementations together (`Environment` print family, the
  `Jvm`/`Wasm` print runtime builders, `NoGcWasmCompiler`), keeping `princ`,
  `prin1`, `write-line` and `terpri` as they are -- only `print` is at issue.
- Regenerate every doc output block (`-Drontolisp.doc.fix=true`) and every
  ci-spec `expected:`, then the native E2E.
- Keep the fresh-line column tracking correct: `print`'s leading newline is a
  `terpri`, NOT a `fresh-line`, so it emits a blank line when output is already
  at column 0 -- that is the CL behavior and it will look like a bug in every
  transcript unless it is deliberate.
- Alternative worth weighing: leave `print` as is and document the divergence in
  `doc/*/guides/missing-features.md` (today only the per-operator reference page
  states the behavior, so a reader comparing with SBCL has nothing to find).
  That is cheap and probably the right first step.

## Non-goals

- The REPL's prompt placement. SBCL reads one form at a time and prints `* `
  before each echo; ours reads a whole balanced buffer and prints `> ` once, so
  two forms typed on one line echo both values under one prompt. The VALUES and
  their order are identical (`RontoLispCliTest.replEchoesEveryValueOnItsOwnLine`);
  only the prompt count differs, and matching it would mean re-architecting the
  reader loop for no semantic gain.
- `princ`/`prin1`/`format` -- their SHAPE (no leading newline, no trailing space) matches
  CL. Their string escaping does not: see
  [216](216-prin1-family-does-not-escape-quotes-and-backslashes-in-strings.md).
