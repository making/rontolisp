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
- **Compiling tree-shakes the system.** A function, variable or constant a
  loaded system defines but your program never reaches — following names
  through the source, including quoted symbols and whole string literals — is
  left out of the `.class`/`.wasm`. Classes, generic functions, methods,
  conditions and structures always stay. Compile with `--no-prune` (or
  `--dynamic`) to keep every definition; see
  [Compiling to the JVM](../compiling/jvm.md) for the one consequence.

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

Eleven real-world libraries load unmodified today. The **Backends** column says
where each one is verified — "all four" means the interpreter, the JVM, WASM
Preview 1 and `--component`.

| Library | Backends | What works | Lite limitations |
|---------|----------|------------|------------------|
| [split-sequence](https://github.com/sharplispers/split-sequence) v2.0.1 | all four | `split-sequence`/`split-sequence-if`/`split-sequence-if-not` on strings and lists — including the second return value (the resume index), which crosses the function boundary through the multiple-value channel | none — its CLOS-only `extended-sequence.lisp` is gated behind `:if-feature (:or :sbcl :abcl)` and drops out automatically |
| [parse-number](https://github.com/sharplispers/parse-number) v1.8 | all four | `parse-number`/`parse-real-number`/`parse-positive-real-number` over integers, ratios, floats, radix-prefixed literals (`#xFF`, `#3r12`) and exponent markers | none — the `(error 'invalid-number :value ... :reason ...)` idiom signals with the intended diagnostics through the lite condition stand-ins |
| [cl-utilities](https://common-lisp.net/project/cl-utilities/) v1.2.4 | all four | the whole public API — its own `split-sequence`, the `extremum` family (`extremum`/`extremum-fastkey`/`extrema`/`n-most-extreme`), `read-delimited`, `expt-mod`, `collecting`/`with-collectors`, `with-unique-names`/`with-gensyms`/`once-only` (three-level nested backquote) usable from your own macros, `rotate-byte`, `copy-array` and `compose` | none |
| [cl-who](https://edicl.github.io/cl-who/) v1.1.5 | all four | Edi Weitz's (X)HTML generation macros. `with-html-output-to-string` (and `with-html-output`) render s-expression HTML with attributes, nested tags and the local `str`/`esc`/`fmt`/`htm` operators; escaping and numeric character entities work. Its macro expansion runs a chain of ordinary defuns **and a generic function** (`convert-tag-to-string-list`) at macro-expansion time — the CLOS static subset plus setf-function definitions (`(defun (setf html-mode) ...)`) make it load | **`:indent` (pretty-printed output) is unsupported**, so the default compact rendering is what you get; and switching output mode must use **`(setf (html-mode) :html5)`** — cl-who reads the mode at macro-expansion (compile) time, so a runtime `let` rebinding of `*html-mode*` is not observed by the already-expanded macro (even though special variable binding otherwise works). The default `:xml` mode and `:html5` both render correctly |
| [assoc-utils](https://github.com/fukamachi/assoc-utils) | all four | Eitaro Fukamachi's alist utilities — `aget` (with a default, and a settable `setf` place), `alist-keys`/`alist-values`, `alist-plist`/`plist-alist`, `remove-from-alist` and its `delete-from-alistf` place variant, `alist-hash`/`hash-alist`, `with-keys`, the key-path `alist-get` and `alist=`, and `alistp` (its early `return-from` out of a `mapl` lambda is a real non-local exit on every backend, so it reports `nil` for a non-alist like the interpreter) | none |
| [cl-base64](https://github.com/darabi/cl-base64) v3.4 | all four | Kevin Rosenberg's Base64 encoder/decoder. `string-to-base64-string`/`base64-string-to-string` (with `:columns` line wrapping and the `:uri` alphabet), the `(unsigned-byte 8)` array pair (`usb8-array-to-base64-string` and back) and the integer pair all work; a bad input character signals `bad-base64-character` | that condition's `:input`/`:position`/`:code` slots are readable on the interpreter (the compiled backends signal a plain condition through the lite `#'error` wrapper — still caught by the same `handler-case`) |
| [md5](https://github.com/pmai/md5) v2.0.4 | all four | Pierre Mai's MD5 message-digest implementation (RFC 1321). `md5sum-sequence` on strings and `(unsigned-byte 8)` vectors, `md5sum-string` (UTF-8 through the flexi-streams shim's `string-to-octets`), and the incremental `make-md5-state`/`update-md5-state`/`finalize-md5-state` API all match the RFC test vectors | the unsigned 32-bit working state rides the WASM backends' boxed 64-bit integer path, so digests are identical on all four backends |
| [cl-ppcre](https://github.com/edicl/cl-ppcre) v2.1.2 | all four | Dr. Edmund Weitz's Perl-compatible regular expression library, loaded from its real unmodified sources. `scan` (with register bounds), `scan-to-strings`, `split`, `regex-replace`/`regex-replace-all`, `all-matches`(-as-strings), `count-matches`, the `do-scans`/`do-matches`(-as-strings) iteration macros, `register-groups-bind`, `quote-meta-chars`, parse-tree regexes and inline modifiers like `(?i)` all work — the generated scanner closures rely on named `block`/`return-from` crossing loops, which the compile backends implement as lexical named exits | none |
| [com.inuoe.jzon](https://github.com/Zulu-Inuoe/jzon) v1.1.4 | all four | JSON parsing and stringification including the README walkthrough — hash-table / vector round-trips, `:key-fn`/`jzon:coerce-key`, the `:stream` writer API over a Gray-stream class writing into an adjustable string, incremental `jzon:writer`, and CLOS-instance stringification through the `closer-mop` shim's real slot list. Its dependencies (`closer-mop`, `flexi-streams`, `float-features`, `trivial-gray-streams`, `uiop`) resolve to the built-in shim systems above | its three numeric leaf components (`eisel-lemire.lisp`/`ratio-to-double.lisp`/`schubfach.lisp` — the eisel-lemire float reader and Schubfach float printer, whose u64/u128 bit algorithms are beyond the WASM numeric model) are replaced at load time by built-in shims over rontolisp's native float arithmetic and printer, so float text takes rontolisp's cross-backend-identical shape rather than Schubfach's shortest-round-trip string, and parsing an extreme exponent can be a few ulps off exact rounding (a decimal exponent of magnitude 22 or less — the common range — rounds exactly once). The usual WASM caveats apply on the WASM backends: large-float print shape, hash-table iteration order and non-ASCII `\u` escapes (`code-char` is byte-oriented there) |
| [ironclad](https://github.com/sharplispers/ironclad) v0.61 (the SHA-256 / HMAC / PBKDF2 / HKDF / SCRAM slice) | all four | Nathan Froyd's and Guillaume LE VAILLANT's cryptographic toolkit, loaded from its real unmodified sources. `digest-sequence` for `:sha256`/`:sha224`, the incremental `make-digest`/`update-digest`/`produce-digest` API, HMAC through both `make-mac`/`update-mac`/`produce-mac` and the deprecated `make-hmac` trio, `make-kdf` with `:pbkdf2` or `:hmac-kdf` (HKDF) + `derive-key`, `pbkdf2-hash-password`, the `octets-to-integer`/`integer-to-octets` converters, and the `byte-array-to-hex-string`/`hex-string-to-byte-array`/`ascii-string-to-byte-array` helpers all reproduce the published FIPS 180-2, RFC 4231, RFC 5869 and RFC 7677 test vectors — including RFC 7677's SCRAM-SHA-256 client proof end to end, the sequence a PostgreSQL client authenticates with | ironclad's own `.asd` is an executable program (component classes, a defsystem-generating macro), so rontolisp substitutes a bundled replacement declaring the loadable slice — the sources loaded are the library's real ones, but only that slice: **ciphers, public-key operations, AEAD modes and the other digests are not available**. Requesting an absent algorithm signals at the call. Two files are present only as narrow substitutions: `public-key.lisp` contributes just `octets-to-integer`/`integer-to-octets` (verbatim), and `prng.lisp` just the OS-entropy surface (`*prng*`, `make-prng`, `random-data`, `random-bits`, `strong-random`) over `rontolisp:random-bytes`, so a client nonce or a default salt is cryptographically strong on every backend — but `:fortuna` and the seed-file operations are absent |
| [uax-15](https://github.com/sabracrolleton/uax-15) v0.1.3 | all four | Chris Bagley's and Sabra Crolleton's Unicode normalization (UAX #15), loaded from its real unmodified sources. `normalize` in all four forms (`:nfc`/`:nfd`/`:nfkc`/`:nfkd`) — canonical and compatibility decomposition, combining-class reordering, Hangul jamo composition — plus `get-canonical-combining-class-map`, `get-illegal-char-list` and `unicode-letter-p`. It is the one entry with dependencies of its own, so **`--system-path` needs three directories** (uax-15, split-sequence and cl-ppcre, joined with `:`) | the library builds its tables at load time by parsing 2.7 MB of bundled Unicode text through cl-ppcre, which cost minutes on the interpreter and ~30 s per run on the WASM backends; rontolisp instead **derives the same tables from the same bundled files** at compile/load time, emits them as data, and **builds each one only when it is first read**, leaving every normalization function verbatim upstream. So loading the system is nearly free and a program that never normalizes never pays for a table at all; a program that does pays for the tables that form needs, once, at its first call. The table contents are identical, with one deliberate difference that is a fix: `(uax-15:unicode-letter-p #\A)` answers `T`, where the real load answers `NIL` for every character outside nine hardcoded CJK/Hangul/Tangut ranges (upstream's letter loop reads `#+utf-32`, and a file's own `pushnew` onto `*features*` never reaches the reader, so its key computation collapses to `nil`). Separately, `uax-15:get-mapping` signals on every backend — it coerces the decomposition maps' integer keys with `string`, which is a type error in Common Lisp too, so it is broken upstream and nothing calls it |

cl-ppcre's load drove the widest feature batch so far — local
`(declare (special ...))`, CLOS slot accessors as generics,
`initialize-instance :after`, `&environment` + `get-setf-expansion`, `psetf`,
`(setf (subseq ...))`, `subst`/`search`/`copy-tree` and the
descending/case-insensitive character comparisons.

uax-15's load drove the second widest: compile-time folding of the ASDF/UIOP
pathname primitives, inlining a bundled data file read with `with-open-file`
into the artifact, a per-clause rewrite of the `LOOP` macro, and the UTF-8 byte
model behind WASM GC strings.

Runnable demos for all eleven — with the per-backend commands and
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
