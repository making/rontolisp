# k45. A variable named `set` turns the eval runtime on: on wasm, a dispatcher of every callable

Difficulty: Medium

Found 2026-11-20 with k33, measuring where the bytes of a YAVI program go
(`.kb/java-frontend.md`, "YAVI compiled to wasm").

```lisp
(defun a (c set) (list c set))
(print (a #\a "abc"))
(print (funcall (lambda (c) c) #\a))
```

`-o a.wasm` is 3,759 B with `_dispatch_spread` and `_apply` in it; with the parameter
named `map` it is 3,227 B without them (`-Drontolisp.wasm.debug-func-sizes`).

- **Cause**: `WasmLispCompiler`'s `usesEval` is true for
  `programUsesSymbol(program, LispNames.SET)` (and `SYMBOL_VALUE`, `FBOUNDP`), and
  `programUsesSymbol` answers for any list element at any depth (`SymbolCensus`), so a
  variable read counts as a call of the function `set`. `usesEval` makes
  `usesApplyRuntime`, whose spread dispatcher has one case per callable.
  `JvmLispCompiler` asks the same question; what it costs a class is not measured.
- **Where it costs**: `eval/uri.lisp` names a parameter `set` (`%uri-char-p`,
  `%uri-scan`), so every Java program that reaches `java.net.URI` pays: 3,165 B of a
  program calling `URI.create`, 53,761 B of `examples/java/yavi/Booking.java`
  (1,280,340 B), where YAVI's reader of an untyped input names `URI`, beside `_eval`
  (3,133 B), a larger top level and more data. The `URI` entry costs a schema of a string
  and an integer 108,815 B, 29,553 of them the code of `java.net` and the URI runtime
  (`.todo/artefacts/k33-yavi-compiles-to-wasm-the-milestone-and-what-stands-before-it/README.md`).
- **What to do**: the gate asks whether `set` is CALLED or taken as a function (`#'set`,
  a quoted designator), on both backends and for the three names. That is a walk that
  knows a binding: `(let ((set 1)) ..)` has the name at a car too. The failing test
  first: the wasm of the program above carries no spread dispatcher (the bytes of the
  `map` spelling), and `(set 'x 1)`, `(funcall #'set 'x 1)` still answer on the four
  backends.
- Renaming the parameter in `uri.lisp` gives a Java program its bytes at once and leaves
  the gate as it is. Measure `Booking.java` before and after either change and write the
  numbers into "YAVI compiled to wasm" and `examples/java/README.md`.
