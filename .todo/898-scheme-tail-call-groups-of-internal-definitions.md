# Scheme: tail-call groups among internal definitions

Difficulty: Medium

Follow-up of `.todo/897`. Top-level procedures whose tail calls cycle are one `defun`
(`.kb/scheme-frontend.md`, "Tail-call groups"); internal definitions (`letrec*` in a body)
and `letrec` bindings that call each other in tail position are still ordinary calls and
overflow like any non-loop tail call (a procedure value's depth: JVM ~1,700, wasm ~2,700,
interpreter ~15,000).

- Measure how often the SICP corpus (`.todo/828`) has such a cycle among internal
  definitions before building anything.
- The same probe (`declareGroups` / `lowerGroup`) could apply to a body whose defined names
  are never assigned and bound to syntactic lambdas; the group would be a local lambda,
  so a name used as a value needs the escape rule of a named `let` (`LoopName.escaped`).
- Byte-identical where no such cycle exists; measure the shape on all four backends.
