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

## Downloading with quickload

To skip the manual download, [`ql:quickload`](../reference/functions/ql-quickload.md)
fetches a system (and its dependencies) from the real
[Quicklisp](https://www.quicklisp.org/) distribution and then loads it through
exactly the machinery above:

```console
$ rontolisp
> (ql:quickload "split-sequence")
(split-sequence)
> (split-sequence:split-sequence #\, "a,b,c")
("a" "b" "c")
```

The Quicklisp dist metadata drives the download (`systems.txt` for dependency
resolution, `releases.txt` for the tarball URLs); each release is extracted and
cached under `~/.rontolisp/quicklisp/` (override with `RONTOLISP_QUICKLISP_HOME`),
so a repeat `quickload` does no network I/O. The download runs at interpret time
or compile time (Java-side): a compiled program has the sources spliced in and
never fetches at runtime, so `ql:quickload` works on all four backends. Because
loading still goes through the `asdf` subset, the same limitations apply — a
downloaded library only loads if its sources stay inside the supported subset
below.

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
  order. The test-op wiring options `:in-order-to` and `:perform` are
  tolerated and ignored (there is no `test-op`/`operate` machinery), and a
  `:version` value may be any literal form including ASDF's
  `(:read-file-form ...)` indirection (never inspected). Anything else
  (`:defsystem-depends-on`, ...) is an error naming the clause.
- Loading a system twice is a no-op; circular `:depends-on` chains are
  detected and reported.
- The compile path requires a literal, top-level `(asdf:load-system NAME)`;
  the interpreter also accepts a computed name at runtime.

## What can I actually load?

Four real-world libraries load unmodified today, verified on all four
backends (interpreter, JVM, WASM Preview 1 and `--component`):

- **[split-sequence](https://github.com/sharplispers/split-sequence) v2.0.1**:
  `split-sequence`/`split-sequence-if`/`split-sequence-if-not` work on
  strings and lists — including the second return value (the resume index),
  which crosses the function boundary through the multiple-value channel.
  Its CLOS-only `extended-sequence.lisp` is gated behind
  `:if-feature (:or :sbcl :abcl)` and drops out automatically.
- **[parse-number](https://github.com/sharplispers/parse-number) v1.8**:
  `parse-number`/`parse-real-number`/`parse-positive-real-number` handle
  integers, ratios, floats, radix-prefixed literals (`#xFF`, `#3r12`) and
  exponent markers; the `(error 'invalid-number :value ... :reason ...)`
  idiom signals with the intended diagnostics through the lite condition
  stand-ins.
- **[cl-utilities](https://common-lisp.net/project/cl-utilities/) v1.2.4**:
  the whole public API works — its own `split-sequence`, the `extremum`
  family (`extremum`/`extremum-fastkey`/`extrema`/`n-most-extreme`),
  `read-delimited`, `expt-mod`, `collecting`/`with-collectors`,
  `with-unique-names`/`with-gensyms`/`once-only` (three-level nested
  backquote) usable from your own macros, `rotate-byte`, `copy-array` and
  `compose`.
- **[cl-who](https://edicl.github.io/cl-who/) v1.1.5**: Edi Weitz's (X)HTML
  generation macros. `with-html-output-to-string` (and `with-html-output`)
  render s-expression HTML with attributes, nested tags and the local
  `str`/`esc`/`fmt`/`htm` operators; escaping and numeric character entities
  work. Its macro expansion runs a chain of ordinary defuns **and a generic
  function** (`convert-tag-to-string-list`) at macro-expansion time — the CLOS
  static subset plus setf-function definitions (`(defun (setf html-mode) ...)`)
  make it load. Two lite limitations: **`:indent` (pretty-printed output) is
  unsupported**, so the default compact rendering is what you get; and switching
  output mode must use **`(setf (html-mode) :html5)`** — cl-who reads the mode at
  macro-expansion (compile) time, so a runtime `let` rebinding of `*html-mode*`
  is not observed by the already-expanded macro (even though special variable
  binding otherwise works). The default `:xml` mode and `:html5` both render
  correctly.

Runnable demos for the first three — with the per-backend commands and
expected output — live in
[`examples/asdf/`](https://github.com/making/rontolisp/tree/develop/examples/asdf).

A library qualifies today roughly when it stays inside: plain
`defun`/`defmacro`/`defpackage` code, `loop`, `multiple-value-bind` over
`values`-tailed functions, `check-type`/`etypecase` with the supported type
specifiers, declarations (parsed no-ops, `deftype` included), the CLOS static
subset (`defclass`/`defgeneric`/`defmethod`/`make-instance`/`slot-value` with
single dispatch, plus `(defun (setf name) ...)` setf functions), and the lite
`define-condition`/`make-condition`/`warn`/`restart-case`/`return-from`
idioms, and dynamic (special) variable binding (`let`/`let*` over a `defvar`
special). Libraries built on the full metaobject protocol, the condition/restart
system, or pathnames do not load yet (see
[Unsupported CL Features](missing-features.md)). For anything else, the
practical use is structuring **your own** multi-file rontolisp projects —
with `.asd` files that real ASDF can read too.
