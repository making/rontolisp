# g06. `PathCitationTest` fails on the f86 citation left in a fixture

Difficulty: Trivial

Found during the g03 final full-suite run (2026-10-13, worktree
rontolisp-wt-g03): `PathCitationTest#noTodoItemIsCitedOutsideTheNotes` expects no
`.todo/` number cited outside `.kb/` and `.todo/`, but
`src/test/java/am/ik/rontolisp/DolistResultScopeFixture.java:5` cites
`.todo/f86` (left by `e54800980`, which closed f86 without removing the pointer).
Reproduces on clean develop without the g03 changes (verified by stash).

## Plan

1. Replace the fixture comment with the fact itself (or a pointer at the `.kb/`
   file that holds it), per the test's message.
2. Re-run `PathCitationTest`.
