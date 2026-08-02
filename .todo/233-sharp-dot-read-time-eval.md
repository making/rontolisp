# `#.` read-time eval in the shared reader

Difficulty: 中 (one hard session — the feature is small but the
architecture constraint is the work: `reader` cannot depend on `eval`)

Split out of `.todo/231` (2026-08-02 survey). The reader currently SKIPS an
unsupported `#.` form with a warning and returns nothing, which turns a
read-time-generated definition into an unbound-variable error later:
fast-http's multipart-parser generates its 14 state `defconstant`s via
``#.`(eval-when (...) ,@(loop ... collect `(defconstant ...)))`` and dies on
`+PARSING-DELIMITER-DASH-START+` unbound. This is fast-http's ONLY blocker
beyond its smart-buffer dep (see `.todo/231`).

Design notes from the survey:

- Package rule: `reader` sits below `eval` and must not import it. `#.`
  therefore needs an EVALUATOR HOOK injected into `LispReader` (a
  `Function<LispVal, LispVal>` supplied by the caller), not a direct call.
  The interpreter passes its own eval; the compile-path CLIs (cli depends on
  eval already) pass an interpreter instance — read-time eval ALWAYS runs
  interpreted, even when compiling, which matches CL (`*read-eval*`
  semantics; compile-file evaluates `#.` at read time).
- The existing skip-with-warning behavior (seen on xsubseq/proc-parse
  README-slurping `#.(with-open-file ...)` forms, which are harmless
  docstring fillers) must remain the FALLBACK when no hook is supplied
  (e.g. the browser playground without a filesystem) — or those systems
  regress. Decide: hook absent -> warn+skip (today's behavior), hook
  present -> evaluate.
- `*read-eval*` nil should signal, per CLHS, once the feature exists.
- Grep `.kb/reader-features.md` / `.kb/read-load-streams.md` before
  changing; the reader is shared by every backend and by `read` at runtime,
  so the runtime `read` builtin needs the hook too (its evaluator is
  available where it is defined).

Pin: a case that `#.`-generates a defconstant and uses it, on all four
backends, plus a `ci-spec.yaml` case.
