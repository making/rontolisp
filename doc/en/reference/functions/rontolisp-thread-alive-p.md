# rontolisp:thread-alive-p

`(rontolisp:thread-alive-p thread)`

Returns `t` while the thread behind the handle is still running, `nil` once it has died.
After a [`rontolisp:join-thread`](rontolisp-join-thread.md) the answer is reliably
`nil` (the join waits for the thread's teardown, not just its value).

```lisp
(let ((th (rontolisp:make-thread (lambda () 1))))
  (rontolisp:join-thread th)
  (rontolisp:thread-alive-p th)) ; => NIL
```

## Limitations

- A value that is not a thread handle is an error (use
  [`rontolisp:threadp`](rontolisp-threadp.md) first when unsure).
- Interpreter and JVM backend only, like
  [`rontolisp:make-thread`](rontolisp-make-thread.md) itself.
