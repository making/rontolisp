# make-package

`(make-package name &key :use :nicknames)`

Creates a package at run time and returns it -- the upcased canonical name as a
keyword, like [`find-package`](find-package.md). The name and nicknames are
upcased the way the reader upcases source, so `:my-pkg` and `"MY-PKG"` name the
same package. Each `:use` entry must name a package the program already knows (a
built-in or a [`defpackage`](../special-forms/defpackage.md) product); the new
package starts empty. What [`intern`](intern.md), [`export`](export.md), [`import`](import.md),
[`shadowing-import`](shadowing-import.md) and [`shadow`](shadow.md) put into it is
recorded in its member table -- on every backend -- so [`find-symbol`](find-symbol.md),
[`do-symbols`](../macros/do-symbols.md) and
[`with-package-iterator`](../macros/with-package-iterator.md) see it and
[`unintern`](unintern.md) can take it out again.

A name or nickname that collides with a registered package or nickname signals a
catchable `package-error`, as does an unknown `:use` entry. Read/compile-time
packages are never affected: creating over one signals instead of replacing it.

```lisp
(make-package :doc-mp :use '(:cl) :nicknames '(:dmp)) ; => :DOC-MP
(find-package :dmp) ; => :DOC-MP
(delete-package :doc-mp) ; => T
```
