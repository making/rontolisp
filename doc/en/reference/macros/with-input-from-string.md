# with-input-from-string

`(with-input-from-string (stream string) body...)`

Binds `stream` to an input stream reading from `string`, evaluates the body forms, and returns the value of the last one. `read-line` consumes the string line by line and returns nil at the end; `read` parses one datum per line (like `read` on a file stream, it is line-oriented, so the rest of a line after the first datum is skipped). Works in all three backends.

```lisp
(with-input-from-string (s "(1 2 3)")
  (read s)) ; => (1 2 3)
```

Naming the bound variable `*standard-input*` redirects the whole
stream-argument-less read family for the extent of the body -- including inside
called functions -- because `read-line`, `read-char`, `read` and `peek-char`
read the current (dynamically bound) value of `*standard-input*` at call time.
A `nil` stream argument is the same designator, so a reader forwarding its own
optional argument follows the redirect too; only `t` always names the process
standard input.

```lisp
(progn
  (defun next-line (&optional stream) (read-line stream))
  (with-input-from-string (*standard-input* "from the string")
    (next-line))) ; => "from the string"
```
