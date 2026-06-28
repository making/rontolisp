# format

`(format destination control-string args...)`

Produces formatted output driven by `control-string`, whose `~` directives are filled from the evaluated `args`. The `destination` and `control-string` must both be literals: with destination `t` the text is written to standard output and `format` returns nil; with destination `nil` the formatted text is built and returned as a string. Supported directives include `~a`/`~s` (aesthetic/standard printing), `~d`/`~f`/`~e`/`~$` (numbers), and `~%`/`~&`/`~~`, all of which accept prefix parameters and the `:`/`@` modifiers -- see [format](../format.md) for the full table and limitations.

```lisp
(format t "Hello ~a, you are ~d!~%" 'world 42)
```

```
Hello world, you are 42!
```

With destination `nil` the result is returned as a string instead of printed:

```lisp
(format nil "~a+~a=~a" 1 2 3) ; => "1+2=3"
```
