# i91. `defclass` under a conditional on the compile paths

Difficulty: High

The compile paths refuse a `defclass` that is not top level (`.kb/clos.md`: "No program needing
it is known"). snooze needs it (measured 2026-11-06, JVM; the interpreter runs it and matches SBCL
2.2.9 on `handle-request`): `common.lisp`'s `define-content` expands to

    (progn ...
      (unless (find-class ',supertype nil) ... (defclass ,supertype (snooze-types:content) ()))
      (defclass ,type (,supertype) ())
      ...)

and a compile stops with `DEFCLASS is only supported as a top-level form`.

The class is capture-free and its name is literal; only its EXISTENCE is conditional. Options:
hoist a capture-free `defclass` whose only enclosing forms are conditionals to top level and keep
the test as a run-time `find-class` (the class table answers absent until the branch runs), or a
run-time `ensure-class` path for classes defined off top level. Decide with the
`expandTopLevelDefinitions` walk and the metaobject table in mind; `ContextlE2eTest`'s `layer-gc`
(definitions inside `loop`) is the other known consumer.

Done when: `(ql:quickload "snooze")` plus a `defroute` compiles and answers the interpreter's
three `handle-request` results on the JVM (and WASM if snooze's other parts compile there).
