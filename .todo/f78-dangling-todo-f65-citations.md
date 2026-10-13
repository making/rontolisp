# f78. Dangling `.todo/f65` citations in source and test comments

Difficulty: Low

Found by the parent full suite (2026-10-13, tree `dc585b9cc`):
`PathCitationTest.noTodoItemIsCitedOutsideTheNotes` fails — f65's worker left
`.todo/f65` citations in comments, and the f65 item has since closed:

- `src/main/java/am/ik/rontolisp/clojure/ClojureInteropLowering.java:846`
- `src/main/java/am/ik/rontolisp/clojure/ClojureJavaFaces.java:465`
- `src/main/java/am/ik/rontolisp/codegen/jvm/JavaBridgeTemplate.java:2682-2723`
- `src/main/java/am/ik/rontolisp/eval/JavaInterop.java:2121`
- `src/test/java/am/ik/rontolisp/clojure/ClojureInteropTest.java:210-231`

## Plan

1. Replace each with the fact/measurement itself or a pointer at the `.kb/` file
   that holds it (the rule the test enforces). Comment-only change.
2. Targeted run: `PathCitationTest`.
