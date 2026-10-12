# f60. The WIT reader accepts a type and a function of one name in an interface

Difficulty: Low

Found while writing `examples/clojure/wit-types/shipping.wit` (2026-10-12): an interface declaring
`record quote { ... }` and `quote: func(...) -> result<quote, problem>` was read by
`rontolisp.wit/import` and ran on the interpreter and the JVM, while `wasm-tools component wit`
refuses the file:

```
error: name `quote` is defined more than once
     --> shipping.wit:30:3
```

A WIT file rontolisp accepts and every other tool rejects lets a program depend on it until the
file meets a real host (`wasm-tools`, `wit-bindgen`, a `--component` build linking it).

## Plan

1. Measure `wasm-tools component wit` on the duplicate shapes (type/func, func/func, type/type,
   a `use`d name against a local one, across interfaces of one package) and pin each refusal
   in the WIT parser's tests, in its words.
2. Refuse them in the WIT reader every `rontolisp:wit-import`/`wit-export` path shares.
