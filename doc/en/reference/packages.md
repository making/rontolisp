# Packages

rontolisp has a small namespace (package) system with a set of built-in packages, plus [user-defined packages via `defpackage`](#user-defined-packages-defpackage):

- **`cl`** — the standard package. All built-in functions, macros, special forms and the `*package*` variable belong here.
- **`cl-user`** — the default working package. It *uses* `cl`, so standard symbols are available unqualified. The current package when a program starts. User definitions go here.
- **`rontolisp`** — a package for implementation-specific symbols. `rl` is a built-in nickname. It does **not** use `cl`. It owns the `version` function.
- **`linalg`** — numpy-style vector/matrix operations (`linalg:zeros`, `linalg:matmul`, `linalg:solve`, ...), implemented once in Lisp source and available in every backend. `la` is a built-in nickname. It does **not** use `cl`. See the [Vectors & Matrices guide](../guides/linear-algebra.md).
- **`torch`** — a PyTorch-style tensor with reverse-mode automatic differentiation and an `nn`-style module layer, the optimizers and the training-loop plumbing (`torch:tensor`, `torch:matmul`, `torch:backward`, `torch:linear`, `torch:cross-entropy-loss`, `torch:adam`, `torch:step`, ...) over the `linalg` kernels, implemented once in Lisp source and available in every backend. It does **not** use `cl`. See the [Neural Networks guide](../guides/neural-networks.md).
- **`geom`** — solid modeling over the `linalg` kernels: rigid transforms, a scene graph and boundary-represented solids with a cached triangle mesh (`geom:box`, `geom:cylinder`, `geom:attach`, `geom:mesh`, `geom:volume`, ...), implemented once in Lisp source and loaded on first use, like `linalg`. It reaches for nothing outside `linalg`, so it is available in every backend. It does **not** use `cl`. See the [Solid Modeling guide](../guides/solid-modeling.md).
- **`java`** — Java interop by reflection, usable only under the JVM interpreter (`java -jar rontolisp.jar`), not the compilers or the native binary. It does **not** use `cl`. It owns `new`, `call`, `static`, `field` and `proxy`; see the [Java interop guide](../guides/java-interop.md).
- **`objc`** — the Objective-C runtime and AppKit through the foreign function API, usable on the macOS interpreter (`java -jar rontolisp.jar` and the `rontolisp` native binary), not the compilers. It does **not** use `cl`. It owns `class`, `send`, `define-class`, `on-main`, `string`, `address` and `objectp`; see the [macOS GUI guide](../guides/objc-appkit.md).
- **`appkit`** — a Cocoa widget layer over `objc` (`appkit:window`, `appkit:label`, `appkit:button`, ...), implemented once in Lisp source and loaded on first use, like `linalg`. It does **not** use `cl`. Same guide.
- **`metal`** — a Metal drawing surface on an `appkit` window over `objc` (`metal:attach`, `metal:library`, `metal:pipeline`, `metal:buffer`, `metal:frame`, `metal:run`, ...): the layer, the device, the command queue and the render pass every Metal program writes identically. Implemented once in Lisp source and loaded on first use; macOS only, like `objc`, and it stands on its own — `geom` and `scene` are not needed to use it. It does **not** use `cl`. Same guide.
- **`scene`** — a 3-D viewer for `geom` solids over `metal` (`scene:viewer`, `scene:add`, `scene:fit`, `scene:camera`, `scene:animate`, ...): an orbit/pan/dolly camera, a ground grid, axis triads and an animation hook, with no per-triangle work in a frame. Implemented once in Lisp source and loaded on first use; macOS only, like `metal`. It does **not** use `cl`. See the [Solid Modeling guide](../guides/solid-modeling.md).
- **`asdf`** — a limited, API-compatible subset of ASDF (system definitions): `defsystem` and `load-system`. It does **not** use `cl`. See the [Systems guide](../guides/asdf-systems.md).
- **`ql`** — a limited, API-compatible subset of Quicklisp: `quickload` downloads a system from the real Quicklisp distribution and loads it through the `asdf` subset, and `update-dist` refreshes a dist's index. `quicklisp` is a built-in nickname. It does **not** use `cl`. See the [Systems guide](../guides/asdf-systems.md#downloading-with-quickload).
- **`ql-dist`** — Quicklisp's distribution machinery, of which the one member a program writes is `install-dist`: it adds another Quicklisp-format distribution ([Ultralisp](https://ultralisp.org/), or any distinfo URL) to the dists `ql:quickload` searches. It does **not** use `cl`. See the [Systems guide](../guides/asdf-systems.md#adding-a-dist-ultralisp).
- **`uiop`** — ASDF's portability layer, registered as 15 sub-packages (`uiop/os`, `uiop/pathname`, ...) that `uiop` re-exports, so both spellings of a member name the same symbol. It does **not** use `cl`. See [The uiop Package](uiop.md).
- **`usocket`** — a [usocket](https://github.com/usocket/usocket)-compatible shim over the `rontolisp:tcp-*` socket built-ins (`usocket:socket-connect`, `usocket:socket-listen`, ...), implemented once in Lisp source; also registered as the built-in ASDF system `"usocket"`. It does **not** use `cl`. See the [TCP Sockets guide](../guides/tcp-sockets.md#the-usocket-compatible-shim).

A symbol can be referenced with a package qualifier: `package:symbol` (e.g. `cl:car`, `rontolisp:version`) reaches the package's external (exported) symbols, and `package::symbol` reaches any of its symbols, internal ones included — the same single/double colon distinction as Common Lisp (see [External and internal symbols](#external-and-internal-symbols)). `*package*` holds the current package — as the package keyword `find-package` answers, so `(eq *package* (find-package ...))` holds — and `(in-package name)` switches it (the name is a keyword, a symbol, or a string: `:rontolisp`, `rontolisp`, `"rontolisp"`). Like Common Lisp's, `*package*` is a dynamic variable read when a form runs: a function reads the package current at call time, `(let ((*package* ...)) ...)` binds it for the extent, `with-standard-io-syntax` binds it to `cl-user`, and `setq` assigns it. The standard Common Lisp names `common-lisp` and `common-lisp-user` are built-in **nicknames** for `cl` and `cl-user`, so portable `(:use #:common-lisp)` clauses and `common-lisp:car` references resolve; the shorthands `rl` and `la` are built-in nicknames for `rontolisp` and `linalg`, and `quicklisp` for `ql`. User packages can register their own nicknames with the `defpackage` `:nicknames` clause.

```lisp
(print *package*)              ; => :CL-USER
(print (rontolisp:version))    ; the build's own version plist
(print (gethash "n" (rl:json-parse "{\"n\": 41}")))  ; => 41
(print (la:to-list (la:from-list '(1 2 3))))        ; => (1.0 2.0 3.0)
```

[`rontolisp:version`](functions/rontolisp-version.md) returns the same information as `rontolisp --version`, as a property list `(:version "0.1.0-SNAPSHOT" :build-timestamp "..." :git-commit "..." :git-branch "...")`. Its timestamp and revision are whatever the running build was made from, so no fixed result is shown for it here.

Because the `rontolisp` package does not use `cl`, standard symbols must be qualified with `cl:` inside it, while `version` (which it owns) is available unqualified:

```console
(in-package rontolisp)
(cl:print (version))           ; the rontolisp package owns version
(cl:print (cl:car '(1 2)))     ; standard symbols need the cl: prefix here
;; (car '(1 2)) would be an error: Undefined symbol: car (use cl:car)
```

The default package `cl-user` is empty and uses `cl`, so ordinary programs do not need any qualifiers.

## External and internal symbols

As in Common Lisp, each package distinguishes its external (exported) symbols
from its internal ones, and the two qualifier spellings differ in reach:

- `package:symbol` (single colon) references an **external** symbol only.
- `package::symbol` (double colon) references **any** symbol of the package,
  internal ones included.

The built-in packages export their entire documented API: every standard `cl`
symbol is external, and so are all the `rontolisp` and `java` functions in this
manual (so the double colon is never *required* for them, though
`rontolisp::version` is also accepted and means the same as
`rontolisp:version`). Internal symbols follow the `%` prefix convention — for
example `rontolisp::%json-parse`, the fixed-arity helper behind
[`rontolisp:json-parse`](functions/rontolisp-json-parse.md) — and are
implementation details that may change without notice. `cl-user` exports
nothing, like the Common Lisp `COMMON-LISP-USER` package, so on the rare
occasion a `cl-user` symbol needs a qualifier it is written `cl-user::name`.

A single-colon reference to a non-external symbol is an error at read/compile
time:

```console
CL-USER> (rontolisp:%json-parse "1")
Error: The symbol %json-parse is not external in the rontolisp package (use rontolisp::%json-parse)
```

A package's export set comes from its definition — the built-in packages export
their documented API, a user-defined package its `(:export ...)` clause — and
[`export`](functions/export.md)/[`unexport`](functions/unexport.md) adjust it
afterwards. A symbol defined while `(in-package rontolisp)` is in effect is
interned into the `rontolisp` package as an internal symbol, so from other
packages it must be referenced with the double colon.

Exporting changes which qualifier *reaches* a symbol, never which symbol it is,
so an `export` may come before or after the definitions it publishes. One
deviation from Common Lisp: a symbol exported after it was first named keeps the
double colon when *printed* — the qualifier is stored with the symbol here
rather than recomputed at print time — though both spellings name the same
symbol.

## User-defined packages (`defpackage`)

New packages are defined with [`defpackage`](special-forms/defpackage.md):

```lisp
(defpackage :mypkg (:use :cl) (:export :greet))
(in-package :mypkg)
(defun greet (name) (concatenate 'string "hello, " name))
(defun helper () 42)                  ; not exported: internal
(in-package :cl-user)
(print (mypkg:greet "world"))         ; => "hello, world"
(print (mypkg::helper))               ; => 42
```

Like `in-package`, `defpackage` is a **literal, top-level directive consumed at
read/compile time**, so packages are defined in source order, before any use.
The supported clauses are `(:use package...)`, `(:export symbol...)`,
`(:nicknames name...)`, `(:import-from package symbol...)` and
`(:shadow symbol...)`, plus `(:documentation "...")`/`(:size n)` which are
accepted and ignored; the name and the clause arguments are keywords, bare
symbols, strings, or uninterned symbols (`#:name`, the portable defpackage
idiom). A `:shadow`ed name always resolves to the package's own symbol inside
the package -- never to the `cl` (or any used package's) symbol of the same
name -- so a library can define its own `digit-char-p` or `defconstant`.
`:shadowing-import-from` is an error, and so is any other clause or using a
package that does not exist yet. A `defpackage` naming a package that already
exists MODIFIES it (Common Lisp's rule): the clauses merge into what is there,
which is what lets a library declare a package rontolisp has already seeded.
A name that is another package's nickname stays an error.

- `:use` makes the **external** symbols of the used packages visible
  unqualified, as in Common Lisp — internal symbols of a used package still
  need the double colon. Without a `:use` clause nothing is inherited (like
  SBCL), so `cl` symbols would need the `cl:` prefix; ordinary packages should
  say `(:use :cl)` (or, portably, `(:use #:common-lisp)`). When several used
  packages export the same name, the first package in `:use` order wins
  (Common Lisp signals a conflict instead).
- `:export` declares the package's external symbols. Symbols interned later
  (a `defun` under `(in-package name)` that is not in the `:export` clause, a
  free variable) are internal, exactly like the built-in packages.
- `:nicknames` registers alternate names that resolve everywhere the canonical
  name does (in qualifiers, `in-package`, `:use`, ...). A nickname colliding
  with an existing package or nickname is an error — the built-in nicknames
  (`common-lisp`, `common-lisp-user`, `rl`, `la`, `quicklisp`) are reserved the
  same way as the built-in package names.
- `:import-from` makes the named symbols of one package visible unqualified
  without using the whole package. Resolution is textual: an imported name
  resolves to the source package's canonical spelling, so importing and then
  re-exporting a symbol makes `mypkg:name` refer to the original definition.

[`use-package`](functions/use-package.md) is the runtime form of the `:use`
clause, and follows the same read/compile-time rule as `in-package`: a literal
top-level `(use-package :mypkg)` widens the current package's use list for the
forms that follow it, on every backend.

[`export`](functions/export.md), [`unexport`](functions/unexport.md) and
[`import`](functions/import.md) follow the same rule. `make-package` and
`rename-package` are not available, and a `defpackage` inside another form (not
top-level) is an error.

Packages are resolved at read/compile time (in source order), so `in-package` is a top-level directive: which package a symbol in the source belongs to is decided by the `in-package` above it, not by a runtime `setq` of `*package*` (in compiled output the whole file is resolved before it runs; the interpreter resolves each top-level form as it reaches it, so a runtime assignment does affect the forms after it there). In compiled output a runtime-loaded file's package directives are not processed; the `rontolisp` package's functions (`version`, ...) are not available as first-class values (they cannot be passed to `mapcar`/`funcall`); and a `cl` symbol name must not be shadowed as a local variable inside a package that does not use `cl`.

## rontolisp Package Extensions

The symbols the `rontolisp` package owns are **implementation-specific and not
part of Common Lisp**. They must be referenced with the `rontolisp:` qualifier
(or used unqualified after `(in-package rontolisp)`). Besides `version`,
the package provides asynchronous outgoing HTTP via `rontolisp:fetch` (which
returns a future) together with `rontolisp:await` (resolve) and
`rontolisp:futurep` (type predicate), and JSON conversion via
[`rontolisp:json-parse`](functions/rontolisp-json-parse.md) /
[`rontolisp:json-stringify`](functions/rontolisp-json-stringify.md)
(JavaScript `JSON.parse`/`JSON.stringify` style). All of these have their own
pages in the [Functions](functions/rontolisp.md) reference,
including the full [`rontolisp:fetch`](functions/rontolisp-fetch.md) /
[`rontolisp:await`](special-forms/rontolisp-await.md) /
[`rontolisp:futurep`](functions/rontolisp-futurep.md) documentation.

Two members of the package are neither functions nor macros but read-time
literals: `rontolisp:current-file` and `rontolisp:current-line`, which the
reader replaces with the position they stand on. Because they are resolved
before any `in-package` directive is interpreted, they are the exception to the
rule above — they must always be written qualified. See
[Source position literals](data-types.md#source-position-literals-rontolispcurrent-file-rontolispcurrent-line).
