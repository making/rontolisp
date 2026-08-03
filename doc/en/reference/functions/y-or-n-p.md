# y-or-n-p

`(y-or-n-p &optional format-control &rest format-arguments)`

Asks a yes/no question and returns `t` or `nil`. The optional
[`format`](../macros/format.md) control and its arguments are printed first,
followed by `" (y or n) "`; then one line is read from standard input. A line
starting with `y` or `Y` answers `t`, one starting with `n` or `N` answers `nil`,
and anything else -- an empty line included -- re-asks, reprinting the whole
prompt.

Lite: Common Lisp reads single CHARACTERS without echo, where this reads a whole
line, so the answer is only taken once the user presses return. End of input
answers `nil` instead of signalling, because a backend with no interactive user
has no way to ask again.

```console
(if (y-or-n-p "Delete ~A?" "old.sql")
    (delete-file "old.sql")
    (print "kept"))
```

Answering `maybe` prints `Delete old.sql? (y or n) ` a second time; answering
`yes` (or a bare `y`) then returns `t` and the file is deleted.

## Backend support

All four backends -- one definition in rontolisp source over
[`format`](../macros/format.md) and [`read-line`](read-line.md).
