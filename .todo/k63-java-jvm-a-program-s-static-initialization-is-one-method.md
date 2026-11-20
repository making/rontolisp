# k63. Java front end on the JVM: a program's static initialization is one method

Difficulty: Medium

Found by k55 (2026-11-20). A Java program runs inside one top-level form,
`(%reporting-uncaught line exit init.. (main))` (`JavaLowering.uncaught`), and the JVM backend
compiles a top-level form into one method: chunking splits only between forms, and the
outliner leaves whole a form with a lexical exit (`.kb/jvm-method-size-limits.md`, "The top
level is chunked"). The `java-spec.yaml` corpus, compiled as one program, stopped at
`method _top$0: method code exceeds the JVM's 65535-byte limit: 65740` once k55's case was
added: 2,296 settings of the interned strings were inside that form. k55 moved them out
(`JavaLowering.internings`, each a top-level form ahead of the initialization: none can
throw); the form is 44,218 B now (`_top$1`), the class objects, the constant fields and
every static initializer of the corpus's classes with `main`'s call.

A program with a few thousand static fields or initializers reaches the limit the same
way, and only on the JVM. Leads, to measure on a generated program of N classes with static
state: the settings that cannot throw (the class objects, `|Object%class|` .. , and the
constant fields) ahead of the form as the strings are; the initialization as functions of
their own, a chunk each, called from the form; or the outliner told that the form's exit is
the reporter's and the body's statements may be cut. Compare the size of a program with no
such initialization before and after (it must not move), and check the order of the
initializations a JDK shows (`static-initialization-runs-at-first-use-where-it-shows`).
