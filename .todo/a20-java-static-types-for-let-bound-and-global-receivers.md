# java: static types for let-bound and global receivers

Difficulty: Medium

Follow-up of a13 (the one resolution model; .kb/java-interop.md). Measured
2026-09-26 with `--warn-java-reflection`: examples/jvm/java-interop.lisp resolves
8 of its 17 java: sites before they run, swing.lisp 13 of 53. Nearly every site
left to run time is a receiver held in a variable -- a `let` local bound to a
`java:new` / resolved call, or a `defvar` global -- because a13 types a variable
only through `(declare (type (java:object "C") v))`.

Plan (both backends through the same pass, `compiler/JavaDeclarations`):
- let-initializer inference: a `let`/`let*` variable never assigned in its scope
  (no setq/setf/psetq/multiple-value-setq/incf... on it, closures included) takes
  its initializer's static type (`JavaSiteResolver.typeOf`, so the pass needs the
  lookup). Clojure infers the same.
- Globals: `(declaim (type (java:object "C") *v*))` / `proclaim`, in program order
  -- the interpreter keeps the proclaimed types across top-level forms, the compile
  path reads the declaims in order, so a site sees the same proclamations on both.
  Inferring a defvar's type from its init form is unsound (any form may setq it).
- Keep a rewritten reference an explicit `(the (java:object "C") v)`: the resolver
  still reads the site alone. A type with no class name (a Lisp kind set) needs a
  spelling -- `(java:object "int")` already means {integer}.
- Re-measure the two examples; pin with JavaDeclarationsTest and a both-backend
  case in JavaInteropTest / JvmJavaInteropCompilerTest.
