# m97. Java `@WitExport` and Clojure `rontolisp.wit/export`: exporting a resource

Difficulty: High

A Common Lisp program exports a resource of an exported interface (`.kb/wit.md`, "A resource
the world exports"); `WitExportDirective.describe` refuses one for a front end that binds
its own names ("which this language does not export"), so the Java and the Clojure front
ends refuse the world.

- **Java**: the natural value is an object of a class of the program. A member class of the
  interface's class named after the resource, its constructor the WIT constructor, its
  instance methods the methods (`self` the receiver), its static methods the static
  functions; `close()` (`AutoCloseable`) the destructor. The export forms already take any
  value as the representation: the lowering needs `describe` to answer the resource's
  functions (labels `[constructor]r` / `[method]r.m` / `[static]r.f`, the member names
  `r-new` / `r-m`) and wrappers binding them to the class. Mirror of what `@WitImport` does
  with a member interface (`.kb/java-frontend.md`, "WIT imports").
- **Clojure**: a var per function, named by the member name; the representation any value.
- `describe` gives a handle's shape as `HANDLE` with `resource` and `borrow`; an export's
  handle of an exported resource is the value, which the front end must tell from an
  imported one's integer.
- A handle of a resource the component imports (`.kb/wit.md`, "A resource the component
  imports") crosses a Common Lisp export as that integer; `describe` refuses it for the
  front ends (`WitExportDirective.NOT_IMPORTED`). Java's natural value is the object of the
  `@WitImport` member interface standing for the resource, Clojure's the integer; the
  wrapper already releases a borrowed one.
- Docs: `doc/en/java/wit.md` and `doc/ja/java/wit.md` ("The types of an export",
  Limitations), `doc/*/clojure/reference/wit.md`.
