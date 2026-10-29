# Clojure: core macros expand to the oracle's special forms as data

Difficulty: High

`(macroexpand-1 '(let [x 1] x))` answers the form itself here (a core macro lowers inline in
Java and owns no expander, `.kb/clojure-frontend.md` "Macros"); the oracle answers
`(let* [x 1] x)`. A macro's var is not callable the oracle's way either: a program macro's root
is a signal, a core macro's the one-argument run-time delegate (g16), where the oracle's var
holds `(fn [&form &env & args] ...)`. And the special forms the oracle's expansions use are
refused as heads: `(let* [a 1] a)` is `unknown name: let*`, likewise `loop*`, `letfn*`,
`case*`.

tools.analyzer.jvm's `macroexpand-1` calls `(apply v form (:locals env) (rest form))` on the
resolved macro var and parses only special forms, and core.async's `go` re-emits the
analyzer's expanded forms, so the `go` chain needs all three (`.todo/g87`, "core.async and
tools.analyzer.jvm"); `clojure.walk/macroexpand-all` users and riddley-style walkers need the
first.

1. Lower `let*`, `loop*`, `letfn*` and `case*` (`(case* e shift mask default case-map
   switch-type test-type skip-check)`: the `[test then]` entries by equality, a skip-check
   entry's `then` already holding its `condp`), each oracle-measured.
2. Ship the core macros' expansions as Clojure source written from their documented behaviour
   (`let`/`fn`/`loop` with `destructure`, `when`, `cond`, `and`, `or`, `case`, `->`, `doseq`,
   `dotimes`, `if-let` ... the ones a `go` body meets first), answering `macroexpand-1` and a
   macro var's `(&form &env & args)` call, while a program's own call sites keep the Java
   lowering rows.
3. A program macro's var root becomes its expander in the same calling convention.

Weigh the size of a program that never expands at run time (it must stay byte-identical)
and pin the expansions against clj 1.12.6.
