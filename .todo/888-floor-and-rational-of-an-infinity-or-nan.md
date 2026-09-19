# `floor` / `ffloor` / `rational` of an infinity or a NaN

Difficulty: Medium

Found by `.todo/886` (2026-09-19), Common Lisp level, every backend:

| form | interpreter / JVM / wasm | SBCL |
|---|---|---|
| `(floor inf)`, `(round inf)`, ... | `9223372036854775807` (clamped to a long) | `simple-error` |
| `(ffloor inf)` | `9.223372036854776e18` | `floating-point-invalid-operation` |
| `(fround nan)` | `0.0` | `simple-error` |
| `(rational inf)` | interpreter / JVM: error "rational of a non-finite float is undefined"; wasm: `unreachable` trap, no message | `simple-error` |

`inf` is `(- (log 0))`. The Scheme front end no longer reaches any of these (its
`floor`/`exact` helpers test first, `.kb/scheme-frontend.md`), but a Common Lisp program
does. Every one should signal, with one message on every backend.

## Test plan

- `ci-spec.yaml` cases (error message on every backend for the signalling ones).
- `.kb/jvm-double-arithmetic.md` or the numeric `.kb` file that owns `floor`.
