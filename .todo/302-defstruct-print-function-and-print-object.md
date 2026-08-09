# `defstruct` rejects `(:print-function ...)` / `(:print-object ...)`

Difficulty: Low

```console
$ rontolisp -e '(defstruct (s (:print-function (lambda (obj stream depth) (declare (ignore depth)) (print-unreadable-object (obj stream :type t))))) v)'
DEFSTRUCT option is not supported: (:PRINT-FUNCTION (LAMBDA ...))
```

`:print-function` and its modern sibling `:print-object` are the last two
`defstruct` options the expander refuses outright (`:conc-name`, `:constructor`,
`:predicate`, `:copier`, `:include`, `:type` are all in). The refusal is a LOAD
failure, so a library that merely wants a pretty printer for a struct nobody
prints becomes unloadable.

Found through map-set (`map-set-20230618-git`, BSD 3-Clause, Robert Smith) —
one 90-line file, a transitive dependency of myway and therefore of ningle
(`.todo/300`):

```lisp
(defstruct (map-set (:constructor make-map-set ())
                    (:copier nil)
                    (:print-function (lambda (obj stream depth)
                                       (declare (ignore depth))
                                       (print-unreadable-object (obj stream :type t)
                                         (format stream "of ~D element~:P"
                                                 (map-set-size obj))))))
  ...)
```

Nothing in myway or ningle ever prints a map-set, which is the point: the option
costs a whole library for output no program asks for.

## The seam already exists

`print-object` on a CLOS class is honoured by every printing operator since
todo-199 (`.kb/clos.md`, the `%print-object-str` hook), and
`print-unreadable-object` works. So this is registering the struct type's
printer through the same hook, not new printer machinery:

- `(:print-object FN)` — `FN` is called with `(object stream)`.
- `(:print-function FN)` — the CLtL1 spelling, called with
  `(object stream depth)`; `depth` is the current `*print-level*` depth, and
  passing `0` is what implementations do when they do not track one.
- Both take a function DESIGNATOR (a symbol or a lambda expression), and both
  are mutually exclusive with `:type` (a typed struct is a vector; it has no
  identity to dispatch on) — reject that combination rather than ignoring it.

`print-unreadable-object`'s `:type t` prints the type name, which for a struct
must be the struct name (`MAP-SET`), the same name `type-of` answers.

Fall back to parse-and-ignore only if the hook turns out not to reach structs on
some backend — and then say so in `.kb/defstruct.md` with the reason, because a
struct that silently prints `#S(MAP-SET :TABLE ... :INDEX ... :SIZE 1)` where CL
prints `#<MAP-SET of 1 element>` is a divergence a program can see.

## Work

- Accept both options in `LispMacroExpander.expandDefstruct` and route them
  through the `print-object` hook, on all four backends.
- `~:P` (the plural directive map-set's printer uses) — confirm it renders, or
  add it (`.kb/format.md`).
- Pins: a `ci-spec.yaml` case printing a struct with each option, and
  `LispEvaluatorTest` for the designator forms (symbol and lambda) and for the
  `:type` rejection.
- `.kb/defstruct.md`: the two options, the `depth` argument convention, the
  `:type` exclusion.

## Done when

`(ql:quickload "map-set")` loads the verbatim upstream file on all four
backends and a map-set prints as `#<MAP-SET of N element(s)>`.
