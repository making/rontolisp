# asdf:find-system

`(asdf:find-system name &optional (error-p t))`

Returns the **system metaobject** for the named system: a CLOS instance of
[`asdf:system`](asdf-component-name.md) (or of `asdf:package-inferred-system`
for a `:class :package-inferred-system` one). The instance is memoized per
name — repeated calls answer the **same object** (`eq`), like real ASDF — and
passing a component object back in answers it unchanged. `name` is a string,
keyword or symbol designator (a symbol downcases, a string stays verbatim).

A name nothing registered is searched for, on the interpreter, as a load searches
([`asdf:*system-definition-search-functions*`](asdf-system-definition-search-functions.md)): the `.asd`
runs and registers what it defines, and nothing is downloaded or loaded. A name still
unknown signals `asdf:missing-component`, unless `error-p` is nil — then the answer is
`nil`, the probe shape libraries use (`(asdf:find-system name nil)` guarding a
`load-system`). An error while the `.asd` loads is an
`asdf:load-system-definition-error`.

The component model behind the instance is real ASDF's: the classes
`asdf:component`, `asdf:child-component` / `asdf:parent-component`,
`asdf:module`, `asdf:system`, `asdf:package-inferred-system`,
`asdf:source-file`, `asdf:cl-source-file` and `asdf:static-file`, with real ASDF's slot
names, are real CLOS classes on every backend, so `typep`, `typecase` and `defmethod` specializers
over them all work.

```lisp
(asdf:defsystem :demo :components ((:file "main")))
(asdf:component-name (asdf:find-system :demo)) ; => "demo"
```

```lisp
(asdf:defsystem :demo2 :components ((:file "main")))
(eq (asdf:find-system :demo2) (asdf:find-system "demo2")) ; => T
```

## Backend support

Works on all four backends. The interpreter answers from its live system
registry; a compiled program carries the registry that was spliced at compile
time, so `find-system` knows exactly the systems the program loaded (plus every
`defsystem` the `.asd`s declared). A literal
`(asdf:system-source-directory (asdf:find-system 'lib nil))` still folds to a
literal namestring at compile time, so the bundled-data-file idiom needs no
runtime registry.
