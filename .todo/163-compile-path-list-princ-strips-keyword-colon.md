# Compile-path `princ` on a keyword INSIDE A LIST strips the leading colon

Discovered while writing todo-161's cross-backend `read-char` tests. The
interpreter, the JVM compile path and both WASM backends agree on the printed
form of a keyword AT TOP LEVEL:

```
(princ :eof)   ; -> ":EOF" everywhere
```

But when the same keyword rides inside a list (or any cons) that is `princ`ed,
the two compile paths drop its leading colon while the interpreter keeps it:

```
(princ (list :eof))    ; interpreter: "(:EOF)"    JVM/WASM: "(EOF)"
(princ (list 1 :eof))  ; interpreter: "(1 :EOF)"  JVM/WASM: "(1 EOF)"
```

CL's `princ` prints a keyword as its bare symbol name (no colon) but its
usual list-printing recursion goes through `prin1` on the elements, which
DOES include the colon. Something in the compile-path list printer is picking
the wrong flavour on the recursive element step.

## Where the divergence lives

- Interpreter: `Environment.printString` / `displayString` chain (they go through
  `LispVal.print()` on elements, which prints the keyword with its colon).
- JVM: the compile-path `_princ` helper (in `JvmPrintCompiler` / the
  runtime it emits) walks list elements through its own display path.
- WASM: `emitPrintList` / whatever the equivalent is in `WasmPrintCompiler` -
  same shape.

The divergence is entirely on the ELEMENT step; the top-level princ of the
same keyword prints correctly on all four.

## Plan

- Locate the compile-path list-print element step (JVM + WASM). Have it use the
  print / prin1 path (colon retained) instead of the display path (colon
  stripped) when the element is a keyword symbol.
- Verify: add a cross-backend ci-spec case
  `(princ (list :one :two))`  -> `(:ONE :TWO)` everywhere.
- Should also unblock using `:eof` as an eof-value in cross-backend read-char
  / read tests. Right now such tests need a plain symbol as a sidestep (see
  `readCharDecodesSupplementaryCodePointFromStringStream` on WASM, and
  `readCharCombinesSurrogatePairsOnSupplementaryCodePoint` on JVM).

## Non-goals

- The top-level `(princ :keyword)` form is already correct on every backend;
  no change there.
- The `prin1` path is likely already consistent -- check but do not touch
  unless a real divergence surfaces.
