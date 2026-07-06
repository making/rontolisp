# 78: cl-who unit 2 -- CLOS-lite (defgeneric + defmethod)

Parent: `.todo/76`. A minimal slice of the CLOS effort (`.todo/40`), scoped to
exactly what cl-who needs. One session.

## What cl-who actually uses

Only one generic function, with a single un-specialized method:

```lisp
(defgeneric convert-tag-to-string-list (tag attr-list body body-fn)
  (:documentation "..."))

(defmethod convert-tag-to-string-list (tag attr-list body body-fn)
  ...)                       ; standard (all-T) method, no eql/class specializer
```

The docstring notes users MAY add `eql` specializers on the first arg, but
cl-who itself ships only the default method. So the required subset is small.

## Scope (lite)

- **`defgeneric name (lambda-list) ...options...`**: define `name` in the
  function namespace as a generic dispatcher. `:documentation` recorded/ignored;
  other options (`:method`, `:method-combination`, ...) -> clear error for now.
- **`defmethod name (params...) body`**: register a method on `name`. Support:
  - the **default method** (all params unspecialized) -- the common case;
  - **optional bonus**: an `eql` specializer on the FIRST parameter
    (`(tag (eql :br))`) dispatched by `eql` against arg 1, falling back to the
    default method. Class specializers (`(x integer)`) -> error (no class
    system). Keep it to first-arg eql to stay lite.
- Calling the generic dispatches at runtime: try the most-specific matching
  eql method, else the default method; no-applicable-method -> `error`.
- First-class: `#'convert-tag-to-string-list` / `funcall` must work.

Implementation shape: a shared `LispMacroExpander` lowering is preferred over
per-backend codegen (defstruct/flet precedent). `defgeneric` -> define a global
holding a dispatch table (default method + an alist of eql-key -> method
lambda); `defmethod` -> `setf` into that table; the generic name -> a lambda
that reads arg 1, looks up the eql table, else the default. All expands to
existing primitives (lambda/let/if/assoc/funcall), so no Jvm/Wasm class needed.
Splice on the compile path like defstruct (top-level, before Pass 1); register
the runtime helpers in `Environment` for the interpreter.

Cross-cutting note: `defmethod` bodies may reference the generic's params
un-specialized; the desugared lambda list runs through `LambdaLists.expand`.

## Non-goals (leave in `.todo/40`)

`defclass`/slots, class-precedence dispatch, `call-next-method`, `:before`/
`:after`/`:around` qualifiers, `make-instance`, multiple-argument specializers.

## Acceptance

All four backends:

```lisp
(defgeneric describe-it (x))
(defmethod describe-it (x) (list :default x))
(defmethod describe-it ((x (eql :br))) (list :special x))
(list (describe-it 5) (describe-it :br))   ; => ((:DEFAULT 5) (:SPECIAL :BR))
(funcall #'describe-it 9)                  ; => (:DEFAULT 9)
```

Add a `ci-spec.yaml` case + native `CiSpecE2eTest`; doc pages for `defgeneric`
/`defmethod` (state the lite subset) + `.kb/` note (extend `.todo/40`'s kb or a
new `.kb/clos-lite.md`).
