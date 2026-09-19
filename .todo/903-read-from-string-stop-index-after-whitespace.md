# read-from-string's stop index does not count the terminating whitespace

Difficulty: Low

Found by `.todo/895` (2026-09-19):

| form | interpreter | JVM / wasm / component | SBCL |
|---|---|---|---|
| `(multiple-value-list (read-from-string "abc def"))` | `(ABC 4)` | `(ABC 3)` | `(ABC 4)` |
| `(multiple-value-list (read-from-string "(a) b"))` | `((A) 3)` | `((A) 3)` | `((A) 4)` |

SBCL's `read` (not `read-preserving-whitespace`) consumes one whitespace character after the
object, a symbol or a list alike. The compile paths do not do it after a token, and no path
does it after a list. See `.kb/read-load-streams.md` for where each path computes the index.

## Test plan

- ci-spec case with both rows plus a trailing-newline and an end-of-string case.
