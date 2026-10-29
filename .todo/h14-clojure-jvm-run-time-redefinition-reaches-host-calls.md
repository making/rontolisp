# Clojure on the JVM: a run-time redefinition reaches the host's compiled calls

Difficulty: High

Carried out of h01. On the interpreter a `defn` an `eval` (or a REPL input) lowers over an
existing `defn` re-points every function cell the program's calls name, so it reaches every
caller, as the oracle's calls through the var do (`.kb/nrepl.md`, "Redefinition in an open
lowering"). A JVM host cannot: its calls of a program `defn` are static calls of its own
method, so a fragment's redefinition reaches only the fragments lowered after it
(`.kb/jvm-run-time-lowering.md`, Limits). The interpreter and the JVM leg therefore answer
`(defn g [] 1) (defn f [] (g)) (eval '(defn g [] 2)) (f)` differently (2 and 1; the oracle 2).

Shape to weigh: in a host, route each call of a program `defn` through a per-function
indirection the fragment can set again -- an `invokedynamic` call site over a
`MutableCallSite` per function, relinked by the fragment's definition -- only in a class
that lowers at run time (an ordinary class keeps its static calls). Measure the indirection
on a call-heavy host (a recursive `fib`, a tight `loop` of calls) against today's static
call before choosing; `.kb/jvm-self-tail-calls.md` and `.kb/jvm-tail-bounce.md` constrain
what a call may become.

Acceptance: the line above answers 2 on the JVM leg of clojure-spec, and a `def` over a
host `defn` (`(def f (memoize f))`) reaches the host's recursive calls as on the
interpreter.
