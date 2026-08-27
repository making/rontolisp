# streamp

`(streamp object)`

Returns `t` if `object` is a stream and `nil` otherwise. Streams are opaque integer handles across all backends, so this is a lite test equivalent to `integerp`; the standard-output designator `t` (what `*standard-output*` is bound to) also counts as a stream. A [Gray stream](../../guides/gray-streams.md) instance -- any instance of a class descending from `rontolisp:fundamental-stream` -- counts too, as does a synonym stream, so a library that dispatches on "a file descriptor versus a Lisp stream" can be handed one and route it correctly. The `stream` type specifier used by `check-type`/`typecase` is backed by the same test. Its subtype names resolve too: `synonym-stream` has an exact test (a synonym stream is the one stream kind that is a value rather than a handle), while `file-stream` is lite in the same direction `streamp` is -- it is true of every handle stream, because nothing in a handle tells a file's from a string stream's. `readtable` is the type of the opaque `nil` token `*readtable*` holds. Available on all backends except `--no-gc`.

```lisp
(with-output-to-string (s) (princ (streamp s) s)) ; => "T"
```
