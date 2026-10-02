# b59. `proxy` with several interfaces

Difficulty: Medium

`proxy takes a single interface, not [JPanel ActionListener KeyListener]` --
two corpus programs (the snake UI) subclass a component and implement two
listeners in one `proxy`.

Today: single interface only, methods take the Java arguments with no `this`,
`java:proxy` under the hood. Widening means one generated proxy over several
interfaces: check what `java:proxy` supports (`.kb/java-interop.md`), and
whether the no-`this` model still holds when two interfaces declare the same
method name (the oracle dispatches by the single method implementation -- a
proxy body is shared per method name across interfaces).

Constructor superclass argument `(proxy [JPanel] [w h] ...)` is the same
shape's other half if the corpus needs it; refuse-by-name is fine if not.

## Oracle

```bash
clj -M -e '
(import javax.swing.JPanel java.awt.event.ActionListener)
(def p (proxy [JPanel ActionListener] []
         (actionPerformed [e] (println :clicked))
         (toString [] "panel!')))
(println (str p)) (.actionPerformed p nil)'
```

Pin: multi-interface acceptance, shared method body across interfaces, the
refusal message for a missing method, constructor-arg shape if taken.

## Acceptance

Interpreter + JVM legs in `ClojureInteropTest` (wasm refuses `java:` as
today); the corpus UI programs lower past the proxy form.
