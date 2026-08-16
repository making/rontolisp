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
  `asdf:`-qualified), `in-package`/`defpackage` forms (skipped),
  `register-system-packages` forms (which record "this package lives in that
  system" — read when a package-inferred system turns a `defpackage`
  dependency into a system name, and otherwise inert, since a package is
  found through its own `defpackage`), and top-level
  `defparameter`s of pure literal/conditional values (evaluated into a
  parse-time environment) may appear. `#+`/`#-` feature conditionals work
  (evaluated against the target backend's features, see
  [Data Types](../reference/data-types.md#comments-feature-conditionals-and-features)),
  a `#.` read-time-eval form is resolved where its value is **used**: in a
  clause that decides what gets loaded it is resolved against those
  `defparameter`s (the `(:file #.*string-file*)` idiom), and an unresolvable
  one is an error naming the file and the clause; in ignored metadata
  (`:long-description`, `:version`, `:perform`, …) and at top level (an ASDF
  version guard) it is dropped unevaluated and unremarked. A `:depends-on`
  entry may be
  `(:feature EXPR DEP)`, contributing its dependency only when the feature
  expression holds, or `(:version NAME "1.2.3")`, resolving to the plain
  dependency (the version constraint is not checked — the `:version` option is
  ignored metadata here, so there is nothing to check against). A top-level
  `(defmethod perform ...)` hook is tolerated and ignored (there is no
  `operate` machinery for it to run on; any other method name is an error),
  and a top-level `defclass` whose superclasses are documentation component
  classes (ASDF's `doc-file`, or one declared earlier in the same file)
  declares its name as a component type whose entries participate in ordering
  but contribute no source, like `:static-file` — `:doc-file` and `:html-file`
  work without a `defclass`. Any other top-level form is an error naming the
  file.
- A `.asd` may **announce features**: a top-level
  `(eval-when (:load-toplevel :execute) (pushnew :my-feature *features*))`
  (or a bare `pushnew`/`push`) before a `defsystem` declares that feature for
  every system defined after it in the file — the same effect as writing
  `:rontolisp-features (:my-feature)` on those systems. The declaration reaches
  the system's own `:if-feature` / `(:feature ...)` clauses and the reading of
  its component files — carrying the announcement out of the `.asd`, which is
  the half the reader cannot do for itself. (A `#+`/`#-` in the **same** `.asd`
  sees the push too, but through the reader's own handling of a source's
  announcement — see
  [Data Types](../reference/data-types.md#comments-feature-conditionals-and-features).)
  It does not reach a dependency, which declares its own. An `eval-when` whose situations are only
  `(:compile-toplevel)` is inert (ASDF loads a `.asd`, it never compiles one),
  and any other form inside the `eval-when` is an error naming it.
- `defsystem` supports the metadata options (ignored), `:depends-on`,
  `:serial`, `:pathname` (a literal directory prefixed to every component, so
  a system whose sources live in `src/` can name them bare) and `:components`
  with `:file`/`:module`/`:static-file` entries;
  a component may carry `:if-feature expr`, which drops the component's files
  when the feature expression does not hold (how libraries gate CLOS-only
  files behind `(:or :sbcl ...)`) while keeping its place in the dependency
  order. The test-op wiring options are the one op with machinery behind
  them: `:in-order-to ((test-op (test-op ...)))` and
  `:perform (test-op (o c) ...)` are recorded and driven by
  [`asdf:test-system`](../reference/functions/asdf-test-system.md) (any other
  operation, a qualified `test-op :after` method, or a `#.` in the body stays
  tolerated and ignored — there is still no general `operate` machinery). A
  `:version` value may be any literal form including ASDF's
  `(:read-file-form ...)` indirection (never inspected). Anything else
  (`:defsystem-depends-on`, ...) is an error naming the clause.
- `:class :package-inferred-system` is supported — the style ningle, rove and
  array-operations use. Such a system has **no `:components` at all**: a
  sub-system name is a file path under the system's directory (`my-lib/main`
  is `main.lisp`, `my-lib/util/text` is `util/text.lisp`, both below
  `:pathname` when the system has one), and that file's own **`defpackage`**
  names its dependencies — every package in `:use`, `:mix`,
  `:reexport`, `:use-reexport` and `:mix-reexport`, plus the first argument of
  each `:import-from` / `:shadowing-import-from`. A package name becomes a
  system name: what a `register-system-packages` form declared, otherwise the
  downcased package name itself (`cl` and friends drop out). Only the forms up
  to the first package definition form of each file are read, so the common
  `(in-package #:cl-user)` header before the `defpackage` is fine; a file with
  no `defpackage`/`uiop:define-package` at all is an error. No other `:class` is supported, and a
  package-inferred system that also lists `:components` is an error.
- Loading a system twice is a no-op; circular `:depends-on` chains are
  detected and reported — including one written as a cycle between two
  sub-systems' `defpackage` forms.
- The compile path requires a literal, top-level `(asdf:load-system NAME)`;
  the interpreter also accepts a computed name at runtime. Both accept and
  ignore trailing keyword options (`:verbose nil`, `:force t`, `:silent t`),
  which real libraries pass when they load a system at runtime. A nested or
  computed `load-system`/`ql:quickload` in a compiled program answers `nil`
  when the system was already spliced and signals otherwise — nothing can be
  loaded at run time there.
- **The component metaobjects are real at run time.**
  [`asdf:find-system`](../reference/functions/asdf-find-system.md) answers a
  memoized CLOS instance per system (`eq` across calls) over real classes —
  `asdf:component`, `asdf:child-component`/`asdf:parent-component`,
  `asdf:module`, `asdf:system`, `asdf:package-inferred-system`,
  `asdf:source-file`, `asdf:cl-source-file`, `asdf:static-file` — so `typep`,
  `typecase` and `defmethod` specializers over them work on every backend. The
  readers [`asdf:component-name`](../reference/functions/asdf-component-name.md),
  [`asdf:component-pathname`](../reference/functions/asdf-component-pathname.md),
  [`asdf:component-children`](../reference/functions/asdf-component-children.md)
  (one `cl-source-file` per component file, in load order),
  [`asdf:component-sideway-dependencies`](../reference/functions/asdf-component-sideway-dependencies.md),
  [`asdf:component-parent`](../reference/functions/asdf-component-parent.md) and
  [`asdf:component-system`](../reference/functions/asdf-component-system.md)
  walk the model;
  [`asdf:registered-systems`](../reference/functions/asdf-registered-systems.md)
  lists every registered name, and `asdf:*user-cache*` is external and `nil`
  (there is no fasl cache). This is the component model rove's system-driven
  test runner reads.
- **`asdf:test-system` runs the recorded test-op wiring.**
  [`asdf:test-system`](../reference/functions/asdf-test-system.md) loads the
  system, follows its `:in-order-to` test-op chain, and runs each recorded
  `:perform (test-op (o c) ...)` body with the component bound to the system
  metaobject. On the compile paths a literal top-level call splices the test
  systems too.
- **Compiling tree-shakes the system.** A function, variable or constant a
  loaded system defines but your program never reaches — following names
  through the source, including quoted symbols and whole string literals — is
  left out of the `.class`/`.wasm` — classes, generic functions, methods,
  conditions and structures included (a method also leaves when no reachable
  code can create an instance it applies to). Compile with `--no-prune` (or
  `--dynamic`) to keep every definition; see
  [Compiling to the JVM](../compiling/jvm.md) for the one consequence.

- **The load-context variables hold the file being loaded, on every backend.**
  While a file is being loaded, `*load-pathname*` holds the path `load` was
  called with and `*load-truename*` the path it resolved to; the enclosing
  file's values come back when it finishes, and outside a load both are `nil` —
  read at top level or from a function the load defined and your program calls
  later. **A component is loaded by its resolved path**, so both variables hold
  what
  [`asdf:component-pathname`](../reference/functions/asdf-component-pathname.md)
  answers for it, which is what lets a test framework correlate the definitions
  of a file with the system that owns it. This works the same on the compile
  backends: a spliced file's forms are compiled with its own load context,
  because nothing is being loaded at run time there for them to read otherwise.
  `*compile-file-pathname*` and `*compile-file-truename*` are always `nil` —
  there is no `compile-file` here. Libraries use the `(or
  *compile-file-truename* *load-truename*)` idiom to find data files beside
  their own sources.

## Built-in shim systems

Some Quicklisp libraries depend on per-implementation portability layers that
cannot know rontolisp from their side. rontolisp ships those as **built-in
ASDF systems**: `asdf:load-system`/`ql:quickload` (and a `:depends-on` from a
real library) resolve the name to a bundled shim instead of downloading it.

| System | What the shim provides |
|--------|------------------------|
| `usocket` | the socket API over `rontolisp:tcp-*` (see the [TCP guide](tcp-sockets.md#the-usocket-compatible-shim)) |
| `trivial-gray-streams` | the portable Gray-stream classes/generics (base-class hierarchy incl. the binary/input classes and `trivial-gray-stream-mixin`, the read/write/sequence generics, `stream-file-position` + its `(setf ...)` writer), adapting onto rontolisp's own protocol — what the stream-taking built-ins dispatch to for a CLOS instance stream (see [Gray Streams](gray-streams.md)) |
| `closer-mop` | the class-introspection readers over the real class metaobjects ([`find-class`](../reference/functions/find-class.md) / [`class-of`](../reference/functions/class-of.md) answers): `classp`, `class-slots`, `slot-definition-name`/`-initargs`/`-type`/`-readers`/`-initfunction`, `class-name`, `class-direct-superclasses`, `class-direct-slots`, `class-direct-subclasses`, `class-finalized-p`, `ensure-finalized`. A legacy tag-symbol designator still answers `(name declared-type)` pairs. The flat `closer-common-lisp` re-export package (nickname `c2cl`: all of `cl` overlaid with these, closer-mop winning collisions) is always registered, so a `(:use :closer-common-lisp)` package works |
| `flexi-streams` | pass-through streams (a flexi stream IS the underlying stream) |
| `babel` | the UTF-8 codec: `babel:string-to-octets`/`octets-to-string` (with `:start`/`:end`/`:errorp`), `babel:string-size-in-octets`, `babel-encodings:*default-character-encoding*` (`:utf-8`) and `babel:list-character-encodings`. Real babel generates 40+ code pages from 20,000 lines of tables; rontolisp has one character model (a character IS a Unicode code point, the wire form is UTF-8), so the shim implements that codec, treats the `:latin-1`/`:us-ascii` aliases as the code-point identity they are for the octets they can represent, and **signals on any other `:encoding`** rather than handing back mis-coded bytes |
| `float-features` | `single-float-bits`/`bits-single-float` and the double variants over the IEEE 754 bit primitives (interpreter + JVM; the WASM numeric model cannot carry 64-bit bit patterns) |
| `bordeaux-threads` (nicknames `bt` and `bt2`) | both API namespaces of the one shim. The locking subset — `make-lock`, `acquire-lock`, `release-lock`, `with-lock-held`, `*supports-threads-p*` — rides [`rontolisp:make-mutex`](../reference/functions/rontolisp-make-mutex.md) and friends; thread creation — `bt2:make-thread` (with `:initial-bindings`), `join-thread`, `threadp`, `thread-alive-p`, `destroy-thread` — rides [`rontolisp:make-thread`](../reference/functions/rontolisp-make-thread.md), a real virtual thread on the interpreter and the JVM. On the single-threaded WASM backends the thread entry points signal at call time and `bt:*supports-threads-p*` is `nil`. `make-lock` returns a reentrant lock (upstream's is not), `acquire-lock`'s `:wait-p` is ignored — the acquisition always blocks — and an `:initial-bindings` value form must be a quote form or self-evaluating (anything else would need the new thread's dynamic environment and signals) |
| `uiop` | ASDF's portability layer, registered as 15 sub-packages and 429 exports. See **[The uiop Package](../reference/uiop.md)** for what is implemented; every other export resolves and signals `uiop:not-implemented-error` naming the operation, so a library that merely names one still loads |
| `swank` | a stub, and only so that a library depending on it can load: `swank:create-server` signals ("rontolisp cannot serve a remote REPL") and `swank:stop-server` is a `nil` no-op. Real swank is SLIME's server half, whose own `.asd` is a program the defsystem-as-data front-end cannot read -- without the stub, `(ql:quickload "clack")` fetches the SLIME tarball and dies on it |
| `mgl-pax-bootstrap` | the `mgl-pax` package (nickname `pax`) as a stub, so a library documented with [mgl-pax](https://github.com/melisgl/mgl-pax) can load (trivial-utf-8, a uuid dependency, hard-depends on it; the real system's `.asd` uses `:defsystem-depends-on`). `pax:define-package` acts as `defpackage`, `pax:defsection` defines its section name as a `nil` variable **and exports the section's `(symbol locative)` entries** — mgl-pax's documented default, and how such libraries export their public API — and the PAX-World registration helpers are `nil` no-ops. No documentation is generated |
| `trivial-garbage` (nickname `tg`) | GC finalizers as honest no-ops: `tg:finalize` registers nothing and returns the object, `tg:cancel-finalization` is a `nil` no-op. No backend exposes GC hooks — and Common Lisp gives finalizers no guarantee of ever running, so a conforming consumer must already work when they never fire. Practical consequence for `dbd-postgres` (its consumer): a leaked prepared statement lives until the connection closes; call `dbi:disconnect` explicitly |
| `clack-handler-rontolisp` | the [Clack](https://github.com/fukamachi/clack) handler backend: package `clack.handler.rontolisp` exporting `run`/`stop`, bridging the Clack application protocol onto rontolisp's embedded HTTP server. You never load it by hand — `(clack:clackup app :server :rontolisp)` resolves it by name at run time (the system also answers to the dotted spelling `clack.handler.rontolisp` that clack derives from the package name). See the [Clack guide](clack.md) |
| `clack-handler-reactor` | the Clack handler backend for a **host-driven reactor**: a Cloudflare Worker, a browser page, a node or JVM embedding — any host that has already parsed the request and calls an exported function instead of handing the program a socket. Package `clack.handler.reactor` exports `run`/`stop` and, under them, `handle` (an application and a JSON request string in, a JSON response string out) and `dispatch` (the same over the application `clackup` stored). Resolved by `(clack:clackup app :server :reactor)`, the dotted spelling included, exactly like the backend above. See the [Clack guide](clack.md#driving-the-reactor-by-hand-clack-handler-reactor) |

The shims are deliberately thin: they satisfy what the loadable libraries
actually call, not the full upstream APIs.

## What can I actually load?

The real-world libraries below load unmodified today. The **Backends** column says
where each one is verified — "all four" means the interpreter, the JVM, WASM
Preview 1 and `--component`. **Notes** covers what is special about the load and
what does not work.

| Library | Backends | Notes |
|---------|----------|-------|
| [alexandria](https://gitlab.common-lisp.net/alexandria/alexandria) 1.0.1 | all four | The ecosystem's most-depended-on utility library, both packages (`alexandria`/`alexandria-1` and `alexandria-2`), from its real sources. Every library below with dependencies pulls it in. Absent are the members standing on a primitive that is still missing: `type=` (`subtypep`'s second value). `format-symbol`/`ensure-symbol` and `ensure-function` on a **symbol** work on the interpreter only (a compile-backend error, not a wrong answer). `shuffle`/`random-elt`/`gaussian-random` draw each backend's own entropy, so their output is not comparable across backends |
| [split-sequence](https://github.com/sharplispers/split-sequence) v2.0.1 | all four | The whole API on strings and lists, including the second return value (the resume index). Its CLOS-only `extended-sequence.lisp` is gated behind `:if-feature (:or :sbcl :abcl)` and drops out automatically |
| [parse-number](https://github.com/sharplispers/parse-number) v1.8 | all four | The whole API over integers, ratios, floats, radix-prefixed literals (`#xFF`, `#3r12`) and exponent markers; the `invalid-number` condition signals with the intended diagnostics |
| [cl-utilities](https://common-lisp.net/project/cl-utilities/) v1.2.4 | all four | The whole public API — its own `split-sequence`, the `extremum` family, `read-delimited`, `expt-mod`, `collecting`/`with-collectors`, `with-unique-names`/`with-gensyms`/`once-only` (three-level nested backquote) usable from your own macros, `rotate-byte`, `copy-array`, `compose` |
| [cl-who](https://edicl.github.io/cl-who/) v1.1.5 | all four | (X)HTML generation macros — `with-html-output(-to-string)` with attributes, nested tags and the local `str`/`esc`/`fmt`/`htm` operators; `:xml` and `:html5` both render correctly. **`:indent` (pretty-printed output) is unsupported**, and the output mode must be switched with **`(setf (html-mode) :html5)`**: cl-who reads it at macro-expansion time, so a runtime `let` on `*html-mode*` is not observed |
| [assoc-utils](https://github.com/fukamachi/assoc-utils) | all four | Alist utilities, whole API — `aget` (settable), the alist/plist/hash conversions, `remove-from-alist`/`delete-from-alistf`, `with-keys`, `alist-get`, `alist=`, `alistp` |
| [cl-base64](https://github.com/darabi/cl-base64) v3.4 | all four | Base64 over strings, `(unsigned-byte 8)` arrays and integers, with `:columns` wrapping and the `:uri` alphabet; a bad input character signals `bad-base64-character`. That condition's `:input`/`:position`/`:code` slots are readable on the interpreter only — the compiled backends signal a plain condition, caught by the same `handler-case` |
| [md5](https://github.com/pmai/md5) v2.0.4 | all four | MD5 (RFC 1321) — `md5sum-sequence`/`md5sum-string` and the incremental API, matching the RFC test vectors identically on all four backends |
| [chipz](https://github.com/froydnj/chipz) 0.8 | all four | Decompression — `chipz:decompress` for the `gzip`, `zlib` and `deflate` formats (to a fresh vector, into a supplied one, or incrementally through `make-dstate`), plus the CRC32/Adler-32 checksum entry points. The inflate state machine is a `labels` whose transitions store `#'local-function` in a struct slot, and it exits through `catch`/`throw`, so a compiled artifact is always in EH mode (`-W exceptions=y` on both WASM backends). bzip2 loads with it — `decompress`'s own `typecase` names `bzip2-state` — but is untested here. [`size-report/programs/zlib`](https://github.com/making/rontolisp/tree/develop/size-report/programs/zlib) is built on it |
| [cl-ppcre](https://github.com/edicl/cl-ppcre) v2.1.2 | all four | Perl-compatible regular expressions from its real sources — `scan`, `scan-to-strings`, `split`, `regex-replace(-all)`, `all-matches`, `count-matches`, the `do-scans`/`do-matches` macros, `register-groups-bind`, `quote-meta-chars`, parse-tree regexes and inline modifiers like `(?i)` |
| [com.inuoe.jzon](https://github.com/Zulu-Inuoe/jzon) v1.1.4 | all four | JSON parsing and stringification including the README walkthrough — hash-table / vector round-trips, `:key-fn`, the Gray-stream `:stream` writer, `jzon:writer`, CLOS-instance stringification; its dependencies resolve to the built-in shim systems above. Its three numeric leaf components (the eisel-lemire reader and Schubfach printer) are replaced by shims over rontolisp's own float arithmetic, so float text takes rontolisp's cross-backend-identical shape and an extreme exponent can be a few ulps off (a decimal exponent of magnitude 22 or less rounds exactly). The usual WASM caveats apply: float print shape, hash-table iteration order, non-ASCII `\u` escapes |
| [ironclad](https://github.com/sharplispers/ironclad) v0.61 (the SHA-256 / HMAC / PBKDF2 / HKDF / SCRAM slice) | all four | From its real sources, reproducing the published FIPS 180-2, RFC 4231, RFC 5869 and RFC 7677 vectors — including SCRAM-SHA-256's client proof end to end, the sequence a PostgreSQL client authenticates with. Only that slice loads (its own `.asd` is an executable program, so a bundled replacement declares the slice): **ciphers, public-key operations, AEAD modes and the other digests are absent**, and requesting one signals at the call. `prng.lisp` is narrowed to the OS-entropy surface over `rontolisp:random-bytes` — nonces and default salts are cryptographically strong everywhere, but `:fortuna` and the seed-file operations are gone |
| [uax-15](https://github.com/sabracrolleton/uax-15) v0.1.3 | all four | Unicode normalization (UAX #15) in all four forms from its real sources; **`--system-path` needs three directories** (uax-15, split-sequence, cl-ppcre, joined with `:`). Upstream builds its tables by parsing 2.7 MB of bundled Unicode text at load time (minutes interpreted); rontolisp derives the same tables from the same files at compile/load time and builds each one on first read, leaving every normalization function verbatim — so loading is nearly free and a program that never normalizes pays nothing. One deliberate difference, and it is a fix: `(unicode-letter-p #\A)` answers `T` where the real load answers `NIL` (upstream's loop reads `#+utf-32`). `get-mapping` signals on every backend — it is broken upstream and nothing calls it |
| [quri](https://github.com/fukamachi/quri) v0.7.0 | all four | URI library from its real sources via `(ql:quickload "quri")` — parsing into the scheme-specific structs, the accessors, `render-uri`, `merge-uris`, `uri-query-params`, percent-encoding, the public-suffix API and the address predicates. Its `babel` dependency resolves to the built-in UTF-8 shim, so a non-UTF-8 `:encoding` signals; the effective-TLD tables build on first read from the bundled 152 KB list, so `(load-etld-data OTHER-FILE)` reads that list rather than `OTHER-FILE`. `:lenient` percent-decoding skips a bad escape with a `go` out of a `handler-bind` handler, which the compile backends lower to a non-local exit, so it answers the same on all four. Needs alexandria, split-sequence, cl-utilities and idna on `--system-path` |
| [local-time](https://github.com/dlowe-net/local-time) v1.0.6 | all four | Date/time library from its real sources via `(ql:quickload "local-time")` — `encode-timestamp`/`decode-timestamp`, `now`/`today`, the unix and universal-time conversions, `parse-timestring`, `format-timestring` over every bundled format (ISO 8601, RFC 3339, RFC 1123, asctime, ISO week date) and custom format lists, the comparison family, `timestamp+`/`timestamp-`/`adjust-timestamp`/`timestamp-minimize-part`, the julian-date pair and `print-object`. Its only dependency is the built-in `uiop`. **Real TZif zone files load wherever the host has a filesystem** — `(local-time:define-timezone tokyo #p"/usr/share/zoneinfo/Asia/Tokyo" :load t)` — and the load-time `/etc/localtime` read that seeds `*default-timezone*` works the same way, falling back to `+utc-zone+` where the file cannot be read (which is what the WASM backends do without `--dir`). **`reread-timezone-repository` walks the bundled `zoneinfo/` tree on all four backends** now that `directory` exists, so `find-timezone-by-location-name` resolves `"Asia/Tokyo"` and friends; on the compiled backends pass the repository explicitly (`(local-time:reread-timezone-repository :timezone-repository "zoneinfo/")`) because its default is computed at load time from `asdf:component-pathname` through a run-time `eval`, with `*load-truename*` as the fallback — neither of which the compiled backends can answer, so the default is `nil` there |
| [trivia](https://github.com/guicho271828/trivia) (the `trivia.trivial` route) | all four | Optima-compatible pattern matching from its real sources via `(ql:quickload "trivia")` — `match`/`match*`/`ematch` (failure signals `match-error`), constant / variable / `cons` / `list` / `list*` / `vector` patterns, `guard`, `or`/`and`/`not` patterns, `defpattern`, struct patterns (keyword and conc-name shapes), class patterns (keyword slot and `(class name (slot var))` shapes) and `(type spec)` patterns. System `trivia` is mapped to `trivia.trivial` — upstream's own base system for extensions — so clauses run under the `:trivial` optimizer: identical semantics, no balland2006 clause optimization (which would need `iterate` + `type-i`). Its dependencies (alexandria, lisp-namespace, the closer-mop / trivial-cltl2 shims) load with it. Note the interpreter re-expands macros per evaluation, so a hot `match` loop belongs on a compiled backend |
| [sxql](https://github.com/fukamachi/sxql) | all four | SQL generator from its verbatim sources via `(ql:quickload "sxql")` — `sxql:yield` returns the SQL string plus the bind-value list as multiple values, byte-identically on every backend (and identically to SBCL on the same sources): `select` with `from`/`where` (incl. `:and`/`:or`/`:in`/`:like`), `order-by` (`:desc`, `nulls`), `limit`/`offset`, `left-join ... :on`, `insert-into` with `set=`, `update`, `delete-from`, `create-table` with column options (the mito `deftable` shape), `drop-table` and `alter-table`. Its dependencies (trivia via the `trivia.trivial` route, alexandria, cl-package-locks — the last a no-op-shaped lock library) load with it. Like every macro-heavy library, hot query construction belongs on a compiled backend (the interpreter re-expands macros per evaluation). The [O/R mapping guide](mito.md) walks through `yield` and the statement builders |
| [esrap](https://github.com/scymtym/esrap) 0.19 | all four | Packrat / PEG parser from its verbatim sources via `(ql:quickload "esrap")` — `esrap:parse` over an inline expression or a named rule, `defrule` with `:lambda` / `:destructure` / `:text` transforms, `add-rule` / `make-instance 'esrap:rule`, case-insensitive `(~ "lit")` terminals, `and` / `or` / `not` / `*` / `+` / `?` sequencing, semantic predicates (`(oddp decimal)`), `:junk-allowed`, and the accurate parse-error report (`esrap:esrap-parse-error`, whose text is byte-identical to SBCL's apart from SBCL's non-standard Unicode character NAMES). The parser is pure computation, so **Preview 1 WASM is in** — no sockets, no flags beyond `-W gc`. Its dependencies (alexandria, trivial-with-current-source-form) load with it. `esrap:trace-rule` needs `break`, which does not exist here, and the swank indentation hook needs `set`; both are dead unless called |
| [postmodern](https://github.com/marijnh/Postmodern) v1.33.12 (the MOP build) | interpreter, JVM, WASM component | PostgreSQL stack — s-sql included — from its verbatim upstream sources via `(ql:quickload "postmodern")`: `with-connection`/`connect` and the pool, `query`/`execute` over S-SQL forms or strings in every result style, `doquery`, prepared statements with the `:reconnect`/`reset-prepared-statement` restarts, transactions and savepoints, `execute-file`, `deftable`, and `:postmodern-thread-safe` ON so its locks really serialize. The **DAO layer is in**: the build takes `:postmodern-use-mop` ON, so `table.lisp` loads verbatim over the static metaobject subset — `(defclass ... (:metaclass pomo:dao-class))` with `:col-type`/`:keys`/`:table-name`, `dao-table-definition`, `deftable`'s `!dao-def`, `insert-dao`/`get-dao`/`update-dao`/`upsert-dao`/`delete-dao`/`save-dao`/`select-dao`/`query-dao` and `make-dao`. The metaclass protocol runs at DEFINITION time, so DAO classes must be top-level `defclass` forms with literal options (classes built from runtime data signal), and `finalize-inheritance` runs eagerly at class definition rather than at first use — definition errors surface earlier, results are unchanged. Connecting needs cl-postgres' socket layer, so **Preview 1 WASM is out**; both wasm run commands need `-W exceptions=y` and a `--component` one additionally `-S tcp=y -S inherit-network=y`. The s-sql layer alone (`(ql:quickload "s-sql")`) opens no sockets and renders identical SQL on all four backends |
| [clack](https://github.com/fukamachi/clack) v2.1.0 (with [lack](https://github.com/fukamachi/lack)) | interpreter, JVM, WASM component | Web application environment from its verbatim upstream sources via `(ql:quickload "clack")`, served by the built-in `clack-handler-rontolisp` backend — see the [Clack guide](clack.md). The lack side loads too: `lack:builder`, `lack-util`'s `generate-random-id` (over the ironclad slice) and the backtrace middleware, which `clackup`'s default `:use-default-middlewares t` exercises end to end. `clackup`'s default `:use-thread t` runs the acceptor on a real thread ([`rontolisp:make-thread`](../reference/functions/rontolisp-make-thread.md)) on the interpreter and the JVM; the WASM component serves under `wasmtime serve` instead (the host owns the socket). Preview 1 WASM has no incoming TCP by design, so `clackup` signals at call time there |
| [tiny-routes](https://github.com/jeko2000/tiny-routes) v0.1.1 | all four | A routing layer for Clack applications, from its verbatim sources via `(ql:quickload "tiny-routes")` — the piece between `clack:clackup` and an application with routes. `define-get`/`define-post`/`define-put`/`define-delete`/`define-any`/`define-route` and `define-routes`, the `:id`-style path template (and a regex one with `:regex t`) over cl-ppcre, `path-parameter`, `with-request`/`with-path-parameters`, the `pipe` middleware combinator with `wrap-request-body` (the Clack `:raw-body` stream), `wrap-query-parameters`, `wrap-request-predicate`/`-mapper`, the response wrappers and the whole `ok`/`created`/`not-found`/… constructor set. Its companion system `tiny-routes-middleware-cookie` loads too (`parse-cookie-header`, `write-set-cookie-header`, `wrap-request-cookies`, `wrap-response-cookies`), pulling in cl-cookie, quri, local-time and proc-parse. Routing itself is pure computation, so **all four backends are in**; SERVING the routes needs `clackup`, which rules Preview 1 out — see the [Clack guide](clack.md). Its only dependency is cl-ppcre, so `--system-path` needs two directories when you load it from disk. The test system needs fiveam, which does not load. For a size-constrained compiled module there is a ppcre-free **opt-in**, `tiny-routes/lite` — the subsection right below |
| [ningle](https://github.com/fukamachi/ningle) v0.3.0 | all four | The "super micro framework" over Clack, from its verbatim sources via `(ql:quickload "ningle")` — the second routing layer here, and a genuinely different model from tiny-routes rather than another spelling. The application is a CLOS OBJECT (`(make-instance 'ningle:app)`, a `lack-component`), every route is a `setf` (`(setf (ningle:route app "/x") controller)`), a controller receives the matched PARAMETERS rather than the environment, and a controller that is not a function is answered as the response body. Path templates with `:name` tokens and `*` splats, `:regexp t` routes, `:method`, `:accept` content negotiation and user-defined requirements (`(setf (ningle:requirement app :key) fn)`) — a route can therefore be selected by something that is not the path at all — plus the `ningle:*request*`/`*response*`/`*session*` specials, `ningle:context` and `with-context-variables`, `ningle:next-route`, and `ningle:not-found`, the overridable 404 method. Its router [myway](https://github.com/fukamachi/myway) and myway's `map-set` load with it, as does the whole lack request chain it reads every request through (http-body, fast-http, smart-buffer, circular-streams, quri, yason, trivial-mimes) — which is why a compiled ningle module is an order of magnitude larger than the same routes through tiny-routes, and there is **no size opt-in** to offer: myway compiles every rule to a cl-ppcre scanner, so the regex engine is genuinely reachable. Routing itself is pure computation, so **all four backends are in**; SERVING the routes needs `clackup`, which rules Preview 1 out — see the [Clack guide](clack.md) |
| [cl-dbi](https://github.com/fukamachi/cl-dbi) 0.11.1 (`dbd-postgres` only) | interpreter, JVM, WASM component | Database-independent interface from its verbatim sources via `(ql:quickload "dbd-postgres")`: `dbi:connect` (the driver resolves over the already-loaded system — a compiled program must contain the `ql:quickload` itself, since it cannot load a system at run time), `dbi:do-sql`, `dbi:prepare`/`execute`/`fetch`/`fetch-all`, `dbi:with-transaction` (commit and rollback), `dbi:connect-cached` and `dbi:disconnect`. The `:mysql` and `:sqlite3` drivers need FFI and are absent. On the thread-capable backends the connection cache is per-thread (`cache/thread.lisp` over the `bt2` shim's real locks and [`rontolisp:current-thread`](../reference/functions/rontolisp-current-thread.md)); the single-threaded WASM backends use upstream's own threadless cache. Its `trivial-garbage` dependency resolves to the no-op finalizer shim above, so call `dbi:disconnect` explicitly. Same socket constraints as postmodern: Preview 1 WASM is out, a component needs `-W exceptions=y -S tcp=y -S inherit-network=y` |
| [mito](https://github.com/fukamachi/mito) 0.2.0 | interpreter, JVM, WASM component | O/R mapper from its verbatim sources via `(ql:quickload "mito")` — the **full** system (`mito-core` + `mito-migration` + `lack-middleware-mito`), covered by the [O/R mapping guide](mito.md). The DAO layer: `connect-toplevel`/`disconnect-toplevel`, `deftable` (the `dao-table-class` metaclass over the static metaobject protocol — auto-pk `:serial` and `:uuid`, `record-timestamps-mixin`'s `created-at`/`updated-at`), `table-definition`, `ensure-table-exists`, `create-dao`/`insert-dao`/`save-dao`/`delete-dao`, `find-dao`, `select-dao` with sxql clauses, `object-id`, `retrieve-by-sql` and `execute-sql`. The migration layer: `migration-expressions` and `migrate-table` diff a class against the live schema on all three backends, while `generate-migrations` / `migrate` over migration FILES are interpreter + JVM (the WASM backends import no directory-creation or file-removal call, so they signal at the call); `migrate` re-reads the generated `.sql` with esrap, and the advisory lock rides a CRC32-only slice of chipz. PostgreSQL only (`dbd-postgres`, which must be quickloaded explicitly); like every metaclass consumer, `deftable` forms must be top level with literal options. Known gaps, all in the guide: the `:conc-name` accessors are not generated (`slot-value` works), and sxql's SQL FUNCTION operators — `(:count ...)` and therefore `mito:count-dao` — are interpreter-only. Two shapes fail identically on SBCL and are upstream defects, not gaps: a bare `:references` without a `:col-type`, and adding a NOT NULL column with an `:initform`. The uuid dependency loads (its v1/v4 generation draws the backend's own entropy) and `dissect`'s stack introspection is the no-op interface. Same socket constraints as cl-dbi: Preview 1 WASM is out, a component needs `-W exceptions=y -S tcp=y -S inherit-network=y` |
| [rove](https://github.com/fukamachi/rove) v0.10.0 | all four | Testing framework from its verbatim sources via `(ql:quickload "rove")`, covered by the [testing guide](testing.md) — `deftest`/`testing`/`ok`/`ng`/`signals`/`outputs`/`expands`/`pass`/`fail`/`skip`/`failing`/`setup`/`teardown`/`defhook`/`diag` with the `:spec` (default) and `:dot` reporters, and every entry point: `rove:run` over a `:package-inferred-system` or a plain `defsystem` test system, `run-test`/`run-tests`, and `run-suite` at the end of a test file. A test body that signals becomes a recorded failure instead of ending the run (on the WASM backends a raw trap — `(car 1)`, `(/ 1 0)` — still ends it), and `rontolisp test TARGET` (or `(uiop:quit (if (rove:run ...) 0 1))` inside your own runner) turns the result into a CI exit code on every backend. Its dissect dependency loads from its real sources with the stack introspection empty, so failure reports carry no backtraces, and assertion descriptions print symbols package-qualified where SBCL prints them bare — details and the run commands are in the guide |

### The size opt-in: `tiny-routes/lite`

`(ql:quickload "tiny-routes/lite")` loads the same tiny-routes tree with one
component substituted — `path-template.lisp`, whose matcher upstream is a
cl-ppcre scanner — and the `:cl-ppcre` dependency dropped with it. It exists
because routing keeps the regex engine **live**: a route template compiles to
a scanner when the route is *built*, so in a compiled module no amount of
tree-shaking can remove cl-ppcre, and on a size-limited target that is most of
the module — the
[routed Worker example](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/httpbin-tiny-routes)
measures 974,530 B raw with the full system and 408,448 B with the lite
one, same routes, same answers, request for request.

The substitution never changes what a template *matches* — it matches
identically to the full system, or it refuses loudly when the route is built:

- **Accepted**: templates made of literal characters and `:name` tokens — a
  token is `:` followed by a letter or `_`, continuing over letters, digits,
  `_` and `-`, anywhere in the template: `/users/:id`, `/files/v:version`,
  `/pair/:a/:b`. Within this subset the lite matcher reproduces the full
  system's semantics exactly, greedy backtracking and upstream's greedy
  token-*name* scan included (in `/a/:x-:y` the first token is named `x-`);
  the two engines are pinned template-for-template by the test suite.
- **Refused at route-build time**, with an error naming the escape: a
  template containing any regex metacharacter — `.` `\` `[` `]` `(` `)`
  `{` `}` `|` `^` `$` `*` `+` `?` — and every `:regex t` template. (A
  template with no `:name` token is never a regex upstream either — it is
  compared with `string=` — so metacharacters there are fine on both
  systems.)

Plain `(ql:quickload "tiny-routes")` is untouched — the verbatim library,
cl-ppcre included — and the two systems refuse to load into one program,
in either order (whichever loaded last would silently redefine the matcher).
`tiny-routes/lite` is not in the Quicklisp index; the name downloads the
tiny-routes release and resolves against its `.asd`.

cl-ppcre's load drove the widest feature batch so far — local
`(declare (special ...))`, CLOS slot accessors as generics,
`initialize-instance :after`, `&environment` + `get-setf-expansion`, `psetf`,
`(setf (subseq ...))`, `subst`/`search`/`copy-tree` and the
descending/case-insensitive character comparisons.

uax-15's load drove the second widest: compile-time folding of the ASDF/UIOP
pathname primitives, inlining a bundled data file read with `with-open-file`
into the artifact, a per-clause rewrite of the `LOOP` macro, and the UTF-8 byte
model behind WASM GC strings.

alexandria's is the batch every other library inherits, because everything above
that has dependencies depends on it: `&whole` in `defmacro`/`destructuring-bind`,
a destructuring pattern after `&rest`/`&body` (`if-let`), `lambda-list-keywords`,
`do-external-symbols`, `intern` with a package designator, the hash-table
introspection readers (`hash-table-test`/`-size`/`-rehash-size`/`-rehash-threshold`),
`mismatch`, `arrayp`, `with-open-stream` and `#'open` as a first-class value —
plus, for `mappend`, `#'mapcar` as a value over more than one list.

Runnable demos for twelve of them — with the per-backend commands and
expected output — live in
[`examples/asdf/`](https://github.com/making/rontolisp/tree/develop/examples/asdf).

A library qualifies today roughly when it stays inside: plain
`defun`/`defmacro`/`defpackage` code, `loop`, `multiple-value-bind` over
`values`-tailed functions, `check-type`/`etypecase` with the supported type
specifiers, declarations (parsed no-ops, `deftype` included), the CLOS static
subset (`defclass`/`defgeneric`/`defmethod`/`make-instance`/`slot-value` with
single dispatch, plus `(defun (setf name) ...)` setf functions), the condition and
restart system (`define-condition`/`handler-case`/`handler-bind`/`restart-case`/
`invoke-restart`), `return-from`, and dynamic (special) variable binding (`let`/`let*` over a `defvar`
special). Libraries built on the full metaobject protocol or the interactive
debugger (`break`, `*debugger-hook*`) do not load yet (see
[Unsupported CL Features](missing-features.md)). For anything else, the
practical use is structuring **your own** multi-file rontolisp projects —
with `.asd` files that real ASDF can read too.
