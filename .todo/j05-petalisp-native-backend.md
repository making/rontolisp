# j05. petalisp's native backend on rontolisp

Difficulty: High

`(ql:quickload :petalisp)` stops before any petalisp code (measured 2026-11-07, interpreter):
`petalisp.api` depends on `petalisp.native-backend`, which depends on `static-vectors`, whose
`.asd` signals `static-vectors does not support this Common Lisp implementation!`
(`#-(or abcl allegro clasp ccl cmu ecl lispworks sbcl)`). `petalisp.core`, `.ir` and `.codegen`
load on the interpreter; typo, their dependency, loads on the interpreter, the JVM and P1
(`.kb/asdf.md`).

Past the `.asd`, the backend is written against an image whose arrays have raw storage
(`native-backend/`): `array-storage-vector` is `sb-ext:array-storage-vector` /
`ccl::array-data-and-offset`, else `(error "Not implemented yet.")`; `array-storage-pointer`
hands the kernels `static-vector-pointer`; `+n-fixnum-tag-bits+` (`compilation.lisp`) reads a
fixnum array's words through `cffi:mem-ref` to learn the tag width; the kernels load elements
through pointer getters (`c128-memref` ...). It also runs lparallel worker threads and `compile`s
the generated kernels.

Weigh: an `eval/*Forms` patch giving the backend a rontolisp arm that loads with `row-major-aref`
over the array itself (a Lisp vector has no address on the JVM or wasm-GC), with `static-vectors`
admitted only as far as that arm needs; or a backend of rontolisp's own behind petalisp's backend
protocol (`petalisp.core` has a reference backend).

Done when: `(petalisp:compute (petalisp:lazy #'+ #(1 2 3) 1))` prints SBCL 2.2.9's `#(2 3 4)` on
the interpreter, and the item records where the compile paths stop.
