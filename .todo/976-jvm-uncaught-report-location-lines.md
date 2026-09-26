# A compiled .class's uncaught report does not say where it happened

Difficulty: High

The interpreter prints location lines under `Unhandled condition: <report>` -- the innermost
form read from a named file with the named function holding it, and one line per async
boundary with its await site (`compiler/UncaughtReport.atLine` / `asyncLine`,
`.kb/error-handling.md` "An uncaught condition reports ONE line"). A `.class` / `.jar` /
`.war` output prints the report line alone.

Goal: the same lines from `JvmUncaughtHandler`, byte-identical to the interpreter's for the
same program, with an async body's hop (the virtual thread's exception rethrown by the await
helper) included.

What is known (2026-09-26):

- The generated code carries no `LineNumberTable` and no `SourceFile` attribute; nothing in
  `am.ik.jvm` writes either. The line has to come from the compile path's position table
  (`SourceProvenance.locate`, open during `compileToFile`) at emission time.
- Every pass that moves code offsets after emission would have to remap a line table:
  `BranchRelaxer`, `JvmClassShaker`, `JvmClassSplitter`, `StackMapAugmenter`, and the method
  splitting of `.kb/jvm-method-size-limits.md`.
- The thrown `RuntimeException`s keep writable stack traces (the handler empties them before
  rethrowing), so with a `LineNumberTable` the handler can read the innermost program frame's
  line from `getStackTrace()`; the function name needs the mangled-method -> Lisp-name map
  (`Fail.$pctERROR-RT-47`), which the function-name table the `#<function NAME>` printer
  reads may already answer.
- An alternative that avoids offset tables: an exception-table entry per user function that
  notes its name on the way out (the interpreter's throw-path model), plus a per-call-site
  line only where a signal is emitted. Measure both against class size (`JvmSizedMainTest`,
  the size report) before choosing.

Pin with a test beside `RontoLispCliStreamsTest#anUncaughtConditionNamesTheInnermostFormAndTheFunctionHoldingIt`
and the async case, run on `java Prog` and `java -jar`.
