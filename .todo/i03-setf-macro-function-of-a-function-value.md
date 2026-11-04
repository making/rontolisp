# i03. `(setf (macro-function 'x) <function>)` beyond an alias

Difficulty: Medium

Measured 2026-11-04:
`setf MACRO-FUNCTION only supports aliasing a user macro -- (setf (macro-function 'new) (macro-function 'existing))`.

`.kb/defmacro-backquote.md` recognizes only the alias shape syntactically. log4cl defines its
deprecated forwarders with a real expander (`src/package.lisp:137`):

```lisp
(setf (macro-function ',name)
      (lambda (&rest args)
        (log4cl-style-warning "Macro ~S is deprecated~^. Use ~A instead" ...)
        (apply (macro-function ',from-name) args)))
```

So `(ql:quickload "log4cl")` stops at `package.lisp:185`, ahead of what `.todo/152` recorded for it,
and sento with it. Also note: a macro function takes `(form env)`.

Needed: the value form runs at macro time and its function becomes the expander. On the interpreter
that is a table write of the closure. On the compile paths `UserMacroExpander` replays the form
into the macro-time evaluator like a `defmacro`, then drops it, the same owner as the alias case.
A form the replay cannot evaluate stays a hard error naming it. Pin with a `(lambda (form env) ...)`
expander; the log4cl walk continues at `.todo/h97`.
