# rontolisp:join-thread

`(rontolisp:join-thread thread)`

Blocks until the thread's function returns, waits for the thread itself to die, and
yields the function's value. If the thread died signalling an error, the error is
re-signaled in the joining thread, so a `handler-case` around the join dispatches by
condition type exactly like a same-thread signal.

```lisp
(rontolisp:join-thread (rontolisp:make-thread (lambda () (+ 40 2)))) ; => 42
```

After a join, [`rontolisp:thread-alive-p`](rontolisp-thread-alive-p.md) on the same
handle answers `nil`.

## Limitations

- Interpreter and JVM backend only, like
  [`rontolisp:make-thread`](rontolisp-make-thread.md) itself.
- A value that is not a thread handle is an error.
- There is no timed join.
