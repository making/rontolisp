# k58. Java resources: a computed name, URLs, bundles and services

Difficulty: High

k53 reads a resource whose name is a constant: found while lowering, its octets in the
output (`.kb/java-frontend.md`, "Resources"). Still refused by name:

- A name computed when the program runs (`getResourceAsStream of a resource named by no
  constant`). What answers it: the roots read at run time (the interpreter and the JVM
  through the host; wasm through a preopen, and a jar through a zip reader -- the Clojure
  front end has one in `clojure.lisp`, "Jars read when the program runs", which would have
  to become a library both front ends splice), or a table of what the program can name
  (every resource of the roots: unbounded for a tree whose top is a home directory).
  Measure what a real library computes first.
- `getResource` / `getResources` (no `java.net.URL` in the subset), `ClassLoader.getParent`.
- `java.util.ResourceBundle` (a `.properties` lookup by locale suffixes: computed names,
  so after the item above, or a lowering that enumerates the suffixes of a constant base
  name), `java.util.Properties.load` if missing, `java.util.ServiceLoader` (reads
  `META-INF/services/<interface>` and instantiates by name: needs a class by name, which
  the subset refuses as reflection).
