# truename

`(truename pathname)`

Returns the pathname when the file exists and signals a `file-error` when it does not.
The signal is the point: `(ignore-errors (truename path))` is the Common Lisp
idiom for "this path if it is there, `nil` otherwise", and libraries use it to
probe for an optional file or directory.

The value on success is the path with every symbolic link in it resolved and
every `.` and `..` taken out, a `..` after a link counting from where the link
leads: with `d` a link to `x/y`, `(truename "d/../f.txt")` is `#P"x/f.txt"`.
Nothing is made absolute -- a relative path answers a path relative to the working
directory, which a WASM module cannot name -- and a trailing `/` is kept. When you
want an existence answer without the condition, use [`probe-file`](probe-file.md),
which returns `nil` instead of signalling and answers the path as given.

On both WASM backends a link whose target is absolute is not followed (the WASI host
refuses it), so such a path is not there.

```lisp
(ignore-errors (truename "definitely-missing.txt"))   ; => NIL
```

## Backend support

Works on all four backends: one definition in rontolisp source over the per-backend
read of a single link (both WASM backends through the `path_readlink` WASI import,
`--component` through `wasi:filesystem`'s `readlink-at`), spliced into the program
when it is referenced.
