# Prototype: Starlark lowered to the core forms (2026-11-15)

Question: can a non-Lisp language join the pipeline through the same seam as Scheme and
Clojure, i.e. be lowered to core forms and handed to the unchanged compiler?
Answer: yes. Nothing downstream of the seam was touched.

## What is here

- `sl2lisp.py` -- Starlark source -> core forms, printed as Common Lisp text. Parsing is
  borrowed from Python's `ast` (Starlark is syntactically a Python subset); the LOWERING is
  what was under test.
- `starlark-rt.lisp` -- the run-time half, plain Common Lisp (values, operators, call
  binding, built-ins, methods).
- `runsuite.py`, `run4.sh` -- run bazelbuild/starlark's `test_suite/testdata` on the four
  backends: one program per `.star` file, each `---` chunk with its globals prefixed and each
  top-level statement inside a `handler-case`.
- `demo.star`, `bench.star` -- a feature tour and a micro benchmark.

```bash
D=$PWD/.todo/artefacts/j45-starlark-front-end-prototype
export JAR=$PWD/target/rontolisp-0.1.0-SNAPSHOT-exec.jar
export SUITE=$PWD/../starlark/test_suite/testdata      # a bazelbuild/starlark checkout
cd "$(mktemp -d)"
(cat $D/starlark-rt.lisp; python3 $D/sl2lisp.py $D/demo.star) > demo.lisp && $D/run4.sh demo.lisp
python3 $D/runsuite.py                                  # SHOW=99 lists every failing chunk
```

## The lowering

| Starlark | core forms |
|---|---|
| `None`, `True`, `False` | `:none`, `T`, `NIL` |
| int, float, string | integer (bignum), double, string |
| list, tuple, dict, range, function | structs; a list is an adjustable vector plus `frozen` and an iteration count, a dict an `equal` hash table from a normalized key to `(key . value)` |
| identifier | `\|name\|`, verbatim (escaped when it has no lowercase letter) |
| top-level `x = v` | top-level `setq` |
| top-level `def` bound once, no `*args`/`**kwargs` | `defun` (direct entry) plus a function VALUE in the variable of the same name |
| nested `def`, `lambda` | function value over a `lambda` |
| function body | `(block %fn (let (every assigned name) ...))`; `return` is `return-from` |
| `for` | `tagbody`/`go` over the iterable's vector; `break`/`continue` are `return-from` of two blocks; `unwind-protect` only when the body has a `return` |
| comprehension | the same loop pushing into an accumulator, its variables in a fresh `let` |
| `a + b`, `a == b`, `a[i]`, `x in y` | `(%sl-add a b)`, ... -- generic, dispatching at run time |
| `if c:` | `(if (%sl-truth c) ..)`; a comparison or `not` is used as the test directly |
| `a and b` in value position | `(let ((v a)) (if (%sl-truth v) b v))` |
| call of a known top-level `def`, positional, exact count | direct call |
| any other call | `(%sl-call f (list pos..) (list "kw" v ..))`, binding defaults, `*args`, `**kwargs` at run time |
| built-in function | `(%sl-b-NAME ..)`, Starlark keywords as Common Lisp keywords |
| `x.m(..)` | `(%sl-m-NAME x ..)`, dispatching on the receiver |
| `fail`, a dynamic error | `error` |

## Results

Linux x86_64, Xeon E5-2697A v4, Java 25.0.5, wasmtime 49.0.0, exec jar built 2026-11-10,
bazelbuild/starlark at `c0af0ea`.

- `demo.star` (closures, defaults, `*args`/`**kwargs`, comprehensions, dicts, slices,
  string methods, bignums): correct on the first run and identical on the interpreter, the
  JVM, WASM preview 1 and the WASM component. 45 KB of forms; `.class` 161 KB, `.wasm` 145 KB.
- Conformance suite, 37 files / 430 chunks: **404 pass (94%)**, and every file prints the
  same on all four backends. A chunk passes when it reports no assertion and raises an
  error exactly when one is expected; the error MESSAGE is not matched. Whole run: 2 min 58 s
  wall (four backends in parallel per file).
- The 26 failures are gaps of the prototype, none of the pipeline:
  - missing methods and built-ins (10): `splitlines`, `title`, `islower`/`isupper`/`istitle`, `dir`
  - keyword arguments to a method or to a built-in without that keyword (4): `dict.update(k=v)`, ...
  - argument validation (6): `int`, `list.index` bounds, 3 chunks each
  - `format` mixing automatic and manual numbering (3)
  - "local variable referenced before assignment" (2)
  - evaluation order of `a[i] = rhs` (1), the name inside a built-in's `repr` (1)
- One divergence was found and was the run-time's fault: `(1, 2).append(3)` reached a struct
  accessor with the wrong struct. That is an error on the interpreter and the JVM (different
  messages) and an uncatchable trap on WASM. With the receiver checked first, all four agree.
  A front end's run-time library must check before every accessor.
- `bench.star` (`fib(27)` plus a 10^6-iteration `for` over `range`), wall time of one run:

  | runner | time |
  |---|---|
  | rontolisp interpreter | 5.83 s |
  | rontolisp JVM `.class` | 0.49 s |
  | rontolisp WASM preview 1 | 0.48 s |
  | rontolisp WASM component | 0.36 s |
  | Java Starlark (`starlark_deploy.jar`) | 0.30 s (an error exit: it forbids recursion; `.kb/starlark-frontend.md`) |
  | CPython 3.12 | 0.14 s |

  Every operator is a generic call and `range` is materialized; nothing was optimized.

## What the prototype does not answer

- The reader. Indentation, line joining and the spec's grammar still have to be written; it
  is known work, not a risk to the lowering.
- `load()`, freezing after a module loads, static name resolution, error positions, the
  string model (bytes, UTF-16 units or code points), float `repr`.
