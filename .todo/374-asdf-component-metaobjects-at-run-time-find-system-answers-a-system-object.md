# ASDF component metaobjects at run time: `find-system` answers a system object

Difficulty: High

Part of `.todo/372` (rove). Rove's `run` is system-driven -- `(rove:run
:my-app/tests)` -- and every step of it reads the ASDF component model:

```lisp
;; core/suite.lisp
(defgeneric run-system (system)
  (:method ((system symbol)) (run-system (asdf:find-system system)))
  (:method ((system string)) (run-system (asdf:find-system system)))
  (:method ((system asdf:system))
    #-quicklisp (asdf:load-system (asdf:component-name system))
    (with-context (context :name (asdf:component-name system))
      (typecase system
        (asdf:package-inferred-system ... (system-packages system) ...)
        (otherwise (dolist (suite (system-suites system)) ...))))))
;; core/suite/file.lisp
(defun component-source-files (component)
  (typecase component
    (asdf:cl-source-file (list (asdf:component-pathname component)))
    ((or asdf:module asdf:system)
     (mapcan #'component-source-files (copy-seq (asdf:component-children component))))))
(defun package-inferred-system-component-names (system-designator)
  ... (asdf:component-sideway-dependencies system) ... (asdf:component-name system) ...)
(defun package-inferred-system-files (system)
  ... (asdf:component-pathname (first (asdf:component-children (asdf:find-system name)))) ...)
;; main.lisp
(defun run* (target-pattern ...) ... (asdf:find-system pattern-main-part nil) ...
  (remove-if-not matcher (asdf:registered-systems)) ...)
;; core/suite/file.lisp
(unless asdf:*user-cache* ...)
```

Today (`.kb/asdf.md`, `PackageRegistry` line ~485): the `asdf` package exports
nine names; `*user-cache*`, `registered-systems`, `component-name`,
`component-children`, `component-sideway-dependencies`, `cl-source-file`,
`module`, `package-inferred-system` are not external, so rove's files fail at
RESOLUTION ("The symbol X is not external in the ASDF package"); `asdf:system`
is a resolve-only name, not a class, so `(:method ((system asdf:system)))` is
"unknown specializer"; the interpreter's `find-system` materializes a system as
its NAME STRING and the compile paths lower a nested/computed `find-system` to
nil and a nested `load-system` to a call-time error stub (todo-228).

## What lands

**The class family** -- `asdf:component` (name, pathname), `asdf:child-component`
/ `asdf:parent-component` (children) as real ASDF has them, `asdf:module`,
`asdf:system` (+ sideway dependencies, and the metadata the `.asd` carried:
version, author, description -- resolve-only readers may stay), `asdf:package-inferred-system`
(a `system`), `asdf:source-file`, `asdf:cl-source-file`, `asdf:static-file`. Defined
in Lisp source (an `asdf.lisp` resource, the CLOS subset, seeded like the
condition hierarchy or spliced on use like `gray.lisp`), so they are classes on
every backend: `typecase`, `etypecase`, `typep`, defmethod specializers all work
by construction. Readers `component-name` (the downcase-canonical name),
`component-pathname` (a system: its directory, as today; a source file: the
resolved file path -- the SAME spelling `.todo/375` binds into `*load-pathname*`,
so `(uiop:native-namestring (asdf:component-pathname c))` and rove's recorded
`*load-pathname*` key the same hash entry), `component-children` (a system's
components in load order, ONE `cl-source-file` per file; a package-inferred
sub-system has exactly one child, real ASDF's shape), `component-sideway-dependencies`
(the `:depends-on` names, sub-system names included -- rove's
`package-inferred-system-component-names` filters them by the primary's prefix),
`component-parent`, `component-system`, `system-source-directory` /
`component-pathname` / `system-relative-pathname` accepting the object as well as
the name (they take a designator; the object's name is the key). `asdf:*user-cache*`
external and nil (there is no fasl cache; rove's `resolve-file` returns early on
it). `asdf:registered-systems`: the names of every registered system, downcased,
like ASDF (`.asd`-declared, package-inferred-derived and built-in ones).

**`find-system` answers a memoized instance per name** (one object per system --
`eq` on repeated calls, which is how real ASDF behaves and what
`(setf (gethash system ...))` shapes assume). Interpreter: over `asdfSystems`
(`LispSystem` records already hold files, deps, base dir, the package-inferred
dir). Compile paths: `LoadInliner` bakes the registry it spliced -- name, class,
base dir, files, `:depends-on`, package-inferred flag -- into a runtime table
(`%asdf-systems%`), and the same Lisp defuns build the instances from it;
`expandRuntimeFindSystem`'s "arguments then nil" lowering goes. A LITERAL
`(asdf:find-system 'x)` no longer folds to a string: `system-source-directory`
callers that folded through `CompileTimePathnameFolder` keep working through the
designator acceptance above.

**Runtime `asdf:load-system` on the compile paths**: a nested/computed
`(asdf:load-system NAME [options])` becomes "already spliced -> nil no-op,
otherwise the existing call-time error" instead of an unconditional error --
rove calls it on the very system it was asked to test, which the program spliced
at top level. Same for a nested `(ql:quickload NAME)`.

**`asdf:test-system` (second half)**: `.asd` `:perform (test-op (o c) BODY)`
is tolerated-and-ignored today. Record it: `AsdfSystems` keeps the BODY (data)
per system; the interpreter's `test-system` = `load-system` + evaluate BODY with
`o`/`c` bound (c = the system object); the compile path splices
`(defun %asdf-test-op-<name> (o c) BODY)` at the system's splice point and
`test-system` dispatches to it through the baked table. `:in-order-to
((test-op (test-op "x/tests")))` chains: `test-system` follows the edge before
its own perform. This is what fukamachi's `.asd`s ship (`:perform (test-op (op c)
(symbol-call :rove :run c))`, `uiop:symbol-call` is already real), so
`(asdf:test-system "my-app")` becomes the standard entry point on every backend.

Not in: `operate`/`perform` as generic functions, `:defsystem-depends-on`,
`compile-op` and the fasl output-translations. `component-pathname` of a source
file keeps answering a NAMESTRING like the other asdf/uiop producers
(`.kb/pathnames.md`, "deliberately still namestrings"); rove only namestrings it.

## Acceptance

- `AsdfSystemsTest` + interpreter/JVM/WASM tests: `find-system` returns an
  instance, `eq` across calls, `typep` on `asdf:system` / `asdf:package-inferred-system`,
  children/pathnames/sideway deps for a `:components` system with a `:module`
  and for a package-inferred graph (the `package-inferred-demo` fixture), a
  defmethod specialized on `asdf:system`, `registered-systems`, a nested
  `load-system` of a spliced system answering nil on the JVM/WASM.
- `RoveE2eTest` (`.todo/372`) drives `(rove:run "my-app/tests")` over both
  system shapes and `(asdf:test-system ...)` on all four backends.
- `.kb/asdf.md` (the "no CLOS operate, no test-op" first paragraph is rewritten
  to the new contract), `doc/{en,ja}/guides/asdf-systems.md` "What is (and is
  not) supported", reference pages for `find-system`/`test-system` and the new
  readers.
