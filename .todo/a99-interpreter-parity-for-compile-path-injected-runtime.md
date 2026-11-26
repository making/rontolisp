# a99: interpreter parity for the compile path's injected runtime

Difficulty: High

## Premise (measured, 2026-09-30)

The interpreter lazy-loads the compile path's injected runtimes per NAME
(`resolveFunction` branches: condition report, restart, format renderer, uiop, ...), but
the coverage has holes that only show when the compile path's splices run as PROGRAM code
on the interpreter -- which today happens via a `--dump-ir` re-run (the dump carries the
spliced defuns; the backends get the paired injections, the interpreter does not), and
tomorrow via any feature that feeds compile-path output back through `eval`. Measured on
the native binary against the concatenated ci-spec program's dump:

1. `%baked-packages%` / `%runtime-packages%`: no interpreter-side definition at all
   (the compile path injects the baked table in `LispMacroExpander.injectBakedPackageTable`
   after package resolution). A lazy define in `evalSymbolRef` from
   `this.packageResolver` (via a `bakedPackageTableForm` refactor) clears the unbound
   error, then `PACKAGE-USE-LIST: no package named CI-RPKG` -- the runtime tier is never
   maintained because the interpreter's package mutations go through the Java builtins.
   Real fix = the two-tier table maintained by the interpreter's package ops, or an
   honest decision that the Lisp package defuns never run there (they would need to stop
   shadowing: see 3).
2. `%error-runtime`: no lazy load. Both `runtimeErrorDefuns` variants were prototyped
   and REVERTED -- hookless and hooked alike left a typed condition UNHANDLED in the
   re-run, so the defun bodies' signal semantics on the interpreter need understanding
   (the interpreter's own error expansions carry `signalHook=true` and re-expand per
   eval; the defuns freeze one shape) before any lazy load ships.
3. Typed arrays: `(array-element-type #32@(...))` answers T and `%row-major-aset` into a
   `#8@` literal skips the wrap (999 vs 231) in the re-run -- some dump-carried defun
   shadows the typed behavior; not isolated.

Worth doing only when a consumer needs it (today: none -- the a98 boundary documents the
limitation; the dump stays a diagnostic). The item exists so the measurements and the
prototype's negative result survive.

## Shape

Per hole, decide implement-vs-document: the two-tier table is the only structural piece;
the error dispatcher needs its interpreter semantics pinned first (why did both hook
variants fail?); the typed-array shadow needs isolating before anything else. Each fix
lands with its pinning test on the re-run path (dump a splice-triggering program, re-run,
assert) -- the corpus `dump round-trip` leg from the a98 experiments is the acceptance
run, rebuilt per the description in the a98 history.

## Tests

- Per-hole re-run pins as above; `.kb/running-backends.md` four-backend pass at the end.
