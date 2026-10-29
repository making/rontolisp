# Clojure: run-time lowering on the JVM backend

Difficulty: High

Carried out of g83, which made `eval`, a computed `resolve`/`find-var`/`ns-resolve`/
`requiring-resolve`, a computed `require`/`use`/`refer`/`alias`/`in-ns`, `load`/`load-file`/
`load-string` of computed arguments and `load-reader` lower as the program runs on the
interpreter (`.kb/clojure-frontend.md`, "Lowering at run time"). A compiled JVM class still
refuses them by name (`%clojure-lower-at-run-time`'s `UnsupportedOperationException`).

Why the interpreter's mechanism does not carry over (read 2026-10-29):

- The interpreter evaluates the run-time datum's core forms in the same evaluator, over the
  same `LispVal` values. A compiled class's values are its own representation (`Object[]`
  conses, `RontoHashTable` ...), its functions static methods, its globals static fields; the
  embedded `_eval` (`.kb/eval-runtime.md`) takes a small subset of Common Lisp (no `let*`,
  `block`, `handler-case`, no macros), far below what lowered Clojure is.
- The lowerer (`am.ik.rontolisp.clojure`) and the reader are not in the travelling runtime
  (`.kb/jvm-export.md`, "What travels"), so a `-o App.class` run has neither.

The essential solution is separate compilation: the class carries (or finds on its class
path) the front end and the JVM backend, and a run-time datum's forms compile to a fresh
class linked against the running one -- its functions by name, its globals through their
fields, the library's state (`%clojure-var-table`, the namespace table, protocol tables)
shared, never copied -- then load and run. Needs: the main class's symbol table at run time,
a fragment mode in `JvmLispCompiler`, the whole `clojure.lisp` reachable (a fragment may
call a helper the program's shaker dropped), and `.todo/330`'s question of what an
eval-capable class carries.

Acceptance: clojure-spec `the-interpreter-lowers-at-run-time` green on the JVM leg with its
`jvm:` skip removed, under `-o App.class` and `-o app.jar` runs.
