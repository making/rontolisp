# d65. `write-char`, `write-byte` and `unread-char` have no function value

Difficulty: Medium

CL defines all three as functions; here `#'write-char` fails everywhere and the other two
on the compile paths (measured on all four backends):

| form | interpreter | JVM / P1 / component |
|---|---|---|
| `(funcall #'write-char #\x s)` | "WRITE-CHAR is a macro or special operator, not a function" | "Cannot compile: WRITE-CHAR" |
| `(funcall #'write-byte 7 s)` | works | "Cannot compile: WRITE-BYTE" |
| `(funcall #'unread-char c s)` | works | signals (`LispMacroExpander.UNREAD_CHAR_NOT_A_VALUE_MESSAGE`) |

The other stream operators reach a Gray instance as function values through
`BuiltinFunctionWrappers.GRAY_WRAPPERS` (`.kb/gray-streams.md`, "Function values"); these
three need a catalog wrapper first (`.kb/adding-primitives.md`), then a Gray twin
(`%gray-write-char-dispatch`, `%gray-write-byte-dispatch`, `%gray-unread-char-dispatch`).
`unread-char`'s handle-side pushback is spliced by `UnreadCharLibrary` from call sites
only, which is why its value signals today.
