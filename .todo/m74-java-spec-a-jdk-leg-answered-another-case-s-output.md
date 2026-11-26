# m74. java-spec: a JDK leg answered another case's output

Difficulty: Medium

Seen once, 2026-11-26 (a full `./mvnw test` over integration b7264e6ce's parent): a
`JavaSpecE2eTest` case failed at its JDK leg -- the expected output is made under the JDK --
because what that leg printed was another case's `app.Main` (the JDBC case's) output. The same
class run alone passed (842 of 842), and a full run before passed. So two cases' JDK legs can
see each other's files or classes when they run together.

Where to look: `src/test/java/am/ik/rontolisp/e2e/JavaSpecE2eTest.java`, the standalone cases'
`write(stem + "-JDK")`, `classPath(..)` and the library's `javac -d` (around line 210-260): a
directory or class path shared by two cases whose program class has the same name
(`app.Main`), a `@TempDir` reused across dynamic tests, or the source launcher's class
resolution from a directory another case writes. Reproduce by running the standalone cases
concurrently (or repeating the class) before changing anything, then a failing test first.

A flaky java-spec case fails CI's e2e job, so this goes before feature work.
