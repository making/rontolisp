# j82: the measurements behind the entry and the catch (2026-11-17)

Linux x86-64, GraalVM JDK 25.0.5, wasmtime 49; `bench.sh <jar> <file>` prints the best of
three (JVM / precompiled wasm seconds) and the bytes. The numbers are in
`.kb/java-frontend.md`, "Exceptions".

- `programs/ArrayLoop.java`, `ListWalk.java`, `DivLoop.java`: what a test at the raising site
  costs. Each was lowered with `--dump-ir`, the IR edited by hand and compiled as `.lisp`:
  the entry replaced by `(unwind-protect (main) (finish-output))` (EH mode, nothing held),
  then `(if (if (< i 0) t (>= i (length a))) (raise))` ahead of every `aref` / `%aset`,
  `(if p p (raise))` at every field read, `(if (eql d 0) (raise))` ahead of `truncate` / `rem`.
- `programs/TryLoop.java`, `OneTry.java`, `NoTry.java`: a `try` in a loop. `TryLoop`'s IR was
  edited to a `handler-bind` whose handler leaves by `return-from` for a class it takes, and
  to `handler-case` clauses of type `(satisfies test)`, the shape kept.
- `programs/CharLoop.java`: the `charAt` loop EH mode slows on wasm.
