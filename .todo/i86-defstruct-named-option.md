# i86. `defstruct`'s `:named` option

Difficulty: Low

`:named` (a bare keyword option, CLHS defstruct) is refused in every shape (measured 2026-11-06):

    (defstruct (node (:predicate queue-node-p) :named) a)        ; DEFSTRUCT option is not supported: (:NAMED)
    (defstruct (foo (:type list) :named) a)                        ; same
    (defstruct (foo (:type vector) :named) a)                      ; same

Without `:type` it changes nothing (SBCL accepts it). With `:type list`/`vector` it puts the
structure name in element 0 and enables the predicate, the shape `(:type list)` already has
(`typedList`, `.kb/defstruct.md`).

Consumer: queues 20170124 `priority-queue.lisp:17` (`queues.priority-queue`), a petalisp
dependency (petalisp.core needs it next to typo, see i90).

Fix in `LispMacroExpander`'s defstruct option parse (the three "option is not supported" sites);
extend the defstruct ci-spec case with the three shapes.
