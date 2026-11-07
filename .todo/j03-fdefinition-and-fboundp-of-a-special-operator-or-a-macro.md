# j03. fdefinition of a special operator or a macro, and a computed fboundp of one

Difficulty: Medium

typo's next stop (measured 2026-11-07, after the initialization-protocol fix): in
`vm/data-and-control-flow.lisp`, `(define-fnrecord if ...)`, `or` and `and` reach
`ensure-fdefinition`, which asks `(fboundp name)`, then `(fdefinition name)`. CLHS
`symbol-function`/`fdefinition`: a name globally defined as a macro or a special operator is
fbound and answers an object of implementation-dependent nature. SBCL 2.2.9 answers
`#<FUNCTION (:SPECIAL IF)>`, `#<FUNCTION (:MACRO OR)>`, one per name, the first signalling
when called.

With the symbol held as data (`(intern "IF")`), today:

| | interpreter | JVM, P1, component |
|-|-|-|
| `fboundp` | T | NIL |
| `fdefinition`, `symbol-function` | undefined-function | undefined-function |

A literal `(fboundp 'if)` folds to T everywhere; a literal `(fdefinition 'if)` is a compile
warning and an undefined-function on the compile paths. So the interpreter stops at `if`; the
JVM and P1 stop at `or`, where `fboundp` answers NIL and typo signals its own "The symbol OR
denotes a macro, not a function." With `ensure-fdefinition` patched to answer a closure per
operator, typo loads whole on the interpreter.

Needed on all four: `symbol-function`/`fdefinition` of a special operator or a macro answers
one function object per name (typo keys a hash table by it), signalling when called; a computed
`fboundp` answers t for one on the compile paths too (`.kb/symbol-runtime-api.md`,
"`macro-function` / `special-operator-p` PARTITION the operators"). The compile paths know
those names at run time only through `special-operator-p` and `%%macro-fn`'s baked table
(`LispPreludeLibrary`): ask them on the registry miss only, so a found function pays nothing,
and measure which programs gain the table before choosing the gate.

Done when: the table above answers SBCL's on all four (a ci-spec case) and typo loads on the
interpreter and the JVM; record where petalisp stops next.
