# Three uncaught reports whose one line differs between backends

Difficulty: Medium

`Unhandled condition: <report>` is meant to be the same line on all four backends
(`.kb/error-handling.md`, "An uncaught condition reports ONE line"). The 82-program corpus built for
the location lines (2026-09-26) found three where it is not; their location lines agree:

| program | interpreter | differs |
| --- | --- | --- |
| `(defstruct point x y)` `(point-x 42)` | `%OBJ-REF expects an instance, got 42` | JVM: `class java.lang.Long cannot be cast to class [Ljava.lang.Object; ...` |
| `(dotimes (i n) (setf s (+ s (aref v i))))` over a 3-element `double-float` array, `n` 5 | `aref: index out of bounds` | JVM: `Index 5 out of bounds for length 5` (the typed loop's raw `DALOAD`) |
| `(error 'type-error :datum 1 :expected-type 'string)` | `Condition (TYPE-ERROR :DATUM 1 :EXPECTED-TYPE STRING) was signalled.` | wasm-GC P1 and `--component`: the report is empty |

The first two are raw Java exceptions reaching the report's message; the interpreter's own text is
not CL's either (`point-x` on a non-point is a `type-error` naming the structure type). The third
is a report the wasm renderer answers with nothing.

Goal: one report line per program on every backend, pinned in `ci-spec.yaml`'s `standalone:`
section, deciding for the first two what the CL text is before copying the interpreter's.

Read first: `.kb/error-handling.md` ("An uncaught condition reports ONE line", "A built-in error
carries its CONDITION CLASS", "A wrong-type argument names its operator"), `.kb/jvm-typed-loops.md`,
`.kb/defstruct.md`.
