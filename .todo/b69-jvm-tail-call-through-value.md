# b69. Proper tail calls through a procedure value on the JVM

Difficulty: High

The biggest conformance gap left in the Scheme front end, and a general one: on the
JVM a tail call through a variable, an argument or `apply` consumes stack. A procedure
calling itself through an argument overflows at about 16,000 deep on the 16 MiB stack
(`doc/*/scheme/deviations.md`, first bullet). Proper today: named `let`/`do`, direct
self and mutual tail calls among a file's top-level procedures / one body's internal
definitions / one `letrec`'s lambdas; the interpreter and both wasm backends are
already proper everywhere (`.kb/wasm-tail-calls.md`, `.kb/interpreter-tail-calls.md`).

Direction: a trampoline or continuation-passing lowering on the JVM emitter for the
call sites the existing analyses cannot prove direct -- measure the interpreter-style
loop (`eval` re-binds and loops) against a `Thread`-sized-stack continuation object;
the answer must keep the fused integer arithmetic (`jvm-int-fusion`) and the HotSpot
8000-bytecode method limit (`hot-path-method-size`) intact, so a whole-program
transformation is probably wrong and the per-site shape matters. `--optimize` site
classification decides which calls stay direct (`optimize-dead-code-elimination`
dispatch pruning is prior art for proving the target).

Scope check first: how much of the gap the direct-call proofs already close (a
non-tail `funcall` never mattered; only TAIL positions through a value), and whether
`Clojure`/CL programs share the win or it stays Scheme-shaped.

## Pin

- A mutual/value-dispatch state machine 1M calls deep, constant or bounded JVM stack
 (`-Drontolisp.stack` unchanged as the escape).
- The byte cost on programs that make no value tail call: byte-identical emission.
- The interpreter, wasm and component legs unchanged (their behavior is already
 proper; only pin no regression).
- Time: the 3M-iteration loop of `.kb/scheme-frontend.md`'s measurements and the
 SICP corpus manifest unchanged (`SicpCorpusE2eTest`).

## Acceptance

`scheme-spec.yaml` case (`tail-call-through-a-value-jvm`) run on all four backends
with a depth the old JVM path overflows; `JvmLispCompilerTest` emission pin; the
deviations doc rewritten to state proper tail calls on every backend.
