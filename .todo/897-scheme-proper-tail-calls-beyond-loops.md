# Scheme: proper tail calls beyond loops

Difficulty: High

Split off from `.todo/826` ("Not a language feature, same area"). Self tail calls and
named `let` already lower to loops (`.kb/scheme-frontend.md`, "Destination-driven
lowering"); mutual tail calls and tail calls through a procedure variable are ordinary
calls. `ev?`/`od?` overflows at 5,000 on the JVM (`java Prog`) and at 100,000 on the
interpreter and both wasm targets. A trampoline would tax every call.

Measurement first:

- Current depth limits per backend (interpreter, JVM, wasm preview1, wasm component) for
  mutual tail recursion and for a tail call through a procedure variable.
- What each backend could offer: wasm `return_call`, JVM options, interpreter trampolining
  at tail position, a Scheme-front-end-only lowering (a strongly connected set of
  top-level tail-mutually-recursive procedures grouped into one loop).
- Whatever is chosen must not tax programs that do not need it: byte-identical output
  where unaffected, no per-call slowdown on the hot paths (bench-report programs).
- If no change is worth its blast radius, record the numbers and the date in `.kb/` and
  the Scheme deviations doc, and close on the measurement.
