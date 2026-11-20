# k53. Java source path: the resources a library or a program reads

Difficulty: Medium

k44 measured that a sources jar DOES hold resources (`.kb/java-frontend.md`, "A library from
the source path"): maven-source-plugin packs `src/main/resources`, so the root a class came
from is where its resources are, not the binary jar. A source launcher finds a resource in
its source tree too. Nothing on YAVI's path reads one, so no API was added.

Open when a library or a program needs one:

- `Class.getResourceAsStream` / `getResource` (refused by name today:
  `Class.getResourceAsStream(String) is not supported`), `ClassLoader.getResourceAsStream`,
  `ResourceBundle`, `ServiceLoader`. Decide what embeds the octets: a literal name resolved
  while lowering (relative to the class's package, `/` for the root) and embedded, as the
  Clojure front end embeds `io/resource`'s (`.kb/clojure-frontend.md`, "clojure.java.io");
  a computed name is looked up when the program runs.
- Which roots: the program's tree (the launcher's rule, measured: `data.txt` beside
  `app/Main.java`, `/top.txt` at the root), then the source path in order; the playground's
  uploads as the tree.
- Seen beside it: `java.util.ResourceBundle.getBundle("m")` written qualified is
  `cannot find symbol: variable java`, imported it is `the class java.util.ResourceBundle is
  not supported`; a JDK class the subset lacks should be refused by name either way.
