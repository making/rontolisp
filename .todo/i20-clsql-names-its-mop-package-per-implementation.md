# i20. clsql names its MOP package per implementation

Difficulty: Medium

Measured 2026-11-04 on the interpreter. `uffi` loads since `uffi-cffi.asd`, so `clsql.asd` runs through.
`(ql:quickload "clsql")` then stops at `sql/kmr-mop.lisp:51`: `The function CLSQL-SYS::FINALIZE-INHERITANCE
is undefined`. `sql/package.lisp`'s `defpackage` uses a MOP package only per `#+clsql-sbcl-mop` and friends,
so here `clsql-sys` sees none of the metaobject names.

A Tier 4 rewrite over `closer-mop` is the first candidate (`.kb/asdf.md`). Unmeasured past that point:
`sql/metaclasses.lisp`, `ooddl.lisp` and `oodml.lisp` subclass `standard-class` and specialize
`compute-effective-slot-definition`, and each SQL backend loads a foreign library.
