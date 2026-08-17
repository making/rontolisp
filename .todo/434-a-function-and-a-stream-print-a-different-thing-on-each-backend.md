# 434. A function and a stream print a different thing on each backend

Difficulty: Medium

Found by the neighbour sweep `.todo/430` asked for ("whatever other value type
reaches the JVM's `toString` fallback or has no `_print_val` arm has both bugs
already"). The hash table was the one that TRAPPED; these two survive, so they
were left out of that pass, but they break the same governing rule: ordinary
printing must produce identical text on all four backends.

## 1. A function value loses its name when compiled

```lisp
(princ (princ-to-string #'car))
```

| | output |
| --- | --- |
| interpreter | `#<function CAR>` |
| JVM / WASM P1 / WASM component | `#<function>` |
| SBCL, for reference | `#<FUNCTION CAR>` |

The interpreter is the one closest to CL, so the cheap direction (degrade it to
`#<function>`) is the wrong one. A compiled function value is a function-table
index (`Object[]{Integer, env}` on the JVM, `TYPE_CLOSURE` on WASM) and carries
no name, so closing this means emitting a name table -- which the
`-Drontolisp.debug.functable` mapping already builds for the profiler, and which
the tree-shaker must be able to drop when nothing prints a function.

## 2. A stream prints its raw handle, differently everywhere

```lisp
(princ (princ-to-string (make-string-output-stream)))
```

| | output |
| --- | --- |
| interpreter | `3` |
| JVM | `0` |
| WASM Preview 1 | `-34640` |

Three different integers -- and the WASM one is an address, i.e. text that can
move for reasons unrelated to the program. The printers already HAVE a
`#<STREAM>` tag (`FuturePrint.streamStr` on the JVM, `emitPrintStream` on WASM);
it is only reachable for the async stream values, not for the integer handle a
file / string stream is. Either the handle grows a wrapper the printers can
recognize, or -- the `.kb/mutexes.md` precedent -- the handle is declared opaque
and unprintable, which is a decision, not a silent gap.

## Definition of done

Each item: one printed text, byte-identical on all four backends, pinned per
backend plus a `ci-spec.yaml` case, with the decision (name table vs opaque tag)
written into the matching `.kb` file so the next visitor can tell whether the
reason still holds. Nothing may print an identity hash or an address
(`.kb/emitted-output-determinism.md`).
