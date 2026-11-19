# k32. Java source path: the Maven plugin, a session and the playground

Difficulty: Medium

What k24 (2026-11-19) left of the surface of `.kb/java-frontend.md`, "A library from the
source path". The command line has `--java-source-path` and `--java-source-dep`; these do
not read a library yet:

- **The Maven plugin's `wasm` goal** (`rontolisp-maven-plugin/`, `WasmMojo`,
  `cli/WasmSourceCompiler`): pass the project's compile and provided dependencies as the
  sources jars of their artifacts. Design: `requiresDependencyResolution =
  ResolutionScope.COMPILE`, each artifact of scope compile, provided or system asked again
  with the classifier `sources` through the build's `RepositorySystem` and its remote
  project repositories; one that has none is a warning, as a dependency of a
  `--java-source-dep` is, since javac needs only the class file and the program may never
  name it. A `<javaSourcePath>` parameter for directories and jars beside them.
  `WasmSourceCompiler` needs `javaSourcePath(List<String>)` as `JvmSourceCompiler` has;
  the roots join the goal's up-to-date check (`WasmProgram`'s configuration and stamps).
  Pin with `MavenBuildE2eTest` (`.kb/session-workflow.md`, "Builds outside the root
  reactor"). Test and optional dependencies are not read.
- **A session** (`java> `, `JavaSession`, `eval/SourceSession`) and `-e` read no other
  file: with a source path given they could name a library's classes
  (`JavaClasses.programClass` already asks the path when there is no starting file; the
  session's `JavaFiles` is `NONE`).
- **The playground** has no file system and no resolver: decide whether an uploaded
  sources jar is a root (`SourceLoader.listArchive` answers `null` there today).
- **A class declared in a file named after another class** is found only once that file
  was read (measured: `lib.Hidden` in `lib/Lists.java` is `cannot find symbol` until
  `Lists` is named). A jar's entry list could be searched for the declaration.
- **Resources**: a sources jar holds none, and `ResourceBundle`,
  `Class.getResourceAsStream` and `ServiceLoader` are refused by name. If a library needs
  them, the binary jar is where they are; decide then what embeds them.
