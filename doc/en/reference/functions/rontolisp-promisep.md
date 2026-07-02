# rontolisp:promisep

`(rontolisp:promisep value)`

Returns `t` if `value` is a promise — as returned by
[`rontolisp:fetch`](rontolisp-fetch.md) or
[`rontolisp:then`](rontolisp-then.md) — and `nil` otherwise.

```lisp
(rontolisp:promisep (rontolisp:then 1 (lambda (x) x)))   ; => t
(rontolisp:promisep 42)                                   ; => nil
```

A promise is an opaque value: it has no reader syntax and prints as
`#<PROMISE>`.

```lisp
(rontolisp:then 1 (lambda (x) x))   ; => #<PROMISE>
```

## Backend support

Works on every backend and in every WASM mode (Preview 1 included), like
[`rontolisp:await`](rontolisp-await.md) and
[`rontolisp:then`](rontolisp-then.md).
