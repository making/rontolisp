# truename

`(truename pathname)`

Returns the pathname when the file exists and signals an error when it does not.
The signal is the point: `(ignore-errors (truename path))` is the Common Lisp
idiom for "this path if it is there, `nil` otherwise", and libraries use it to
probe for an optional file or directory.

rontolisp resolves no symbolic links and makes nothing absolute -- a pathname is
its namestring -- so the value on success is the argument string itself. When you
want the answer without the condition, use [`probe-file`](probe-file.md), which
asks the same question and returns `nil` instead of signalling.

```lisp
(ignore-errors (truename "definitely-missing.txt"))   ; => NIL
```

## Backend support

Works on all four backends: one definition in rontolisp source over `probe-file`,
spliced into the program when it is referenced.
