# SWANK: `M-.`, cross references, the inspector and macrostep

Difficulty: Medium

`rontolisp --swank` (`.kb/swank.md`) answers `swank:find-definitions-for-emacs`, `xref` and
`xrefs` with nil (SLIME: "No known definition"), and `swank:init-inspector` and the
`slime-fancy` contribs' `swank-macrostep:macrostep-expand-1` / `macro-form-p` abort as
undefined functions.

Direction:

1. `M-.`: the interpreter already records, for each definition, its lambda list and
   documentation (`LispEvaluator.Definition`); add where it was read -- a `LocatedCons`
   carries the file and line, a SWANK compile request knows the buffer and offset -- and
   answer `((dspec (:location (:file f) (:position p) nil)) ...)` in SWANK's shape. Built-in
   operators answer none.
2. `xref` (`:calls`, `:calls-who`, `:references`, `:binds`, `:sets`, `:macroexpands`): the
   undefined-function walk of a compile (`CommonLispSwank.undefinedFunctions`) already
   visits every call site; recording callers per definition answers `:calls` and
   `:calls-who`.
3. The inspector (`init-inspector`, `inspector-nth-part`, `inspector-pop`, ...): a value's
   parts as SWANK's `emacs-inspect` lists them -- a cons, a vector, a hash table, an
   instance's slots, a symbol.
4. macrostep: `macro-form-p` and `macrostep-expand-1` over `macroexpand-1`.

Measure each against SWANK 2.32 on SBCL with the driver in
`.todo/artefacts/h18-common-lisp-swank-server/`, message for message, and extend
`cli/SwankServerTest`.
