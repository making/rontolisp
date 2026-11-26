---
name: rontolisp
version: {{version}}
description: >-
  Write, run and debug rontolisp programs -- the Common Lisp subset that runs
  identically on an interpreter, the JVM and two WebAssembly backends. Use this
  whenever you touch a .lisp or .asd file in a rontolisp project, whenever the
  user says rontolisp, and whenever you are about to write Common Lisp that will
  run under rontolisp -- even if the request never uses the word "skill" or names
  a backend. Your Common Lisp knowledge is a PRIOR here, not the truth: this
  skill carries the delta -- exactly which operators exist, which CL features are
  missing or partial, the rontolisp-only extensions (fetch, async/await,
  linalg/vec, java:, wit-import, wasm-export, ql:quickload), and how to actually
  run a program on each backend.
---

# rontolisp

rontolisp is a Common Lisp subset with four backends that must agree: the
interpreter, a JVM bytecode compiler (`-o Prog.class`), and two WebAssembly
compilers (wasm-GC by default, `--no-gc` for a linear-memory module). Programs
are ordinary `.lisp` files and systems are ordinary `.asd` files, so a real
Common Lisp library often loads verbatim -- and so does a real Common Lisp
mistake.

This bundle is generated from the rontolisp documentation
({{operator-counts}}); every file under `references/` is the same text published
at {{site-base}}/docs/en/. Skill version {{version}}.

## How to work in this language

Your Common Lisp knowledge is right about most of the core and wrong in
specific, recurring places. It fails in exactly two ways, and both are cheap to
check before you write the code rather than after the error:

1. **Reaching for an operator that is not there.** rontolisp ships a fixed set.
   Before using an operator you have not already seen working in this project,
   look it up in `references/operators.md` -- every name it has, by category. Not
   listed means not there: reshape the code, or write the helper yourself.
2. **Assuming full CL semantics behind a name that IS there.** `loop`, `format`,
   `defpackage`, CLOS, `values`, string mutation and non-local exits all exist
   and all stop short of the standard somewhere. The whole delta is inlined
   below -- read it before you lean on any of them.

Then run the program. An implementation with four backends is not a place to
reason about output; a program that has not been executed at least on the
interpreter is not finished. If it is meant to be compiled, run it on the
backend it targets, because that is where the compiled-path restrictions bite
(`progv`, `java:`, a `return-from` crossing `flet`, redefining a CL function).

When something fails, prefer narrowing to the smallest form that still fails and
running it in the REPL over rereading the code -- the error messages name the
operator and the position.

## Running a program

```bash
rontolisp prog.lisp                                  # interpret
rontolisp prog.lisp -o Prog.class && java Prog       # JVM (class name = file stem, no directories)
rontolisp prog.lisp -o prog.wasm && wasmtime run prog.wasm
rontolisp prog.lisp -o prog.wasm --component && wasmtime run prog.wasm   # WASI 0.3
rontolisp                                            # REPL
rontolisp -e '(print (+ 1 2))'                       # one form
rontolisp format prog.lisp                           # re-indent in place (whitespace only)
rontolisp test tests/main.lisp                       # run a rove suite; exit 0 passed, 1 failed
```

`rontolisp` is the native binary; `references/getting-started/build.md` says how
to get one, and `java -jar target/rontolisp-*-exec.jar` stands in for it inside a
build tree. The `-o` extension picks the backend. A fetch component also needs
`-S http=y`. Other flags
that change the emitted artifact -- `--dynamic`, `--optimize=off`/`=size`, `--no-gc`,
`--simd`, `--blas`, `--no-prune`, `--emit-wit` -- are described under
`references/compiling/`.

## Guides

What Common Lisp knowledge cannot supply: the surfaces rontolisp adds (HTTP,
async, sockets, `java:`, the numeric kernels, WASM contracts, systems and
libraries), and the places where the subset spells its own behavior out in full.
Read the guide before using one of these.

{{guides-table}}

{{include:guides/missing-features.md}}

## Where everything is

- `references/operators.md` -- every operator by category. The existence check.
- `references/examples.md` -- every worked example in the repository, by
  directory, with the whole program mirrored under `references/examples/`. Before
  writing a program of a shape you have not written here before -- a WASM
  component, an HTTP handler, a Clack app, an `.asd` system, a browser demo --
  open the closest one: it shows the imports, the entry point and the build
  command that no reference page states.
- `references/contents.md` -- every documentation page by title.
- `references/reference/functions/<slug>.md`,
  `references/reference/macros/<slug>.md`,
  `references/reference/special-forms/<slug>.md` -- one page per operator, with a
  runnable example and its own deviations from Common Lisp. `operators.md` links
  each name to its page (the slug is not always the name: `+` is `plus.md`).
- `references/reference/data-types.md` -- arrays, hash tables, the numeric tower,
  the packed float literals `#d(...)` / `#f(...)`.
- `references/reference/packages.md`, `references/reference/function-namespace.md`
  -- the package roster, and what `#'name` may and may not name on a compiled
  backend.
- `references/getting-started/`, `references/compiling/` -- the CLI, the REPL, the
  output shapes and their flags.
