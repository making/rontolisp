# h30. ASDF operation protocol: `operation`/`load-op`/`compile-op` classes and a `perform` generic

Difficulty: High

Found 2026-10-30 probing `(ql:quickload "fiveam")`. fiveam depends on
`net.didierverna.asdf-flv`, whose SOURCE (not its `.asd`) extends ASDF:

```lisp
(defmethod asdf:perform :around
    ((operation asdf:load-op) (file asdf:cl-source-file))
  (progv *file-local-variables* (mapcar #'symbol-value *file-local-variables*)
    (call-next-method)))
```

Today: `asdf:perform` is not external (`The symbol PERFORM is not external in the ASDF
package`), and `asdf:load-op`/`compile-op`/`operation` are no classes. `.kb/asdf.md` states
"No general CLOS `operate`/`perform`".

Measured: with the symbols exported, the three classes defined and a no-op
`(defgeneric asdf:perform (o c))` installed by hand before the quickload, fiveam loads and
`fiveam:run!` passes on the interpreter (after also removing the `#+#.` form of h31).
So the stub gets past this error, but it does not implement the behavior: the `:around`
method never wraps a file load.

## The essential fix

Make the file-load step of `load-system` a call to `(asdf:perform (make-instance 'load-op)
<component>)`, where `<component>` is the component metaobject `find-system` already
answers, and give `perform` a primary method that loads the file. Then
`:around`/`:before`/`:after` methods defined by an extension run around every component
load. Export `operation`, `load-op`, `compile-op`, `prepare-op`, `perform`, `operate`,
`input-files`/`output-files` (as far as the shim can answer them).

Watch:
- Compile paths splice files at compile time (`LoadInliner`), so a user `perform` method
  cannot wrap the splice. Decide what a compiled program does with one (run the method
  around the spliced forms at load time, or reject the method by name). Do not leave it a
  silent no-op.
- `.asd`-level `:perform` (`test-op` only today, `.kb/asdf.md` "test-op") must stay
  consistent with the new generic.
- `asdf-flv` is the driving consumer; `:around-compile` (mgl-pax) is the next one.
- `lass` (`asdf.lisp`) defines a component class in package `asdf/interface`
  (`No such package: ASDF/INTERFACE`). It also defines `perform` on `load-op`/`compile-op`
  and `output-files` on `compile-op` for it. So ASDF's internal package names
  (`asdf/interface`, `asdf/component` ...) must exist as nicknames or packages.
