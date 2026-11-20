# k69 probes

Written here; run under JDK 25.0.5. What they showed is in `.kb/java-frontend.md`,
"Resources", and the answers the corpus keeps are lines of `java-spec.yaml`.

- `urls/`: `java Grid.java` and the others print what `new URL(..)`, its constructors of
  parts, `equals`, `sameFile`, `toURI` and `URI.toURL` answer; `G4.java` every ASCII
  character in a host.
- `places/`: the URL a launcher's loaders answer for a file of the tree, of a class path
  directory and of a jar, `getResources`' order, the loaders' parents. Run from `tree/`
  as `java -cp ../libcls:../lib.jar app/Main.java`, with `lib/Lib.java` compiled into
  `libcls/` and `app/data.txt`, `top.txt`, `both.txt`, `app/sub/deep.txt` written in
  the tree, `libcls/` and the jar (`printf` lines of the session, one word each).
- `escapes/`: every printable ASCII character in a file's name in the tree, a class path
  directory and a jar (`python3` wrote `n<c>n` in each), and one name past ASCII.
- `bundles/`: a member class as a bundle (`app.Outer$Texts`, with
  `app/Outer$Texts_en_GB.properties`), the same name with dots, and `ServiceLoader` of a
  `Class<?>` and a `Class<? extends ..>` (`META-INF/services/java.lang.Runnable` naming
  `app.Main$Run`).
