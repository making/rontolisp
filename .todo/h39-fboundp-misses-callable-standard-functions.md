# h39. `fboundp` answers NIL for 172 callable standard functions; ~50 more are not functions at all

Difficulty: Medium

Found 2026-10-30 probing `3bmd`: esrap warns `Undefined function ALPHANUMERICP in
expression (ALPHANUMERICP CHARACTER)` at load, because it checks a rule's function with
`fboundp`. `(alphanumericp #\a)` works; `(fboundp 'alphanumericp)` is NIL.

Measured on the interpreter against SBCL 2.2.9's external `cl` functions (636, macros and
special operators excluded): 223 answer `fboundp` NIL. Of those, 172 still evaluate
`(function NAME)`, so they are callable but reported unbound (e.g. `alphanumericp`,
`equalp`, `read`, `write`, `string<`, `copy-tree`, `subst`, `merge`, `search`, `get`,
`symbol-plist`, `make-pathname`, `probe-file`, `type-of`, `sxhash`). The other 51 are
undefined as a function value:

    ADD-METHOD ARITHMETIC-ERROR-OPERANDS ARITHMETIC-ERROR-OPERATION BOOLE BREAK CHARACTER 
    COMPILER-MACRO-FUNCTION COMPUTE-APPLICABLE-METHODS DESCRIBE DESCRIBE-OBJECT DISASSEMBLE 
    DRIBBLE ED ENSURE-GENERIC-FUNCTION FILE-AUTHOR FIND-METHOD FLOAT-PRECISION 
    FUNCTION-KEYWORDS GET-DISPATCH-MACRO-CHARACTER GET-MACRO-CHARACTER INSPECT 
    INVALID-METHOD-ERROR INVOKE-RESTART-INTERACTIVELY LOAD-LOGICAL-PATHNAME-TRANSLATIONS 
    LOGICAL-PATHNAME-TRANSLATIONS MAKE-DISPATCH-MACRO-CHARACTER MAKE-INSTANCES-OBSOLETE 
    MAKUNBOUND METHOD-COMBINATION-ERROR METHOD-QUALIFIERS NAME-CHAR NO-APPLICABLE-METHOD 
    NO-NEXT-METHOD PATHNAME-MATCH-P PPRINT-FILL PPRINT-LINEAR PPRINT-TABULAR 
    PRINT-NOT-READABLE-OBJECT RANDOM-STATE-P READ-DELIMITED-LIST READ-PRESERVING-WHITESPACE 
    READTABLEP ROOM SET-MACRO-CHARACTER SET-SYNTAX-FROM-CHAR SLOT-MISSING SLOT-UNBOUND 
    UPDATE-INSTANCE-FOR-DIFFERENT-CLASS UPDATE-INSTANCE-FOR-REDEFINED-CLASS 
    UPGRADED-ARRAY-ELEMENT-TYPE YES-OR-NO-P 

(The 51 were measured with `(function NAME)`. A few, e.g. `character` and `boole`, may
still work in call position. Re-check each one before you implement it.)

Fix: `fboundp` (literal fold, computed probe, `#'fboundp` wrapper; `.kb/symbol-runtime-api.md`)
must answer T for every callable standard function, on all four backends. A ci-spec case can
loop over the exported `cl` function names and print the ones whose `fboundp` is NIL.
Owned elsewhere: `makunbound`/`compiler-macro-function` (h32), readtable functions
(h53), `slot-unbound` (h41).
