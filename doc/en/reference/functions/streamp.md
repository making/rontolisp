# streamp

`(streamp object)`

Returns `t` if `object` is a stream and `nil` otherwise. A stream is a self-describing VALUE -- `open`, `make-string-input-stream`, `make-string-output-stream` and the socket constructors all answer one -- so an integer is NOT a stream, and a library that dispatches on "a file descriptor versus a Lisp stream" (`(etypecase s (integer ...) (stream ...))`) routes a rontolisp stream correctly. The standard-output designator `t` (what `*standard-output*` is bound to) counts as a stream too, and so do a [Gray stream](../../guides/gray-streams.md) instance -- any instance of a class descending from `rontolisp:fundamental-stream` -- and a synonym stream. The `stream` type specifier used by `check-type`/`typecase` is backed by the same test.

Every stream operator takes what this test answers `t` for, and signals a `type-error` for anything else, on every backend: `(write-char #\a 3)` reports `WRITE-CHAR: The value 3 is not of type STREAM`. nil designates the current standard stream where Common Lisp's operator says so, and for `read-byte`, `write-byte`, `read-sequence` and `write-sequence` as well. Binding or assigning `*standard-output*`, `*standard-input*`, `*error-output*`, `*trace-output*`, `*debug-io*`, `*query-io*` or `*terminal-io*` to a non-stream signals the same `type-error` at that point, naming the variable.

Its subtype names are exact, because the value carries the kind it was built with: `file-stream` is true of a stream `open` answered, `string-stream` of one the two string-stream constructors answered, and `synonym-stream` of a `make-synonym-stream` value. `readtable` is the type of the opaque `nil` token `*readtable*` holds. A stream PRINTS as `#<STREAM>` -- one text on every backend, with no backend-local handle or identity hash in it. Available on all backends except `--no-gc`.

```lisp
(with-output-to-string (s) (princ (streamp s) s)) ; => "T"
```
