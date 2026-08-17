# 428. `#.` read-time eval sees a nil `*load-truename*` on the compile path

Difficulty: Medium

`inner.lisp`, loaded from another file:

```lisp
(princ (list :run-time *load-truename*))
(princ (list :read-time #.(format nil "~A" *load-truename*)))
```

| | run time | read time (`#.`) |
| --- | --- | --- |
| interpreter | `/abs/inner.lisp` | `/abs/inner.lisp` |
| JVM / WASM | `/abs/inner.lisp` | **NIL** |

The RUN-time value is right on every backend -- that is `.todo/375`'s
`%begin-file` / `%end-file` bracket work, recorded in `.kb/load-inliner.md`.
But the bracket lowers to top-level `(setq cl:*load-pathname* ...)`
**statements**, and `#.` runs during the READ of the spliced file, long before
any statement executes. So a `#.` form that consults the load context reads the
nil the variable still holds.

`*compile-file-pathname*` / `*compile-file-truename*` are deliberately nil
forever (there is no `compile-file`; `.kb/asdf.md`), so the usual portable
spelling `(or *compile-file-pathname* *load-truename*)` has nothing left to
fall back on.

## Why it matters

`#.` + `*load-truename*` is THE portable way a library reads a data file that
ships beside its source. Found by the cl-mustache spike (`.todo/425`):

```lisp
(defun version ()
  #.(format nil "CL-MUSTACHE ~A (Mustache spec ~A)"
            (with-open-file (f (merge-pathnames "version.lisp-expr"
                                                (or *compile-file-pathname* *load-truename*)))
              (read f))
            ...))
```

`merge-pathnames` against nil yields the bare relative name, so the compile
path refuses the whole system before emitting anything:

```
error: .../mustache.lisp:735:1: OPEN: cannot open file version.lisp-expr
```

The interpreter loads it fine. That asymmetry is the bug: a library that works
interpreted stops being compilable, and the message points at the library
rather than at us.

Downstream, a nil that survives into `make-pathname` can surface as a raw Java
`NullPointerException` on the JVM backend rather than a Lisp condition
(`cl-mustache`'s own `t/test-api.lisp` does exactly this) -- worth a look while
in the area, but the root cause is this item.

## The fix

Bind `*load-pathname*` / `*load-truename*` around the compile-time READ of each
spliced file, to the same two strings `LoadInliner.spliceFile` already puts in
its `%begin-file` marker -- the reader is where `#.` is evaluated, so the
binding has to be there and not in the lowered statement stream. An ASDF
component keeps its resolved path for both, exactly as the run-time bracket
does, so the read-time and run-time values agree by construction.

Then decide, explicitly and in `.kb/asdf.md`, whether the compile paths should
ALSO bind `*compile-file-pathname*` / `*compile-file-truename*` while reading a
spliced file. They are nil today for a stated reason; the `(or ...)` idiom
above is the argument for revisiting it, and either answer needs the "why"
written down so the next visitor can tell whether it still holds.

## Definition of done

A `#.` form reading `*load-truename*` inside a `load`ed file, an ASDF
component, and a `ql:quickload`ed system answers the same non-nil path on all
four backends, byte-identical with the run-time value. Pinned by extending
`LoadContextE2eTest` (it already covers the run-time half over a real system on
all four backends) with a read-time case, plus a `LoadInlinerTest` unit.
`.kb/load-inliner.md` and `.kb/read-load-streams.md` record that the load
context is now established at read time too, and `.kb/asdf.md` records the
`*compile-file-*` decision.
