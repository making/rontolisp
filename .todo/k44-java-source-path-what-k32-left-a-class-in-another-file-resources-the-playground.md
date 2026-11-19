# k44. Java source path: what k32 left (a class in another class's file, resources, the playground)

Difficulty: Medium

k32 (2026-11-20) gave the source path its surfaces: the Maven plugin's `wasm` goal reads a
project's dependencies from their sources jars, and `-e` and a session read the roots
(`.kb/java-frontend.md`, "A library from the source path"; `.kb/jvm-export.md`, "The plugin's
`wasm` goal"). Left, none on YAVI's path (`.todo/k33`):

- **A class declared in a file named after another class** is found only once that file
  was read (measured in k24: `lib.Hidden` in `lib/Lists.java` is `cannot find symbol`
  until `Lists` is named). A jar's entry list could be searched for the declaration;
  javac finds one only through the class file, so decide first whether a source path
  should find what javac's `-sourcepath` does not.
- **Resources**: a sources jar holds none, and `ResourceBundle`,
  `Class.getResourceAsStream` and `ServiceLoader` are refused by name. If a library needs
  them, the binary jar is where they are; decide then what embeds them.
- **The playground's uploaded `.java` files as a root.** Its files are text
  (`rontoPutFile(name, content)`) and it has no resolver, so a sources jar is no root
  there (decided in k32). A program of the page has no entry file, so an uploaded
  `geo/Place.java` is not read either: the uploads as a directory root
  (`SourceStandards.withJavaSourcePath` over a loader answering `isDirectory` for it, in
  `PlaygroundRepl` and `RontoPlayground`'s compile path alike) would give the page a
  program of several classes. Needs `./mvnw -Pweb compile` and `web/playground-check.js`.
