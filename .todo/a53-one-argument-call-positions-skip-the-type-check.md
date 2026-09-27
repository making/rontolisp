# One-argument arithmetic and char calls: the type check differs per backend

Difficulty: Medium

Measured 2026-09-26 (`(print (handler-case FORM (error (e) (type-of e))))`, native
binary):

| form | interpreter | JVM | wasm |
|---|---|---|---|
| `(+ 'a)`, `(* 'a)`, `(logand 'a)`, `(logior 'a)` | `TYPE-ERROR` | `A` | `A` |
| `(char= 1)`, `(char< 1)` | `T` | `TYPE-ERROR` | uncatchable trap |
| `(< 'a)`, `(= 'a)`, `(min 'a)`, `(max 'a)` | `T` / `A` | same | same |

CL requires a type error in every row. The call-position one-argument lowerings are
where the backends part: the arithmetic ones drop the check, `char=` checks on the JVM and
traps on wasm, and the interpreter's comparisons and `min`/`max` do not check at all. The
function VALUES follow their call position (`BuiltinFunctionWrappers.comparison`'s
one-argument arm is `(op a)`); the arithmetic and bitwise ones fold the identity in and
signal on every backend.

Make one text answer everywhere -- the CL type error -- in the interpreter's built-ins and
both lowerings, and pin the table in ci-spec.
