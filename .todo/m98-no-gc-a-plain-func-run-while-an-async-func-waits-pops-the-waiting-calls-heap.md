# m98. `--no-gc --component`: a plain func run while an async func waits pops the waiting call's heap

Difficulty: Medium

A host runs a sync-typed export of an instance while an async-typed export's task waits
(`.kb/wasi-component.md`, "`:async t` I/O exports", "Which calls of one instance overlap").
The scalar backend's post-return pops the bump heap to its BASE
(`NoGcWasmCompiler.postReturnBody`: "nothing outlives one export call"), so the call made
meanwhile frees what the waiting call holds, and what the host lowers next lands on it.

Measured 2026-11-27, wasmtime 51 (`wasmtime run` of a composition, and a `bindgen!` host with
`run_concurrent`): the answer of the waiting call comes back broken ("invalid utf8 encoding",
from the string's 9th byte). The same three programs with the waiter compiled on wasm-GC
answer `10` and `NIL`.

```console
// waiting.wit
package example:waiting@0.1.0;

interface host {
  slow-echo: async func(s: string, ms: u32) -> string;
}

interface api {
  later: async func(s: string, ms: u32) -> string;
  size: func(s: string) -> u32;
}

world provider {
  export host;
}

world waiter {
  import host;
  export api;
}
```

```lisp
;;; provider.lisp (--component): answers 2,800 characters after ms
(defun slow-echo (s ms)
  (sleep (/ ms 1000.0))
  (let ((out (make-string-output-stream)))
    (dotimes (i 400) (format out "~a~4,'0d|" s i))
    (get-output-stream-string out)))
(rontolisp:wit-export "waiting.wit" :world provider)

;;; waiter.lisp (--no-gc --component)
(rontolisp:wit-import "waiting.wit" :interface "example:waiting/host@0.1.0" :package host :blocking t)
(defun later (s ms) (host:slow-echo s ms))
(defun size (s) (length s))
(rontolisp:wit-export "waiting.wit" :world waiter)

;;; caller.lisp (--component): the plain func is called while the async one waits
(rontolisp:wit-import "waiting.wit" :interface "example:waiting/api@0.1.0" :package api)
(defun repeated (s)
  (let ((out (make-string-output-stream)))
    (dotimes (i 400) (format out "~a~4,'0d|" s i))
    (get-output-stream-string out)))
(let ((pending (api:later "ab" 100)))
  (print (api:size "0123456789"))
  (print (mismatch (rontolisp:await pending) (repeated "ab"))))
```

`wasm-tools compose waiter.wasm -d provider.wasm -o waiting.wasm`, then `wasm-tools compose
caller.wasm -d waiting.wasm -o called.wasm`, `wasmtime run called.wasm`.

- The failing test first: `WitOracleE2eTest.aComponentCallsWhatAnotherExportsOverCompoundTypes`
  ends with this scenario for the wasm-GC waiter (a record there); add the scalar waiter of the
  flat world above.
- The only place a `--no-gc` task waits is a `:blocking t` import's call. A fix that costs a
  program without one nothing: a floor the post-return pops to instead of the base, raised to
  the heap top around a blocking import's call and put back after it. The wasm-GC side holds
  its call's mark in locals (`WasmExportCompiler.waitsOverMemory`).
- A printing program's exports are all async-typed, so its calls never overlap.
