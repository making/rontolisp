# b95. On the JVM a self tail call keeps a frame, so `recur` and per-element verbs overflow

Difficulty: High

Measured 2026-10-03, exec jar at `2ea187782`, compiled output run as `java Prog`:

- Clojure `(loop [n 1000000] (if (= n 0) :done (recur (- n 1))))`: `StackOverflowError`
  (100,000 passes). The oracle's `recur` is a jump, so any count runs; the interpreter
  and both wasm backends answer `:done`.
- Common Lisp `(defun f (n) (if (= n 0) :done (f (- n 1))))` and the same `labels`
  loop at 1,000,000: `StackOverflowError`.
- The Clojure lowering builds per-element iteration as `labels` self calls (`range`,
  `distinct`, `keep-indexed`, `map-indexed`, `every?`, `some`, `take-while`,
  `drop-while`, `zipmap`, `partition`, `interleave`): `(count (distinct (repeat 100000
  1)))` and `(count (range 200000))` overflow on the JVM only.
- `.kb/wasm-tail-calls.md` records the JVM position ("compiled JVM output stays bounded
  by its 16 MiB worker"); b69 trampolines only tail calls through a value.

## Plan

- Emit a self tail call -- a call in tail position to the enclosing `defun`/`labels`
  function with a fixed arity -- as a jump to the method's start (arguments into the
  parameter slots, then `goto`), so every loop written as tail recursion runs in
  constant stack on the JVM like on the other three backends. Mind the OSR rule
  (`.kb/jvm-osr-backedges.md`: no backward branch onto a non-empty operand stack) and
  the method-size limits.
- Re-measure the depth table in `.kb/wasm-tail-calls.md` and the JVM timings of the
  affected benchmarks.

## Pin

- `clojure-spec.yaml` (all four backends): a 1,000,000-deep `loop`/`recur`, and
  `distinct`/`range` over 200,000 elements.
- A `ci-spec.yaml` or JVM compiler test: a 1,000,000-deep self tail call through a
  `defun` and a `labels` function.
