# c59. wasm-GC: division by zero traps instead of signaling `division-by-zero`

Difficulty: Medium

Measured 2026-10-03: `(print (handler-case (/ 1 0) (error (e) (princ-to-string e))))` prints
`"Division by zero"` on the interpreter and the JVM; wasm and the component trap
(`wasm trap: wasm 'unreachable' instruction executed`), so no `handler-case` (nor a Clojure
`catch`) sees it. `.kb/error-handling.md` lists it under "What still traps on wasm-GC".
Every integer/ratio division path (`/`, `floor`/`truncate`/`mod`/`rem` family) needs the
`division-by-zero` condition the other backends signal, in EH mode, byte-identical report.
Pin in ci-spec (all four backends) and in clojure-spec (`(try (/ 1 0) (catch ...))`, the
oracle's most common caught runtime error).
