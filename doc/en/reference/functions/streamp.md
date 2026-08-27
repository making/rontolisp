# streamp

`(streamp object)`

Returns `t` if `object` is a stream and `nil` otherwise. A stream is a self-describing VALUE -- `open`, `make-string-input-stream`, `make-string-output-stream` and the socket constructors all answer one -- so an integer is NOT a stream, and a library that dispatches on "a file descriptor versus a Lisp stream" (`(etypecase s (integer ...) (stream ...))`) routes a rontolisp stream correctly. The standard-output designator `t` (what `*standard-output*` is bound to) counts as a stream too, and so do a [Gray stream](../../guides/gray-streams.md) instance -- any instance of a class descending from `rontolisp:fundamental-stream` -- and a synonym stream. The `stream` type specifier used by `check-type`/`typecase` is backed by the same test.

Its subtype names are exact, because the value carries the kind it was built with: `file-stream` is true of a stream `open` answered, `string-stream` of one the two string-stream constructors answered, and `synonym-stream` of a `make-synonym-stream` value. `readtable` is the type of the opaque `nil` token `*readtable*` holds. A stream PRINTS as `#<STREAM :HANDLE n :KIND :FILE>`, where the handle number is backend-local. Available on all backends except `--no-gc`.

```lisp
(with-output-to-string (s) (princ (streamp s) s)) ; => "T"
```
