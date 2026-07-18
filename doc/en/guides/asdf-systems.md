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

- `.asd` files are parsed as **data**: `defsystem` (bare or
  `asdf:`-qualified), `in-package`/`defpackage` forms (skipped), and top-level
  `defparameter`s of pure literal/conditional values (evaluated into a
  parse-time environment) may appear. `#+`/`#-` feature conditionals work
  (evaluated against the target backend's features, see
  [Data Types](../reference/data-types.md#comments-feature-conditionals-and-features)),
  a `#.` read-time-eval form is resolved against those `defparameter`s (the
  `(:file #.*string-file*)` idiom) — an unresolvable one, like an ASDF
  version guard, is skipped with a warning — and a `:depends-on` entry may be
  `(:feature EXPR DEP)`, contributing its dependency only when the feature
  expression holds.
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

## Built-in shim systems

Some Quicklisp libraries depend on per-implementation portability layers that
cannot know rontolisp from their side. rontolisp ships those as **built-in
ASDF systems**: `asdf:load-system`/`ql:quickload` (and a `:depends-on` from a
real library) resolve the name to a bundled shim instead of downloading it.

| System | What the shim provides |
|--------|------------------------|
| `usocket` | the socket API over `rontolisp:tcp-*` (see the [TCP guide](tcp-sockets.md#the-usocket-compatible-shim)) |
| `trivial-gray-streams` | the portable Gray-stream classes/generics, adapting onto rontolisp's own protocol (`rontolisp:fundamental-character-output-stream`, `rontolisp:stream-write-char`/`-string` — what `write-string`/`write-char` dispatch to for a CLOS instance stream) |
| `closer-mop` | `class-slots` returning real slot metadata (`(name declared-type)` pairs from the class registry; a "slot metaobject" is that pair, `slot-definition-name`/`-type` read it) |
| `flexi-streams` | pass-through streams (a flexi stream IS the underlying stream) |
| `float-features` | `single-float-bits`/`bits-single-float` and the double variants over the IEEE 754 bit primitives (interpreter + JVM; the WASM numeric model cannot carry 64-bit bit patterns) |
| `uiop` | a package stub plus [`uiop:add-package-local-nickname`](../reference/functions/add-package-local-nickname.md) |

The shims are deliberately thin: they satisfy what the loadable libraries
actually call, not the full upstream APIs.

## What can I actually load?

Six real-world libraries load unmodified today, verified on all four
backends (interpreter, JVM, WASM Preview 1 and `--component`), and a seventh
(jzon) loads on the interpreter:

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
- **[assoc-utils](https://github.com/fukamachi/assoc-utils)**: Eitaro
  Fukamachi's alist utilities. The read/convert API works — `aget` (with a
  default), `alist-keys`/`alist-values`, `alist-plist`/`plist-alist`,
  `remove-from-alist` and its `delete-from-alistf` place variant,
  `alist-hash`/`hash-alist`, `with-keys`, the key-path `alist-get` and
  `alist=`. Two lite limitations: **`(setf (aget alist key) value)` is
  unavailable** (it needs `define-setf-expander`'s five-value protocol, which
  is a parsed no-op here), so build the alist with `cons`/`delete-from-alistf`
  instead; and **`alistp` is unreliable on a non-alist** — its early exit out
  of a `mapl` lambda is a lambda-local return here, so it can report `t` for a
  value a real ASDF host would reject.
- **[cl-base64](https://github.com/darabi/cl-base64) v3.4**: Kevin Rosenberg's
  Base64 encoder/decoder. `string-to-base64-string`/`base64-string-to-string`
  (with `:columns` line wrapping and the `:uri` alphabet), the
  `(unsigned-byte 8)` array pair (`usb8-array-to-base64-string` and back) and
  the integer pair all work; a bad input character signals
  `bad-base64-character`, whose `:input`/`:position`/`:code` slots are readable
  on the interpreter (the compiled backends signal a plain condition through
  the lite `#'error` wrapper — still caught by the same `handler-case`). One
  numeric limitation: the WASM backends represent an integer beyond the `i31`
  range (about 2^30) as a float, so `integer-to-base64-string` of a large
  integer diverges there.

- **[com.inuoe.jzon](https://github.com/Zulu-Inuoe/jzon) v1.1.4** (the real
  library via `(ql:quickload '#:com.inuoe.jzon)`, **interpreter only**): JSON
  parsing and stringification including the README walkthrough — hash-table /
  vector round-trips, `:key-fn`/`jzon:coerce-key`, the `:stream` writer API
  over a Gray-stream class writing into an adjustable string, incremental
  `jzon:writer`, and CLOS-instance stringification through the `closer-mop`
  shim's real slot list. Its dependencies (`closer-mop`, `flexi-streams`,
  `float-features`, `trivial-gray-streams`, `uiop`) resolve to the built-in
  shim systems below. The JVM/WASM compile path cannot run the full library
  yet (its float printer does 64-bit/bignum bit arithmetic beyond the WASM
  numeric model, and its adjustable-string buffers are interpreter-only);
  the isolated language features it forced are compiled everywhere, tracked
  by the `jzon-residue-features` ci-spec case.

Runnable demos for all six — with the per-backend commands and
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
