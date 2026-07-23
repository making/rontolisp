# rontolisp:catch

`(rontolisp:catch future handler)`

Returns a fresh future that, on the input's error settlement, invokes
`handler` on the condition and settles to the handler's return value; on
successful settlement the value passes through unchanged. If the handler
itself signals, the returned future carries THAT condition.

```lisp
(rontolisp:async-defun boom () (error "nope"))
(rontolisp:await
  (rontolisp:catch (boom) (lambda (c) (declare (ignore c)) :fallback)))   ; => :FALLBACK
```

Use `rontolisp:catch` when the future crosses a boundary as a value and
you want a JavaScript `.catch`-style single-handler fallback. Type-dispatch
already exists lexically as `(handler-case (rontolisp:await f) (my-err (c) ...))`
-- write that instead when the future is right there in the body. A user
who wants typed dispatch inside a catch handler writes it explicitly:

```console
(rontolisp:catch f (lambda (c)
                     (handler-case (signal c)
                       (my-err (e) ...)
                       (error (e) ...))))
```

A non-future first argument is a `type-error`.

### Name collision with `cl:catch`

Common Lisp's `catch` / `throw` is a tag-based non-local exit special form.
This operator is `rontolisp:catch`, in a different package: qualified names
never collide (`cl:catch` still names the CL special form), and the
`rontolisp` package does not `:use` `cl`, so a bare `catch` inside
`(in-package :rontolisp)` reaches this operator. A user in `cl-user` (or in
a package that `:use`s both) needs the explicit `rontolisp:` / `rl:` prefix
to get this operator, and the explicit `cl:` prefix (or an unqualified
bare name in `cl-user`) to get the tag-based special form.

## Backend support

Same as [`rontolisp:then`](rontolisp-then.md): interpreter, JVM, WASM
`--component`. Preview 1 WASM supports the success passthrough only (the
error path there needs the futured error-at-await contract that the
component backend provides). `--no-gc` rejects at compile time.
