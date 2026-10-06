# d75. The streamless read family ignores a Gray instance bound to `*standard-input*`

Difficulty: Medium

`(read-char)` / `(read-line)` with `*standard-input*` bound to a Gray instance never reach the
Gray generics. Measured 2026-10-06:

```lisp
(defclass gsi-src (rontolisp:fundamental-character-input-stream)
  ((gsi-text :initarg :text) (gsi-pos :initform 0)))
(defmethod rontolisp:stream-read-char ((s gsi-src))
  (with-slots (gsi-text gsi-pos) s
    (if (< gsi-pos (length gsi-text)) (prog1 (char gsi-text gsi-pos) (incf gsi-pos)) :eof)))
(defun f () (let ((*standard-input* (make-instance 'gsi-src :text "hi"))) (list (read-char) (read-line))))
(print (f))
```

| SBCL 2.2.9 | interpreter | JVM | P1 / component |
|---|---|---|---|
| `(#\h "i")` | signals `READ-CHAR expects an input stream` | reads the process stdin (blocks; `end of file` on `/dev/null`) | trap |

The Gray dispatch decides on the argument as written: the interpreter's wraps
(`LispEvaluator`, `dispatchesToGray`) see nil and call the base built-in; on the compile paths
`%gray-*-dispatch` gets nil, `%stream-target` leaves it nil, and the fallback built-in's
designator rule (`StreamDesignators.resolveInput`) then hands the instance to the handle-based
read. The same likely holds for `*standard-output*` bound to a Gray output instance with the
streamless print family -- measure it first.

Resolve nil to the current `*standard-input*` (`*standard-output*`) before the instance test, on
all four, without making a program that never binds the variable pay for it
(`.kb/standard-output-redirect.md`, "Activation rule").
