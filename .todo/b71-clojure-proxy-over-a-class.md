# b71. `proxy` over a class: superclass, constructor arguments, `this`, `proxy-super`

Difficulty: High

Every `proxy` of the shcloj4 corpus names a CLASS, never interfaces alone
(measured 2026-10-02, b59): `snake.clj` / `atom_snake.clj`
`(proxy [JPanel ActionListener KeyListener] [] ...)` with `(proxy-super
paintComponent g)` and `(.repaint ^JPanel this)` in the bodies, and
`(proxy [WindowAdapter] [] ...)`; `interop.clj` `(proxy [DefaultHandler] [] ...)`;
the `sequences` test `(proxy [File] ["recent"] (lastModified [] ...))`. Today
each stops at `proxy over a class is not supported yet: <class>` (b59 widened
`proxy` to several interfaces only).

## What it takes

`java:proxy` implements interfaces only: the interpreter's object is a
`java.lang.reflect.Proxy`, which cannot extend a class. A class proxy is a
generated subclass on BOTH paths:

- `java:` surface: a form naming a superclass, its interfaces, constructor
  arguments and the callable; the callable needs `this` (snake calls `.repaint`
  on it) -- decide the calling convention without breaking `java:proxy`'s
  `(callable name args...)`.
- Interpreter: define the subclass at run time (`java.lang.classfile` +
  a class loader that sees the superclass and the dispatch interface typed with
  JDK types only); interpreted native image stays refused as today.
- JVM: `JvmJavaImplementations` generates it at compile time, but its
  superclass is the user's class, not `$Implementation`: the kind test of a
  direct call (`emitKindTest`) and `JavaImplementationType` (supertypes = the
  class's chain plus the interfaces) change with it. A form left to run time:
  the bridge must generate too, or refuse by name.
- Overridable set: public AND protected non-final methods of the class chain
  (`paintComponent` is protected; `JavaType` lists public methods only, in both
  lookups -- `ReflectiveJavaClasses` and `JvmClassFileLookup` must agree);
  unnamed class methods call super, unnamed interface methods throw
  `UnsupportedOperationException` with the method name (the oracle).
- Constructor: the superclass constructor chosen by `JavaOverloads` over the
  arguments (protected ones included).
- `proxy-super`: a generated `super$m` accessor per overridden method.
- `Object` methods: `toString`/`equals`/`hashCode` named in a proxy run their
  body in the oracle; b59 refuses them by name because `java:proxy` keeps
  `Object`'s three. A class proxy overrides them like any other class method.

## Oracle

```bash
clj -M -e '
(import javax.swing.JPanel java.awt.event.ActionListener java.io.File)
(def p (proxy [JPanel ActionListener] []
         (actionPerformed [e] (println :clicked))
         (toString [] "panel!")))
(println (str p)) (.actionPerformed p nil)
(println (.lastModified (proxy [File] ["x"] (lastModified [] 42))))'
```

## Acceptance

Interpreter + JVM legs in `ClojureInteropTest` and the `java:` legs in
`JavaInteropTest` / `JvmJavaInteropCompilerTest` (shared program in
`JavaImplementationPrograms`); the corpus `interop.clj` SAX handler and the
`sequences` test's `File` proxies run; `snake.clj` lowers past its proxy forms.
