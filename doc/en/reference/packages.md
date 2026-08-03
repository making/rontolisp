# Packages

rontolisp has a small namespace (package) system with a set of built-in packages, plus [user-defined packages via `defpackage`](#user-defined-packages-defpackage):

- **`cl`** — the standard package. All built-in functions, macros, special forms and the `*package*` variable belong here.
- **`cl-user`** — the default working package. It *uses* `cl`, so standard symbols are available unqualified. The current package when a program starts. User definitions go here.
- **`rontolisp`** — a package for implementation-specific symbols. `rl` is a built-in nickname. It does **not** use `cl`. It owns the `version`, `list-functions`, `list-macros` and `list-special-forms` functions.
- **`linalg`** — numpy-style vector/matrix operations (`linalg:zeros`, `linalg:matmul`, `linalg:solve`, ...), implemented once in Lisp source and available in every backend. `la` is a built-in nickname. It does **not** use `cl`. See the [Vectors & Matrices guide](../guides/linear-algebra.md).
- **`java`** — Java interop by reflection, usable only under the JVM interpreter (`java -jar rontolisp.jar`), not the compilers or the native binary. It does **not** use `cl`. It owns `new`, `call`, `static`, `field` and `proxy`; see the [Java interop guide](../guides/java-interop.md).
- **`asdf`** — a limited, API-compatible subset of ASDF (system definitions): `defsystem` and `load-system`. It does **not** use `cl`. See the [Systems guide](../guides/asdf-systems.md).
- **`ql`** — a limited, API-compatible subset of Quicklisp: `quickload` downloads a system from the real Quicklisp distribution and loads it through the `asdf` subset. `quicklisp` is a built-in nickname. It does **not** use `cl`. See the [Systems guide](../guides/asdf-systems.md#downloading-with-quickload).
- **`usocket`** — a [usocket](https://github.com/usocket/usocket)-compatible shim over the `rontolisp:tcp-*` socket built-ins (`usocket:socket-connect`, `usocket:socket-listen`, ...), implemented once in Lisp source; also registered as the built-in ASDF system `"usocket"`. It does **not** use `cl`. See the [TCP Sockets guide](../guides/tcp-sockets.md#the-usocket-compatible-shim).

A symbol can be referenced with a package qualifier: `package:symbol` (e.g. `cl:car`, `rontolisp:version`) reaches the package's external (exported) symbols, and `package::symbol` reaches any of its symbols, internal ones included — the same single/double colon distinction as Common Lisp (see [External and internal symbols](#external-and-internal-symbols)). `*package*` evaluates to the name of the current package, and `(in-package name)` switches it (the name is a keyword, a symbol, or a string: `:rontolisp`, `rontolisp`, `"rontolisp"`). The standard Common Lisp names `common-lisp` and `common-lisp-user` are built-in **nicknames** for `cl` and `cl-user`, so portable `(:use #:common-lisp)` clauses and `common-lisp:car` references resolve; the shorthands `rl` and `la` are built-in nicknames for `rontolisp` and `linalg`, and `quicklisp` for `ql`. User packages can register their own nicknames with the `defpackage` `:nicknames` clause.

```lisp
(print *package*)              ; => cl-user
(print (rontolisp:version))    ; => (:version "0.1.0-SNAPSHOT" :build-timestamp "..." :git-commit "..." :git-branch "...")
(print (gethash "n" (rl:json-parse "{\"n\": 41}")))  ; => 41
(print (la:to-list (la:from-list '(1 2 3))))        ; => (1.0 2.0 3.0)
```

`rontolisp:version` returns the same information as `rontolisp --version`, as a property list.

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
> (rontolisp:%json-parse "1")
Error: The symbol %json-parse is not external in the rontolisp package (use rontolisp::%json-parse)
```

There is no runtime `export` function — a package's export set is fixed when
it is defined: the built-in packages export their documented API, and a
user-defined package exports its `(:export ...)` clause (see
[Missing features](../guides/missing-features.md)). A symbol defined
while `(in-package rontolisp)` is in effect is interned into the `rontolisp`
package as an internal symbol, so from other packages it must be referenced
with the double colon.

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
`:shadowing-import-from` is an error, and so is any other clause, redefining
an existing package, or using a package that does not exist yet.

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

Beyond it there is no runtime package manipulation: `make-package`, `export`,
`import` and `rename-package` are not available, and a `defpackage` inside
another form (not top-level) is an error.

## Package introspection

`rontolisp:list-functions`, `rontolisp:list-macros` and `rontolisp:list-special-forms` return the symbols of a package by category, sorted alphabetically. The optional argument is a package designator — a keyword, a bare symbol, a quoted symbol or a string (`:cl`, `cl`, `'cl`, `"cl"`) — and defaults to `:cl`. An unknown package is an error (`No such package: foo`).

```lisp
(print (rontolisp:list-macros))
; => (AND ASSERT BLOCK CASE CCASE CERROR CHANGE-CLASS CHECK-TYPE COMPLEMENT COMPLEX COND DECF DECLAIM DECLARE DEFINE-COMPILER-MACRO DEFINE-CONDITION DEFINE-MODIFY-MACRO DEFINE-SETF-EXPANDER DEFSETF DEFTYPE DESTRUCTURING-BIND DO DO* DO-EXTERNAL-SYMBOLS DOCUMENTATION DOLIST DOTIMES ECASE ERROR ETYPECASE EVAL-WHEN FLET FORMAT HANDLER-BIND HANDLER-CASE IGNORE-ERRORS INCF LABELS LET* LOAD-TIME-VALUE LOCALLY LOOP MACROLET MAKE-CONDITION MAKE-INSTANCE MAKE-SEQUENCE MULTIPLE-VALUE-BIND MULTIPLE-VALUE-CALL MULTIPLE-VALUE-LIST MULTIPLE-VALUE-PROG1 MULTIPLE-VALUE-SETQ NTH-VALUE OR POP PRINT-UNREADABLE-OBJECT PROCLAIM PROG PROG* PROG1 PROG2 PSETF PSETQ PUSH PUSHNEW REMF RESTART-BIND RESTART-CASE RETURN-FROM ROTATEF SETF SHIFTF SIGNAL SLOT-BOUNDP SLOT-MAKUNBOUND SLOT-VALUE SYMBOL-MACROLET THE TIME TYPECASE TYPEP UNLESS WARN WHEN WITH-ACCESSORS WITH-INPUT-FROM-STRING WITH-OPEN-FILE WITH-OPEN-STREAM WITH-OUTPUT-TO-STRING WITH-PACKAGE-ITERATOR WITH-SIMPLE-RESTART WITH-SLOTS WITH-STANDARD-IO-SYNTAX WRITE-CHAR)
(print (rontolisp:list-special-forms))
; => (CATCH DEFCLASS DEFCONSTANT DEFGENERIC DEFMACRO DEFMETHOD DEFPACKAGE DEFPARAMETER DEFSTRUCT DEFUN DEFVAR FUNCTION GO IF IN-PACKAGE LAMBDA LET PROGN PROGV QUOTE RETURN SETQ TAGBODY THROW UNWIND-PROTECT WHILE)
(print (length (rontolisp:list-functions)))
; => 365
(defun square (x) (* x x))
(print (rontolisp:list-functions :cl-user))
; => (SQUARE)
(print (rontolisp:list-functions :rontolisp))
; => (AWAIT CATCH FETCH FINALLY HTTP-HANDLER JSON-PARSE JSON-STRINGIFY LIST-FUNCTIONS LIST-MACROS LIST-SPECIAL-FORMS MAKE-MUTEX MUTEX-ACQUIRE MUTEX-RELEASE QUERY-PARAM QUERY-PARAMS RANDOM-BYTES TCP-ACCEPT TCP-CONNECT TCP-LISTEN TCP-LOCAL-ADDRESS TCP-LOCAL-PORT TCP-PEER-ADDRESS TCP-PEER-PORT THEN THEN* TLS-CONNECT TLS-LISTEN TLS-LISTEN-PEM URL-DECODE URL-ENCODE URL-PATH URL-QUERY VERSION WIT-ERROR-PAYLOAD WIT-PROVIDE)
(print (rontolisp:list-functions :java))
; => (CALL FIELD NEW PROXY STATIC)
```

The classification follows the function namespace: a name is listed as a function exactly when it is usable as a function value via `#'name` (so `first`, `length`, `1+`, ... are functions even though they compile via inline expansion), and `list-macros`/`list-special-forms` list the operators that have no function value. Notes:

- `list-functions` of `cl-user` lists the user-defined functions (`defun`s); names that are package-qualified, `%`-prefixed internals or shadow a `cl` symbol are excluded. In compiled output it is a **compile-time snapshot** of the program's `defun`s — functions defined at runtime through `load`/`eval` (even with `--dynamic`) are not included, and functions defined while `(in-package :rontolisp)` is in effect are not listed for any package.
- `list-functions` of a [user-defined package](#user-defined-packages-defpackage) lists the package's `defun`s under their canonical qualified names — `mypkg:fn` for an exported function, `mypkg::fn` for an internal one. `list-macros` and `list-special-forms` of a user package are `nil`.
- Car/cdr compositions (`cadr`, `caddr`, ...) are recognized by pattern, not enumerated, so they do not appear in `list-functions`.
- The package designator must be a literal; a computed designator is rejected at read/compile time (the interpreter additionally accepts a computed designator through `funcall`, where an unknown package yields `nil` instead of an error — user packages are known only at read/compile time).
- Like `version`, these functions are not supported inside the compiled runtime `eval`/`load`.

Packages are resolved at read/compile time (in source order), so `in-package` is a top-level directive and `*package*` reflects the current package rather than being a mutable runtime variable. In compiled output a runtime-loaded file's package directives are not processed; the `rontolisp` package's functions (`version`, `list-functions`, ...) are not available as first-class values (they cannot be passed to `mapcar`/`funcall`); and a `cl` symbol name must not be shadowed as a local variable inside a package that does not use `cl`.

## rontolisp Package Extensions

The symbols the `rontolisp` package owns are **implementation-specific and not
part of Common Lisp**. They must be referenced with the `rontolisp:` qualifier
(or used unqualified after `(in-package rontolisp)`). Besides the introspection
helpers above (`version`, `list-functions`, `list-macros`, `list-special-forms`),
the package provides asynchronous outgoing HTTP via `rontolisp:fetch` (which
returns a future) together with `rontolisp:await` (resolve) and
`rontolisp:futurep` (type predicate), and JSON conversion via
[`rontolisp:json-parse`](functions/rontolisp-json-parse.md) /
[`rontolisp:json-stringify`](functions/rontolisp-json-stringify.md)
(JavaScript `JSON.parse`/`JSON.stringify` style). All of these have their own
pages in the [Functions](functions.md#rontolisp-package-functions) reference,
including the full [`rontolisp:fetch`](functions/rontolisp-fetch.md) /
[`rontolisp:await`](special-forms/rontolisp-await.md) /
[`rontolisp:futurep`](functions/rontolisp-futurep.md) documentation.
