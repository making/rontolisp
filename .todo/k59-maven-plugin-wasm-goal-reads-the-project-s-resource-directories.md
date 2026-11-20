# k59. Maven plugin `wasm` goal: the project's resource directories

Difficulty: Low

A Java program's resources are found in its source tree, then under the roots of its
source path (k53, `.kb/java-frontend.md`, "Resources"). A Maven project keeps them in
`src/main/resources`, which the `wasm` goal (`rontolisp-maven-plugin`, `WasmMojo`,
`WasmProgram`) does not pass, so `Main.class.getResourceAsStream("app.properties")` of
a project built with `mvn` is `null` unless `<javaSourcePath>` names the directory.
Decide where the project's resource directories go (javac's class path order: the
resources beside the classes, ahead of the dependencies), pass them, and pin it in the
plugin's tests (outside the root reactor, `.kb/session-workflow.md`).
