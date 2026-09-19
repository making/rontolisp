# Interpreter: a lexical `go` without a thrown signal

Difficulty: Medium

Follow-up of `.todo/897`. On the interpreter every `(go L)` throws a `GoSignal` that
`evalTagbody` catches: a count-down step through `go` costs 1.75x a step through a call
(2026-09-19, 300 x 3,000 steps: 1.25-1.27 s against 0.71-0.73 s). Every Scheme loop pays it
(named `let`, `do`, self tail calls), and the Scheme tail-call groups pay it on every entry
(a shallow two-member group entered 10M times: 37.8 -> 73.8 s; the metacircular evaluator
+16%).

- A `go` in tail position of a tagbody statement (through `if`/`progn`/`let` tails) could
  answer a sentinel instead of throwing, which `evalTagbody` tests.
- Memoizing the label table per `tagbody` form alone (tried in `.todo/897`) bought nothing
  measurable: the throw is the cost, not the table.
- A dynamic `go` (from a closure) must keep working; pinned by the `tagbody` tests named in
  `.kb/do-return-block.md`.
