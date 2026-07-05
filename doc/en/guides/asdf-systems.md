# Systems (asdf)

The `asdf` package provides a **limited, API-compatible subset of ASDF**, the
Common Lisp build facility: you describe a multi-file project once in a
`NAME.asd` file with [`asdf:defsystem`](../reference/functions/asdf-defsystem.md),
and [`asdf:load-system`](../reference/functions/asdf-load-system.md) loads the
files in dependency order — on every backend. Real ASDF is not ported (it
depends on CLOS, the condition system and the pathname API, none of which
exist here); instead, `.asd` files are parsed as plain data and the supported
`defsystem` subset drives the same machinery as `load`/`require`. A `.asd`
that stays inside the subset works unchanged.

| Operator | Purpose |
|----------|---------|
| [`asdf:defsystem`](../reference/functions/asdf-defsystem.md) | Define a system: `:depends-on`, `:serial`, `:components` |
| [`asdf:load-system`](../reference/functions/asdf-load-system.md) | Load a system (dependencies first, files in order, idempotent) |

## A complete project

```console
app/
  my-app.asd
  package.lisp
  main.lisp
  run.lisp
registry/base/
  base.asd
  base.lisp
```

```console
;; app/my-app.asd
(defsystem :my-app
  :version "0.1.0"
  :depends-on (:base)
  :serial t
  :components ((:file "package")
               (:file "main")))

;; app/package.lisp
(defpackage :my-app (:use :cl) (:export :run))

;; app/main.lisp
(in-package :my-app)
(defun run () (print (base:double 21)))

;; app/run.lisp
(asdf:load-system :my-app)
(my-app:run)
```

Run or compile the entry file; the same directive works on all four backends:

```console
rontolisp app/run.lisp --system-path registry/base                 # interpret
rontolisp app/run.lisp --system-path registry/base -o Prog.class   # JVM
rontolisp app/run.lisp --system-path registry/base -o app.wasm     # WASM
```

`my-app.asd` is found next to `run.lisp`; the `:base` dependency system is
found through `--system-path`. On the compile path the whole system (its
dependency first) is spliced into the program at compile time, exactly like
the compile-time `load` include, so the JVM and WASM compilers see every
`defun` natively.

## The system search path

`asdf:load-system` looks for `NAME.asd` in, in order:

1. the directory of the file doing the `load-system` (like `load`),
2. the directories given with `--system-path` (several can be joined with the
   platform path separator, like `PATH`),
3. the directories in the `RONTOLISP_SOURCE_REGISTRY` environment variable
   (same format).

A dependency system's `.asd` is searched starting from the depending system's
directory, so sibling systems in one registry directory find each other.

## What is (and is not) supported

- `.asd` files are parsed as **data**: only `defsystem` (bare or
  `asdf:`-qualified) and `in-package` forms (skipped) may appear. `#+`/`#-`
  feature conditionals work (evaluated against the target backend's features,
  see [Data Types](../reference/data-types.md#comments-feature-conditionals-and-features)),
  and a `#.` read-time-eval form — the ASDF-version-guard idiom — is skipped
  with a warning instead of being the usual read error.
- `defsystem` supports the metadata options (ignored), `:depends-on`,
  `:serial` and `:components` with `:file`/`:module`/`:static-file` entries;
  a component may carry `:if-feature expr`, which drops the component's files
  when the feature expression does not hold (how libraries gate CLOS-only
  files behind `(:or :sbcl ...)`) while keeping its place in the dependency
  order. Anything else (`:in-order-to`, `:perform`, `:defsystem-depends-on`,
  `(:read-file-form ...)`, ...) is an error naming the clause. There is no
  `test-op`/`operate` machinery.
- Loading a system twice is a no-op; circular `:depends-on` chains are
  detected and reported.
- The compile path requires a literal, top-level `(asdf:load-system NAME)`;
  the interpreter also accepts a computed name at runtime.

Most existing third-party libraries also use Common Lisp features rontolisp
does not implement yet (see [Unsupported CL Features](missing-features.md)),
so the practical use today is structuring **your own** multi-file rontolisp
projects — with `.asd` files that real ASDF can read too.
