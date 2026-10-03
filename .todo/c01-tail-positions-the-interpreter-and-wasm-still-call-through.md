# c01. Three tail positions the interpreter and wasm still call through

Difficulty: Medium

Measured 2026-10-03 (exec jar with the JVM self-tail jump, wasmtime 47), each 1,000,000
deep; the compiled JVM answers all three (`.kb/jvm-self-tail-calls.md`):

- Interpreter: the value of a `return-from`/`return` is no tail position.
  `(defun f (n) (if (= n 0) :done (return-from f (f (- n 1)))))` and
  `(defun g (n) (block nil (if (= n 0) (return :d) (return (g (- n 1))))))` end with
  `error: stack overflow`; wasm and the component answer (`BlockMarker.tail`).
- wasm and the component: a self call inside the body of a `labels`/`flet` form that is
  itself in tail position, `(defun f (n) (labels ((d (x) (- x 1))) (if (= n 0) :done
  (f (d n)))))`, traps (`call stack exhausted`): `WasmExprCompiler`'s `FLET`/`LABELS` arms
  compile the expansion without re-arming `Ctx.tailPosition`, unlike `let*`/`the`/`locally`.
- wasm and the component: a Clojure multi-arity `fn` clause's `recur`,
  `((fn m ([n] (if (zero? n) :ok (recur (dec n)))) ([a b] (+ a b))) 1000000)`, traps,
  while a Common Lisp transcription of its lowered IR (a `&rest` labels lambda dispatching
  on `length`) answers -- cause not identified. A plain Clojure call through a value traps
  the same way, `(let [f (atom nil)] (reset! f (fn [m] (if (= m 0) :done (@f (dec m)))))
  (@f 1000000))`, where the interpreter and the JVM answer (measured the same day): every
  such call goes through `%clojure-call`'s `apply`.

## Plan

- Interpreter: when a `return-from`/`return` targets a block the frame owns
  (`.kb/interpreter-tail-calls.md`, `owner`), continue the frame with the value form instead
  of evaluating it and throwing `BlockReturnSignal`.
- wasm: re-arm the flag in the `FLET`/`LABELS` arms; measure the bytes on size-report and
  the examples (a `call` becomes a `return_call` wherever such a form is a tail).
- Find what differs between the Clojure `fn` program and its transcription (`%clojure-call`
  over `apply`, the int fusion of the dispatch test, the inliner's tail rules) before fixing.

## Pin

- `ci-spec.yaml` (all four backends): the three shapes above at a depth the old emission
  overflowed; `clojure-spec.yaml`: the multi-arity `fn` `recur` beside the
  `deep-recur-answers-on-every-backend` case.
