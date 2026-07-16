# rontolisp:async-defun

`(rontolisp:async-defun name (params...) body...)`

Defines an asynchronous function. The surface is the same as [`defun`](defun.md) — the full lambda-list keywords (`&optional`, `&rest`, `&key`, ...) are supported — but calling the function starts the body immediately and returns a *future* instead of a value: the body runs until its first [`rontolisp:await`](rontolisp-await.md) of an unsettled future (or until completion), then the caller resumes ("eager start"). The future settles with the value of the last body form, or with the error the body signaled (re-signaled when the future is awaited).

```lisp
(rontolisp:async-defun add-later (a b)
  (+ a b))
(rontolisp:await (add-later 20 22))   ; => 42
```

The call itself yields an opaque future ([`rontolisp:futurep`](../functions/rontolisp-futurep.md) recognizes it, and it prints as `#<FUTURE>`):

```lisp
(add-later 1 2)   ; => #<FUTURE>
```

An error signaled by the body does not escape at call time; it settles the future and re-signals at the `await` — see [`rontolisp:await`](rontolisp-await.md) for catching it with `handler-case`. The anonymous counterpart is [`rontolisp:async-lambda`](rontolisp-async-lambda.md).

## Backend support

- **Interpreter / JVM**: the body runs on a virtual thread — real parallelism with the caller after the first suspension.
- **WASM `--component`**: the body compiles into a state machine; an `await` of a pending future genuinely suspends it, and the component's event loop resumes it when the awaited host operation (e.g. a `fetch` response) completes. The tasks of one component instance are cooperative (single-threaded). An asynchronous component needs `wasmtime -W exceptions=y` on top of `-W gc=y`.
- **Preview 1 WASM**: the body runs to completion immediately (no asynchronous host I/O exists there).
- **`--no-gc`**: rejected at compile time.
