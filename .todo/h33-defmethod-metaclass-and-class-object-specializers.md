# h33. `defmethod` rejects metaclass names and class objects as specializers

Difficulty: Medium

Found 2026-10-30 probing `serapeum` (and `spinneret`, which needs serapeum).
Errors come from `LispMacroExpander.parseSpecializer`.

1. A metaclass name: `parse-declarations` (serapeum/macro-tools dependency) has
   `(:method ((typespec class) args lexenv) ...)`.
   `DEFMETHOD: unknown specializer CLASS`. Same for `standard-class`. Needed:
   `class`, `standard-class`, `built-in-class`, `structure-class`, `standard-object`,
   `structure-object`, and the runtime `class-of` of a class object must match them.
2. A class OBJECT as specializer: serapeum `threads.lisp:163`
   `(defmethod monitor ((object #.(class-of (bt:make-recursive-lock)))) ...)`:
   `DEFMETHOD: unsupported specializer #<STANDARD-CLASS INTEGER>`. A literal class object
   should mean the same as its name.
   Note the second problem in that form: a bt lock IS an integer handle here, so the
   method would specialize on INTEGER. Owned by h37 (a lock needs its own type).
3. `(eql (find-class 'x))`: a computed eql specializer is rejected
   ("keyword/symbol/number/character literals only").

All four backends (the dispatcher generation in `.kb/clos.md`).
