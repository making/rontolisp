# g81. `%STREAM-TARGET is undefined` warning, and a local named `close` breaking a component

Difficulty: Medium

Two compile-path faults seen on 2026-10-24 while extending tests that take every standard
function as a value (the shape `BuiltinFunctionWrapperCatalogTest` builds). Neither has a
minimal reproduction yet; finding one is the first step.

1. **`%STREAM-TARGET is undefined`.** A program that only takes `#'make-two-way-stream` and
   similar stream constructors as values (no call) prints a compile warning that
   `%STREAM-TARGET` is undefined. Seen with the 2026-10-21 release binary too, so it predates
   that day's changes. A small program listing the stream constructors and accessors as
   `#'...` values compiled without the warning to `.class`, `.wasm` and `--component`, so the
   trigger is something the large all-functions program adds (probably which library defuns
   the tree shaker keeps).
2. **A local variable named `close`.** Inside the large `--component` program that takes every
   standard function as a value, a `let` binding named `close` made compilation fail. The same
   binding in a small program compiled. The test was changed to use another name, so the
   failure is no longer exercised.

Expected: no warning for (1); (2) compiles, since a lexical variable named like a function is
legal Common Lisp and must not interact with function references.

## Done when

- Each fault has a minimal reproduction, then a fix on every backend it affects.
- The covering assertion extends an existing test (the wrapper catalog or the tree-shaker
  corpus) rather than adding a new one.
