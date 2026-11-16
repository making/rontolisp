# What a list element costs on wasm: the loops (2026-11-16)

The numbers of `.kb/adjustable-arrays.md`, "What an element access costs", and
`.kb/starlark-frontend.md`, "The list element on wasm". The Starlark programs are
`.todo/artefacts/j54-starlark-faster-than-cpython/`.

- `micro.sh` -- compiles a program to WASM preview 1 and prints the best of five user+sys
  seconds of the precompiled module, and its size. Wall time was no use: the box was
  loaded.
- 30M rounds each, under a handler (EH mode, as every Starlark program is): `read.lisp`
  (`(aref v j)` of a general vector), `store-variable-place.lisp` and
  `store-other-place.lisp` (`(setf (aref v j) x)` through a function, by a variable and by
  `(the t v)`: the first asks `stringp`), `call-only.lisp` (the same function with no
  store), `store-packed.lisp` (an undeclared `(unsigned-byte 8)` vector), `struct.lisp` (a
  predicate and a reader), `compare.lisp` (two boxed fixnums), `length.lisp`.

```bash
J=$PWD/target/rontolisp-0.1.0-SNAPSHOT-exec.jar
D=$PWD/.todo/artefacts/j55-wasm-list-element-cost
cd "$(mktemp -d)" && $D/micro.sh "java -jar $J" now $D/*.lisp
```

What one type test costs was read off a hand-edited `.wat` of `store-variable-place`'s
module (`wasm-tools print`, then `wasm-tools parse`): the `ref.test` of the string
struct ahead of `_charvec_p` removed, 1.36 -> 1.31 s; `_charvec_p`'s call removed,
1.36 -> 0.80 s.
