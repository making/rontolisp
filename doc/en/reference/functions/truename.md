# truename

`(truename pathname)`

Returns the pathname when the file exists and signals a `file-error` when it does not.
The signal is the point: `(ignore-errors (truename path))` is the Common Lisp
idiom for "this path if it is there, `nil` otherwise", and libraries use it to
probe for an optional file or directory.

The value on success is the absolute path with every symbolic link in it resolved
and every `.` and `..` taken out, a `..` after a link counting from where the link
leads. A relative path is merged with `*default-pathname-defaults*` and placed below
the working directory first, as SBCL's is: run from `/w`, with `d` a link to `x/y`,
`(truename "d/../f.txt")` is `#P"/w/x/f.txt"`. Where the program has no working
directory -- a WASM module whose host names its first preopened directory by a
relative path, as `wasmtime run --dir .` does -- the answer stays relative,
`#P"x/f.txt"` ([`probe-file`](probe-file.md) has the details). A directory answers in directory form
(with its trailing `/`) whatever the spelling, and a link whose target is missing
answers the link itself, as SBCL's does. When you
want an existence answer without the condition, use [`probe-file`](probe-file.md),
which answers the same pathname and returns `nil` instead of signalling.

On both WASM backends a link is resolved only where the module can read it, through a
preopened directory, and a link whose target is absolute is not followed (the WASI
host refuses it), so such a path is not there ([`probe-file`](probe-file.md) has the
details).

```lisp
(ignore-errors (truename "definitely-missing.txt"))   ; => NIL
```

## Backend support

Works on all four backends: one definition in rontolisp source over the per-backend
read of a single link (both WASM backends through the `path_readlink` WASI import,
`--component` through `wasi:filesystem`'s `readlink-at`), spliced into the program
when it is referenced.
