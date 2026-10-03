# c22. On the JVM a closure whose creator was shaken keeps its dispatcher case

Difficulty: Medium

A lambda's funcId joins `Ctx.valueFuncIds` when Pass 2 compiles the `(lambda ...)` that
creates it, so `_invoke_<n>` carries a case for it -- an `invokestatic` of its method --
even when the writer's shake then drops every method that could create it (a wrapper body
the program never calls). Nothing can call such a closure, but the case keeps its method,
and whatever that reaches, alive. Measured 2026-10-03 (lambda methods whose funcId no
method but a dispatcher or `_lookup` pushes): bench-report `sort` 20 of 21, `clos` 21 of 23,
`string` 20 of 21, size-report `zlib` 21 of 83, ci-spec 8 of 1,387, clojure-spec 47 of
1,811, a cl-ppcre program 50 of 238 (700-2,200 B of their code alone). In `clos`, `sort`
and `string` three of them are `reduce :from-end`'s argument-swapping lambdas, whose tails
bounce: they alone keep `_tramp` written (`.kb/jvm-tail-bounce.md`, "The gate").

## Plan

- Make a closure's dispatcher case live only while a method that creates it is: an edge the
  shake follows from the creating method (`OwnCallGraph`), not from the dispatcher; a named
  function's `#'name` value the same way, `_lookup` keeping what a name can reach.
- Measure bench-report, size-report and the examples suite's classes before and after.
