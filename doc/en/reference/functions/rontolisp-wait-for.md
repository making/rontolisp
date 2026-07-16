# rontolisp:wait-for

`(rontolisp:wait-for milliseconds)`

Returns a future that settles to `nil` after the given number of milliseconds
(a non-negative integer). The timer starts immediately, so awaiting it delays
the *awaiting* code only -- other async bodies keep running, which makes
`wait-for` the async counterpart of `cl:sleep` (which blocks and takes
seconds).

```lisp
(rontolisp:await (rontolisp:wait-for 100))   ; => nil
```

Timers run concurrently: two futures started together settle in delay order,
not start order, and awaiting both takes about the longer delay, not the sum.

```lisp
(rontolisp:async-defun delayed (ms tag)
  (rontolisp:await (rontolisp:wait-for ms))
  tag)
(let ((slow (delayed 200 "slow"))
      (fast (delayed 20 "fast")))
  (list (rontolisp:await fast) (rontolisp:await slow)))   ; => ("fast" "slow")
```

## Backend support

`rontolisp:wait-for` exists on the interpreter and the JVM backend today; the
WASM backends reject it at compile time (no host timer is wired up yet).
