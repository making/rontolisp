# One java: resolution model shared by the interpreter and the JVM compiler

Difficulty: High

Depends on a07's argument-kind select (a pure function of candidates x kinds).
Design reference: Clojure (compile-time class loading, type hints, 1.12 param
tags, *warn-on-reflection*), but with the interpreter applying the same rule so
the choice never depends on the backend.

Plan:
- compiler/: JavaSiteResolver over a JavaClassInfo abstraction; candidate set =
  static receiver type when known, else run-time class; selection = today's cost
  model over argument kinds. Among same-parameter overloads pick the most
  specific return type (covariant bridges; access bridges must be kept).
- Static-type sources, all lexical: literals, java:new of a literal class
  (exact), declared return type of a resolved java: form (upper bound),
  (the (java:object "C") x), (declare (type (java:object "C") v)).
  let-initializer inference for never-assigned variables is a later step.
- Param tags: "max(long,long)", "max(long,_)", (java:new "C(int)" ...).
- Metadata providers: reflection (eval); class files from ct.sym for
  --java-release N plus --java-classpath jars/dirs (a class-file reader in
  am.ik.jvm; works from the native CLI, no reflection).
- Interpreter resolves with the same pass (per lambda/defun/top-level, memo
  per site) and invokes exactly the chosen Executable.
- java:*warn-on-reflection* / --warn-java-reflection for unresolved sites.
- Semantic difference to document: only a receiver typed by an upper bound whose
  run-time class adds a same-named public overload changes selection.
- Tests: corpus resolved by both providers yields identical signatures; the
  receiver-subclass case above.
