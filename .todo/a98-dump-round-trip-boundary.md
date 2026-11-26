# a98: state and pin where the --dump-ir round trip holds

Difficulty: Low

## Premise (measured, 2026-09-30)

The a96 corpus-scale round-trip experiment (dump the concatenated ci-spec program with
the native binary, re-run it through the interpreter, diff) established where the a95/a96
dump's "feed it back and it runs the same program" promise holds and where it does not:

- Holds: programs whose forms lower to plain core forms -- the CLI suite's round-trip
  tests, and every hand program tried.
  Diverges: a dump that carries the compile path's RUNTIME SPLICES (the package
  prelude's `rename-package` / `%baked-package-find` / ... defuns, the `%error-runtime`
  dispatcher, the typed-array helpers). Re-run on the interpreter, those defuns shadow
  the Java builtins and read state the interpreter never defines (`%baked-packages%`,
  the `%runtime-packages%` tier) or whose Lisp-shape semantics were never exercised
  (the typed `%row-major-aset` wrap). Concrete failures are recorded in [[a99]].

The valuable residue of the original corpus-equality plan is exactly this boundary, made
explicit and pinned -- not the interpreter parity work, which is a99's and only pays when
someone needs it.

## Shape

- `doc/{en,ja}/compiling/ir-dump.md`: the round-trip paragraph states the boundary -- a
  dump of a program the runtime splices reached re-runs on the COMPILE backends by
  construction; on the interpreter it can diverge (one sentence, no mechanism tour).
- One CLI test beside the existing round-trip ones: a program that triggers a runtime
  splice (a package API call pulling the package prelude into the dump), whose dump
  re-runs is NOT asserted equal -- the test pins the documented boundary by asserting
  what the doc claims, nothing more (green today, so it guards the claim's wording).
  If even that cannot be asserted honestly, the test asserts only the pure-core case
  that exists already and this item collapses to the doc sentence.

## Tests

- The boundary test above, plus the untouched CLI round-trip suite.
