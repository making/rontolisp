# i40. `find-all-symbols` misses a name the program only quotes on the compile paths

Difficulty: Low

`(print (find-all-symbols 'ci-only-here))`, measured 2026-11-05 on b2d493ad7 and SBCL:

| Backend | Output |
|---|---|
| Interpreter, SBCL | `(CI-ONLY-HERE)` |
| JVM, WASM preview 1, WASM component | `NIL` |

The compiled name's symbol exists, so the enumeration universe (`.kb/packages.md`, "The
enumeration universe") leaves out a name read only as quoted data. Check `find-symbol`,
`do-symbols`, `apropos-list` and `do-all-symbols` for the same gap. Holding every quoted name
in the baked rows has a size cost, so weigh narrowing it to programs that enumerate.
