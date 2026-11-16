# Starlark under CPython on wasm: split, join, sorted, a grown vector and a chain of concatenations (2026-11-16)

The numbers of `.kb/starlark-frontend.md`, "A sequence at a time", of
`.kb/bulk-primitives.md` and of `.kb/wasm-gc-strings.md`, "A chain of concatenations is
one string". The programs, `time.sh`, the sampler and `parts/` are
`.todo/artefacts/j54-starlark-faster-than-cpython/` and `j63-starlark-under-cpython/`.

- `bulkcheck.star` -- `split`, `join` and `sorted` at their edges (limits, empty pieces,
  characters of several bytes, ints past 64 bits, strings sharing a long prefix, by a key
  with equal keys both ways, 3,000 items of four spans): the same output from the four
  backends, and from Go and Java Starlark but for `len` of a string with characters of
  several bytes (theirs counts bytes) and CPython's quotes.
- How a candidate was measured: the library edited in a copy of `target/classes`
  (`java -cp copy am.ik.rontolisp.cli.RontoLispCli prog.star -o prog.wasm`), `wasmtime
  compile`, then `j63-starlark-under-cpython/ab.sh` against the module before it.
- What the sampler said after the three primitives, and that the item's plan had not: a
  comprehension's loop is not the cost of `comps.star`, the growth of the vector it
  pushes into is (an element loop in `vector-push-extend`); `list(range(n))` spent 11%
  in `stringp` of a vector it had just made.
- A backend change was checked against the whole ci-spec corpus without the forty minutes
  `CiSpecE2eTest` takes through the exec jar: the driver's concatenated program
  (`ci-program.lisp` of its `@TempDir`, copied with the fixtures staged beside it while a
  run was in flight) compiled by the build before and by the build after, both run under
  `wasmtime run -W gc=y -W exceptions=y --dir . --dir /tmp X.wasm alpha beta`, the 9,976
  lines of output compared. That is how `StringValuedForms.certainlyString` was found to
  be no licence to skip a concatenated piece's normalization (a trap at line 1,118).
