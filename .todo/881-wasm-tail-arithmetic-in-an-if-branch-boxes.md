# wasm: an arithmetic tail in an `if` branch runs 40% slower than the same tail let-bound

Difficulty: Medium

Measured 2026-09-19 (`.kb/multiple-values.md`, "A tail settles the channel", cost)
while settling function tails for the multiple-value channel. `bench-report/programs/
fib.lisp` run as 20 x fib 30 (32M calls) on wasmtime 47:

- `(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))`: 676-709 ms.
- The same defun with the `+` tail rewritten to
  `(let ((v (+ (fib (- n 1)) (fib (- n 2))))) (setq %mv-spill nil) v)` -- what
  `LispMacroExpander.settleMvTail` emits in a program that uses a multiple-value operator:
  419-425 ms, i.e. the extra global store made it FASTER.

The JVM shows no such difference (167-209 ms either way, noise). So the wasm-GC backend
takes a slower path for a bare `(+ ...)` in an `if` branch's tail than for a `let`-bound
integer expression returned as a variable -- presumably the unboxed-local / int-fusion
machinery (`.kb/wasm-int-fusion.md`, `.kb/wasm-unboxed-locals.md`) recognizes the
let-bound shape and boxes the bare one twice, or the `if` result type differs. Find the
shape the bare tail compiles to, and make it take the fast path without the `let`: every
recursive numeric function in `bench-report/programs/` has this tail.
