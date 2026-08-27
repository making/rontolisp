# defpackage

`(defpackage name (:use package...) (:export symbol...) (:nicknames name...) (:import-from package symbol...) (:local-nicknames (nick actual)...))`

Defines a new package named `name` and returns the name symbol. Like `in-package`, it is a literal, top-level directive consumed at read/compile time, so packages are defined in source order. The name and the clause arguments are keywords, bare symbols, strings, or uninterned symbols (`:mypkg`, `mypkg`, `"mypkg"`, `#:mypkg` — the last is the portable defpackage idiom).

- `(:use package...)` makes the external (exported) symbols of the listed packages visible unqualified. The used packages must already exist. Without a `:use` clause **nothing** is visible unqualified — write `(:use :cl)` to use the standard symbols without a `cl:` prefix. `common-lisp` and `common-lisp-user` are built-in nicknames for `cl` and `cl-user`, so `(:use #:common-lisp)` works too.
- `(:export symbol...)` declares the package's external symbols: they are reachable as `name:symbol` from other packages, and inherited by packages that use this one. Symbols interned later (for example `defun`s made under `(in-package name)` that are not in the `:export` clause) are internal and require the double colon, `name::symbol`.
- `(:nicknames name...)` registers alternate names for the package; a nickname resolves everywhere the canonical name does. A nickname that collides with an existing package (or nickname) is an error; the built-in nicknames — `common-lisp` and `common-lisp-user` (`cl`/`cl-user`), `rl` (`rontolisp`), `la` (`linalg`) and `quicklisp` (`ql`) — are reserved the same way.
- `(:import-from package symbol...)` makes the named symbols of `package` visible unqualified without using the whole package. Resolution is textual: an imported name resolves to the source package's canonical spelling, so `(:import-from #:common-lisp #:car)` gives a use-nothing package just `car`.
- `(:local-nicknames (nick actual)...)` registers a shorthand for **another** package, so `nick:symbol` resolves like `actual:symbol` — the idiom libraries use to shorten long package names. Lite: the nickname is **global** (rontolisp has no per-package nickname scoping), so it follows the same collision rules as `:nicknames`. The same registration is available outside a `defpackage` as [`uiop:add-package-local-nickname`](../functions/uiop-add-package-local-nickname.md).
- `(:documentation "...")` and `(:size n)` are accepted and ignored.

A `defpackage` naming a package that already exists **modifies** it, as Common Lisp requires: the clauses are merged into what is there, so nothing the earlier definition declared is lost. (A name that is another package's *nickname* is still an error — adjusting through it would silently apply the definition to a package of a different name.) `:shadowing-import-from` and any other clause (`:intern`, ...) are errors. See [Packages](../packages.md#user-defined-packages-defpackage) for the full rules.

```lisp
(defpackage :util (:use :cl) (:export :trim)) ; => UTIL
```

```lisp
(defpackage #:mypkg
  (:use #:common-lisp)
  (:nicknames #:mp)
  (:import-from #:rontolisp #:version)
  (:export #:greet))
(in-package :mypkg)
(defun greet (name) (concatenate 'string "hello, " name))
(in-package :cl-user)
(mp:greet "world") ; => "hello, world"
```
