# 220 - `error` with a RUNTIME control string drops its format arguments

Split out of `.todo/216` (found while verifying the runtime format renderer on
the four backends). It is NOT a renderer defect -- the renderer never sees the
arguments -- and it predates 216: the same output came out of the tree before
that work.

`(error "literal ~a-~a" 1 2)` renders its arguments. The same call with the
control string in a variable renders them as NIL, identically on all four
backends (2026-07-31):

```lisp
(handler-case (error "lit ~a-~a" 1 2) (error (e) (princ (princ-to-string e))))
;; => lit 1-2

(let ((c "~a-~a"))
  (handler-case (error c 1 2) (error (e) (princ (princ-to-string e)))))
;; => NIL-NIL
```

Same for `signal` / `warn`, and for the shape that motivated 216 -- a condition
signalled with a control string a library computed:

```lisp
(let ((c "PostgreSQL warning: ~A~@[~%~A~]"))
  (handler-case (error c "relation \"notes\" already exists, skipping" nil)
    (error (e) (princ (princ-to-string e)))))
;; => PostgreSQL warning: NIL          (CL: PostgreSQL warning: relation ...)
```

## Where it happens

`LispMacroExpander.expandSignalDesignatorInner`: a datum that is a literal
`LispString` goes to `expandStringSignal` (control + arguments); a datum that is
an EXPRESSION falls through to `expandObjectSignal` (or, with initargs and a
non-empty class registry, to the `%error-runtime` dispatcher). Both take the
datum ONLY -- `parts.subList(2, ...)` never reaches them -- so a runtime string
datum becomes a `simple-error` whose `format-control` is the control string and
whose `format-arguments` are nil. The report then renders that control with no
arguments, which is where the NILs come from.

## Fix direction

Per CL, a STRING datum is a format control and the rest are its format
arguments, whatever the datum's shape in the source. The runtime renderer now
exists (`%fmt-render`, `.kb/format.md`), so the string arm can render properly:

- thread the argument forms into `expandObjectSignal` and make its `stringCase`
  build the message with `(%fmt-render datum (list args...))` -- and store them
  as the condition's `format-arguments` rather than nil, so a handler that reads
  `simple-condition-format-arguments` sees them;
- give the `%error-runtime` dispatcher (`runtimeErrorDefuns`) the same string
  arm: with a string datum its second argument is a format-argument list, not an
  initarg plist;
- the interpreter's runtime-type-dispatch branch needs the same string test
  before the symbol test.

Watch: `warn` prefixes `WARNING: ` and `signal` wraps a `simple-condition`; the
restart-mode (`signalHook`) arms synthesize the instance the handlers see, so
all of them need the rendered message, not the raw control. Cover all four
backends plus a ci-spec case.
