# 79: cl-who unit 3 -- setf-function definitions `(defun (setf name) ...)`

Parent: `.todo/76`. One session (small).

## What cl-who uses

```lisp
(defun (setf html-mode) (mode)
  (ecase mode (:sgml ...) (:xml ...) (:html5 ...)))
```

so that users can write `(setf (html-mode) :html5)` to switch output mode
(the supported alternative to dynamically rebinding `*html-mode*`, which is
deferred -- see `.todo/76`). Today `(defun (setf x) ...)` throws
`LispCons cannot be cast to LispSymbol` in `evalDefun`.

## Scope

1. **Parse `(setf name)` as a function name** in `defun` (interpreter +
   compile path). Store it under a distinct setf-function key (e.g. the
   internal name `(setf html-mode)` -> a mangled symbol like
   `%setf-html-mode`), NOT in the ordinary function namespace.
2. **Make `(setf (name args...) value)` expand to the setf-function call**:
   in `LispMacroExpander.expandSetf`, when the place head `name` has a
   registered setf-function, expand `(setf (name . args) val)` to
   `(funcall #'%setf-name val . args)` (value first, per CL setf-function
   convention: the new value is the LAST required param of the setf lambda
   list, received as the first argument). Returns the value form.
   This composes with the existing accessor-position registry
   (`.kb/defstruct.md`) -- add setf-functions as another place kind.
3. **`#'(setf name)` as a value**: `(function (setf name))` resolves to the
   setf-function funcref. (`symbol-function`/`fboundp` of a `(setf ...)` name
   is a non-goal.)

For the cl-who load itself the definition merely needs to not error; the
`(setf (html-mode) ...)` call site is user-facing, but implement the call-site
expansion too so the exported feature actually works.

## Registry threading

The set of setf-function names is a compile-time fact (like defstruct
accessors). Collect them in the same top-level pass that feeds `expandSetf`
so all three compile consumers (UserMacroExpander + JVM + WASM) and the
interpreter agree. Definition order: a `(setf (html-mode) ...)` before the
`(defun (setf html-mode) ...)` is not required by cl-who (`:serial t`, defun
precedes use).

## Acceptance

All four backends:

```lisp
(defvar *mode* :xml)
(defun (setf my-mode) (m) (setq *mode* m))
(setf (my-mode) :html5)
*mode*                        ; => :HTML5
(funcall #'(setf my-mode) :sgml)
*mode*                        ; => :SGML
```

`ci-spec.yaml` case + native `CiSpecE2eTest`; doc note on `defun` /`setf`
(setf-function subset) + `.kb/defstruct.md` (or a setf kb) update.
