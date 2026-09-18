# Scheme: pick a `case-lambda` clause statically at a direct call

Difficulty: Medium

`.todo/869` lowers `(define f (case-lambda ...))` to one `(defun f (&rest A) ...)` that
counts `A` and takes each formal with `nth` on every call. Measured (2026-09-18,
`.kb/scheme-frontend.md`, "`case-lambda`"): 20M calls of a two-clause `case-lambda`
against a plain two-argument `defun`, JVM 0.20 vs 0.13 s, wasm 1.09 vs 0.26 s.

A direct call `(f a b)` knows its argument count at lowering time. Emitting one `defun`
per clause (`f` keeps the dispatching one for first-class use and `apply`) and lowering a
direct call to the clause's own `defun` would remove the rest list, the count and the
`nth`s. Constraints: a self tail call through another clause must stay a loop (today a
`selfLoop` jump with a rest carrier, pinned by the `cl-count 100000` line of
`scheme-spec.yaml`); a direct call no clause accepts should be a positioned lowering
error, like `(car 1 2)`; the per-clause names must be unforgeable (`%SCM-` spelling).

Measure the same loop after; if the JVM gain is noise, record that and stop.
