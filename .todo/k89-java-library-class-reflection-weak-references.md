# k89. Java library: `Class` beyond what is there, reflection, weak references

Difficulty: High

**Read `.kb/java-frontend-provenance.md` before writing anything.** Split from `.todo/k72`.

A `Class` is a value with the members `doc/*/java/classes.md` lists (k30,
`.kb/java-frontend.md`, "A class as a value"). Refused by name: `Class.forName`, the members,
constructors and annotations of a class (`getMethods`, `getDeclaredFields`,
`getConstructor`, `newInstance`, `getAnnotation`), `getInterfaces()`, `java.lang.reflect`,
`java.lang.invoke`, `java.lang.ref` (`WeakReference`, `SoftReference`, `PhantomReference`,
`ReferenceQueue`, `Cleaner`) and `WeakHashMap`.

- Reflection over what the lowering knows of a program's classes: a member is reachable only
  where the program names it as a constant (as `newUpdater` of a constant field name is,
  `.kb/java-frontend.md`, "java.util.concurrent"); a computed name is refused where the
  program is compiled, as a computed resource name was before k58. Decide what a program that
  names a member by a computed string can have.
- Weak references: a reference the collector of each backend may clear (the JVM's, wasm-GC's,
  the interpreter's); `get()` answering the referent until the program can no longer tell
  is a legal implementation -- measure which programs would see the difference.
