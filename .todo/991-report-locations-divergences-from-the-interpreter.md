# `--report-locations` still differs from the interpreter's lines in four shapes

Difficulty: High

A wasm-GC module compiled with `--report-locations=line` is meant to print the interpreter's
location lines byte for byte (`.kb/error-handling.md`, "Location lines on wasm-GC", whose "Known
divergences" bullet lists these). Measured 2026-09-26 over 82 programs of the JVM parity corpus,
each with `(print (ignore-errors nil))` appended for EH mode (wasmtime 49); the JVM backend matches
the interpreter in every one of them:

1. **A tail call through a function value into a function that is no frame.** The `return_call`
   leaves the caller's try_table, so the location falls back to the line that called the caller:

   ```lisp
   (defun run (f x)
     (funcall f x))
   (print (ignore-errors nil))
   (run #'parse-integer "x")
   ```

   interpreter `at f.lisp:2 in RUN`, wasm `at f.lisp:4`.
2. **A `handler-bind` handler's own form.** The handler's `(error "handler failed")` on line 4 of

   ```lisp
   (defun k ()
     (handler-bind ((error (lambda (c)
                             (declare (ignore c))
                             (error "handler failed"))))
       (error "first")))
   ```

   is `at :4 in K` on the interpreter, `at :2 in K` (the `handler-bind` line) on wasm.
3. **Fused and inlined arithmetic.** `(sq a)` inlined into F (`(defun sq (x) (* x x))`) reports
   `:5 in F` for the interpreter's `:2 in SQ`; a tree spanning lines, `(+ (* a 2)\n (* b 3))`,
   reports its first line for the failing `(* b 3)`; a raw local's `(setq acc\n (+ acc\n step))`
   in a `dotimes` reports one line early. The JVM backend's `_fx$N` answer is
   `.kb/jvm-int-fusion.md`'s per-site fallback marks.
4. **Preview 1 async** (`--component` matches the interpreter in both): the body runs at its call,
   so the hop's await site is the CALL's line when the `await` is on another one
   (`(let ((f (job)))\n (print :between)\n (rontolisp:await f))` says the `let` line); and an
   async body whose tail call enters another frame, `(rontolisp:async-defun job (x) (helper x))`,
   leaves before its hop is noted, so the `in JOB (async), awaited at ...` line is missing.

Goal: each shape prints the interpreter's lines. Constraints: the constant-stack tail calls of
`.kb/wasm-tail-calls.md` stay (1 cannot be a plain `call` through every function value -- a named
`let` overflowed when frames disabled tail calls), and a build without the option stays
byte-identical.

Read first: `.kb/error-handling.md` ("Which function", "Location lines on wasm-GC"),
`codegen/wasm/WasmUncaughtLocations`, `cli/WasmReportLocationsTest`.
