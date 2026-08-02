# rontolisp:destroy-thread

`(rontolisp:destroy-thread thread)`

Interrupts the thread behind the handle and returns the handle. A thread blocked in a
waiting operation unblocks with an error there; like Java's `Thread.interrupt`, a body
that never blocks may run to completion anyway — this is a request, not a kill.

```lisp
(let ((th (rontolisp:make-thread (lambda () 1))))
  (rontolisp:join-thread th)
  (rontolisp:threadp (rontolisp:destroy-thread th))) ; => T
```

## Limitations

- Delivery is asynchronous: `thread-alive-p` may still answer `t` for a moment after
  this returns.
- A value that is not a thread handle is an error.
- Interpreter and JVM backend only, like
  [`rontolisp:make-thread`](rontolisp-make-thread.md) itself.
