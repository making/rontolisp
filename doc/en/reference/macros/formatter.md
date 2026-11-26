# formatter

`(formatter control-string)`

A function of a stream and arguments that renders `control-string` over the arguments to the stream, as [`format`](format.md) would, and answers the arguments the control did not consume. `control-string` is a literal string, not evaluated.

A function is a control in its own right: `format` and the `~?` directive call it with the stream and the arguments.

```lisp
(let ((f (formatter "<~a>")))
  (list (format nil f 1) (format nil "~?|" f '(2)))) ; => ("<1>" "<2>|")
```
