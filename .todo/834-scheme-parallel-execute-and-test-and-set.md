# Scheme: `parallel-execute` and an atomic `test-and-set!`

Difficulty: Medium

For `.todo/828`: the corpus's `variant=concurrent` files (19, `chapter3/section4`). 14 only
define things and pass today; 5 call `(parallel-execute thunk ...)` and 16 use
`test-and-set!` without defining it (one defines the book's non-atomic version).

```scheme
(define x 10)
(parallel-execute (lambda () (set! x (* x x)))
                  (lambda () (set! x (+ x 1))))
```

- `(parallel-execute p1 ... pk)`: run each thunk in its own thread and JOIN them all
  before returning, so a file-mode program's output is complete and deterministic in
  order of completion only where the program serializes. Onto `rontolisp:make-thread` /
  `join-thread` (`.kb/threads.md`): interpreter and JVM only.
- wasm has no threads (`.kb/threads.md`: the names SIGNAL at call time). Running the
  thunks sequentially is a legal interleaving and the only honest wasm answer -- but a
  serializer's busy-wait (`(if (test-and-set! cell) (the-mutex 'acquire))`) must not spin
  forever there; sequential execution never contends, so it terminates. State this in the
  guide rather than refusing the name.
- `(test-and-set! cell)`: atomically "if `(car cell)` is true answer true, else set it and
  answer false", over `.kb/mutexes.md`. A user `define` of it keeps winning.
- A closure shared by two threads assigns the same captured variable: check the JVM
  backend's boxed captures are safe to race on (lost updates are fine, that is the
  section's point; a crash or a torn box is not).
- The outputs are nondeterministic by design: the corpus test (`.todo/828`) asserts exit 0
  for these files, and `scheme-spec.yaml` gets a serialized case whose output IS fixed.
