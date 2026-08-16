# 420. `.asd` parse: a top-level `progn`, a symbol component name, and an impure `defparameter`

Difficulty: Low

Three widenings to `eval/AsdfSystems.parseAsdSource`, all found by the jose
spike (`.todo/419`) on ONE file, `cl-json.asd`, and all three general -- each is
a shape real ASDF accepts because it EVALUATES the file, which the
defsystem-as-data front end deliberately does not.

Each was reproduced by `(ql:quickload "cl-json")` and each was fixed with a
throwaway patch during the spike, so the shape of the fix is known.

## 1. A top-level `(progn ...)` is an unsupported form

```lisp
#-no-cl-json-clos
(progn
  #+(or mcl openmcl cmu sbcl clisp ecl scl lispworks allegro abcl genera)
  (pushnew :cl-json-clos *features*))
```

The outer reader conditional survives (rontolisp has no `:no-cl-json-clos`), the
inner one drops every implementation keyword, and what reaches the parser is the
literal `(PROGN)` -- which is not on the recognized-form list, so the file is a
hard error naming a form that declares nothing.

`progn` around a feature announcement is the ordinary way to gate several forms
on one `#+`, so the fix is to FLATTEN it: splice a top-level `progn`'s body back
onto the front of the form worklist and let each subform hit the same
recognizer. Recursion falls out (a `progn` inside a `progn`), an empty one
becomes a no-op, and an unsupported form INSIDE a `progn` still errors by its
own name rather than by its wrapper's.

Note the consequence the announcement rule already implies: `:cl-json-clos` is
not pushed here, exactly as on an implementation outside that list, so
`src/objects.lisp` stays out of the build and the decoder answers alists. That
is the branch jose relies on (`aget headers "alg"`), so it is the correct one --
see `.kb/declarations-type-checks.md`, which records the same file's float
lattice taking its `:cl-json-only-one-float-type` branch for the same reason.

## 2. A component name written as a symbol

```lisp
(defsystem :cl-json/test
  :components ((:module :t
                :components ((:file "package") ...))))
```

`parseComponent` requires a `LispString`. Real ASDF runs every component name
through `coerce-name`, which accepts a string designator and `string-downcase`s
a symbol -- so `:t` names the `t/` directory. Accept a `LispSymbol` the same
way (strip a leading keyword colon, downcase), and keep the error for anything
that is neither.

This matters even for a system nobody loads: the whole `.asd` is parsed as one
unit, so `cl-json/test` being unparseable kills `cl-json` too.

## 3. A `defparameter` whose value is not pure data

```lisp
(defparameter *cl-json-directory* (system-relative-pathname "cl-json" ""))
```

`defineParameter` -> `evalDataForm` denies by default, which is right for a
value that is CONSUMED (a `#.` resolved out of it, a `:pathname`). But this one
is never read by any `.asd` form; it exists for the test system's own runtime.
Failing the whole file over a binding nothing asks for is the deny landing in
the wrong place.

Move the denial to the USE site -- the rule `#.` already follows in this file
(`.kb/asdf.md`: "the CONSUMER decides, never the evaluator"). Record the name as
unevaluable, and have `evalDataForm`'s reference case raise the existing error,
naming the parameter, only when a form actually reads it. What must NOT happen
is a silent nil: an unevaluable parameter read as a value is the shape that
turns into a wrong pathname.

## Definition of done

`(ql:quickload "cl-json")` parses `cl-json.asd` and loads the system; the three
shapes are pinned in `AsdfSystemsTest` (a top-level `progn` -- empty, nested,
and one holding an unsupported form that still errors by its own name; a
`(:module :t ...)` resolving to `t/`; an impure `defparameter` that parses and
then errors NAMING ITSELF when a later option reads it), and `.kb/asdf.md`'s
recognized-form list is updated with the same "the consumer decides" wording it
already uses for `#.`.
