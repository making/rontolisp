# i66. `fresh-line` on a stream with no column

Difficulty: High

Measured 2026-11-05 on a program writing `1`, `2\n`, `3` and so on to `*error-output*` with a `(fresh-line *error-output*)`
between the writes. SBCL 2.2.9 starts a line only past column 0; all four backends always write a newline, on a file, a
socket and standard error (`.kb/standard-output-redirect.md`, "Per backend"). Only `t` and a string stream look.

| form | SBCL 2.2.9 | all four backends |
| --- | --- | --- |
| `(fresh-line *error-output*)` at the start of a line | nothing | a newline |
| `(format *error-output* "~&x")` past column 0 | a newline, then `x` | `x` |
| `(format file "~&x")` past column 0 | a newline, then `x` | `x` |

The `format` rows are deliberate: an opening `~&` asks only a destination with a column
(`LispMacroExpander.freshLineWhereTheColumnIsKnown`, `.kb/format.md`), since asking `fresh-line` opened every
`(format *error-output* "~&x~%")` with a blank line. With a column on the stream that guard goes, along with gray.lisp's
`%gray-format-fresh-line` and the arm in `%format-write`.

The work is a last-byte or column per stream: the interpreter's writers, the JVM's `_writeStr(String, Object)` and `_streams`,
and WASM's `_write_stream_str` fd path with `_fresh_line_stream`, which today read a string stream's record only. Every
write path updates it. `.kb/pretty-printer.md`, "What a stream with no column cannot do", names what else it opens.
