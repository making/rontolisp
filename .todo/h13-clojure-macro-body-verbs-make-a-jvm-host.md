# A lowering verb only a macro body names makes the whole program a JVM host

Difficulty: High

`ClojureLowering.lowerWhole` opens the program (`ClojureRunTime.namesAny`) when any lowered
form names a run-time lowering entry, macro bodies included. A `defmacro` whose body calls
`eval` / `resolve` / `ns-map` over its argument -- data.priority-map's and instaparse's
`compile-if`, camel-snake-kebab's `defconversion`, tools.reader's `resolve-symbol` -- runs
while the program lowers, yet the JVM class compiles as a host: the whole library
unstripped, threads mode, no raw globals, and the compiler carried (a jar near 10 MB).
`ns-map` and its siblings as a value open it too (`%clojure-ns-computed-here`).

Measured 2026-10-30: four clojure-spec cases did this (`.kb/jvm-run-time-lowering.md`,
"Trap"); they are marked `host:` so the corpus stays ordinary.

Plan: decide whether a macro's run-time function is reachable at all (run-time
`macroexpand`, `#'mac` called, the macro passed as a value). Where it is not, its body's
verbs must not open the program; where only a value reaches the verbs, weigh refusing at
call time against carrying the compiler. Then move each case the change makes ordinary back
into the corpus (drop its `host:`); the JVM leg only notices a stale mark when the whole host
program stops being a host.
