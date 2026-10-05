# d36. A nil `:start` to `write-line` / `write-string` on a Gray stream reads as omitted

Difficulty: Low

SBCL signals a `type-error` for an explicit `:start nil` whatever the stream, before any Gray
method runs (`stream-write-string` is never called); a nil `:end` is the length:

| call on a Gray character output stream `o` | SBCL | rontolisp |
|---|---|---|
| `(write-line "hello" o :start nil :end 3)` | `type-error` | writes `hel` |
| `(write-string "hello" o :start nil :end 3)` | `type-error` | check |
| `(write-line "hello" o :end nil)` | writes `hello`, method sees start `0` end `5` | check |

Cause: `rontolisp::%gray-write-line-dispatch` (`eval/gray.lisp`) has `start-p` but sends any
nil `start` to a Gray instance down the omitted arm. Use `start-p` there too (refuse a given
nil, keep the user method's default for an omitted one), and check the `write-string` dispatch
the same way. Model: the non-Gray arm and `.kb/sequence-bounding-keywords.md`, "A nil `:start`
is no bound". Extend the existing nil-`:start` fixture with the Gray cases on all four
backends; the native CiSpec run (whole corpus as one program) is what exercises the Gray
dispatch.
