# 81: cl-who unit 5 -- integration (vendor + 4-backend load + close)

Parent: `.todo/76`. The closing session (the split-sequence-unit-7 analog:
`.todo/54` Phase 3). Depends on `.todo/77`..`.todo/80`. Do this only after the
four feature units are green.

## Steps

1. **Vendor cl-who** under `src/test/resources/cl-who/` (BSD license file +
   `cl-who.asd` + `packages.lisp` `specials.lisp` `util.lisp` `who.lisp`),
   verbatim like `src/test/resources/split-sequence`.
   - The `.asd` uses `:serial t`, `:components`, `:in-order-to` (already
     ignored) and a second `defsystem :cl-who/test` depending on
     `:flexi-streams` -- only load `:cl-who`, not the test system.
   - `packages.lisp` has `(:use :cl)`, `(:nicknames :who)`,
     `#+(or :clasp :sbcl) (:shadow #:defconstant)` (feature-guarded, skipped),
     `(pushnew :cl-who *features*)` (no-op on the substituted `*features*`).
2. **Load on the interpreter**, fix residue, then JVM, WASM P1, WASM component.
   Anticipated residue beyond units 77-80:
   - `n-spaces` / `make-array :displaced-to +spaces+ :element-type 'base-char`
     -- only hit when indenting; if it blocks LOADING (macro definition), make
     it parse; runtime indentation stays the documented limitation (`.todo/76`).
   - `(defconstant +spaces+ (make-string 2000 :initial-element #\Space
     :element-type 'base-char))` -- large constant string; watch the JVM baked
     constant limit (`.todo/17`) and WASM data section.
   - `extract-declarations` returns `(values decls forms)` consumed by
     `multiple-value-bind` in `with-html-output` -- exercises the `%mv-spill`
     channel (`.kb/multiple-values.md`); confirm it survives the macro path.
   - `check-type ,var stream` at expansion output -- from `.todo/77`.
3. **Document the lite limitations** (in the cl-who doc page and `.kb/`):
   dynamic-variable rebinding + `:indent` unsupported (default no-indent
   renders correctly); switch mode via `(setf (html-mode) ...)`; hyperdoc
   lookup returns nil.
4. **Tests**: a `ClWhoE2eTest` (interpreter + compiled JVM over the vendored
   sources, split-sequence's `SplitSequenceE2eTest` pattern) and a `ci-spec`
   case. Note: the compile path needs the `.asd` on disk, so like the asdf
   cases this cannot be a pure concatenated-ci-spec case for the load itself --
   drive it from the JUnit e2e; the ci-spec case can be a self-contained
   render using cl-who once loaded via `--system-path` in the e2e harness.
5. Run the native `CiSpecE2eTest`; update `.todo/76` and `.todo/54`
   (library-candidates memory) to mark cl-who loadable; add the
   `.kb/documentation-site.md` mirror (en + ja).

## Definition of done

`asdf:load-system "cl-who"` + a basic render works on all four backends:

```lisp
(cl-who:with-html-output-to-string (s)
  (:html (:head (:title "Hi")) (:body (:p "Hello" (:a :href "/x" "link")))))
;; => "<html><head><title>Hi</title></head><body><p>Hello<a href='/x'>link</a></p></body></html>"
```

(default no-indent, `*html-mode*` :xml). Attributes, nested tags, `str`/`esc`
/`fmt`, and `(setf (html-mode) :html5)` all exercised. Update the memory index
(a new `cl-who-loadable` memory + `MEMORY.md` line, linking
`[[asdf-library-candidates]]`).
