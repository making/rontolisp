# streamp

`(streamp object)`

Returns `t` if `object` is a stream and `nil` otherwise. Streams are opaque integer handles across all backends, so this is a lite test equivalent to `integerp`; the standard-output designator `t` (what `*standard-output*` is bound to) also counts as a stream. The `stream` type specifier used by `check-type`/`typecase` is backed by the same test. Available on all backends except `--no-gc`.

```lisp
(with-output-to-string (s) (princ (streamp s) s)) ; => "T"
```
