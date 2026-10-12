# f64. JVM: a single top-level form past 64 KB of code fails the compile

Difficulty: High

Found by the library probes (2026-10-12): a hiccup 2.0.0 program whose `(h/html ...)` literal
expands into one variant per `*html-mode*` x `*escape-strings?*` (8 compiled bodies) compiles
on the interpreter and both wasm backends, but `-o Probe.class` stops with
`method _top$1: method code exceeds the JVM's 65535-byte limit: 69502`
(`-Drontolisp.jvm.debug-method-sizes=true`: `_top$1` 69,502 B, `_top$0` 34,286, `_top$2`
31,845).

`.kb/jvm-method-size-limits.md`: the top level is chunked only BETWEEN forms
(`chunkCodeBudget` 24,000); one form whose own code passes the limit has nowhere to go, and
`JvmBodyOutliner`/`AstOutliner` do not reach a top-level form's subexpressions.

## What decides the design

- Where the outliner can cut: the branches of a large `case`/`cond`/`if` (hiccup's variants)
  and long argument lists are independent subexpressions; each could become a method of its
  own closing over nothing (a top-level form has no locals but its own `let`s).
- A Clojure program's top-level datum lowers to one form; a lowering-side split (each
  variant's body behind its own `defun`) would fix hiccup but not a large Lisp form.

## Plan

1. A repro: a top-level `(println (case k ...))` of enough large literal branches, failing now.
2. The outlining of a too-large top-level form; the hiccup probe on the JVM, oracle-identical.
3. `.kb/jvm-method-size-limits.md` updated with the measurement.
