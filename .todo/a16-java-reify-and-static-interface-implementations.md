# java:reify, and interface implementations without java.lang.reflect.Proxy

Difficulty: High

Depends on a14; shares invokedynamic support in am.ik.jvm with a08.

Today the only way to implement a host interface is (java:proxy "Iface" callable):
java.lang.reflect.Proxy with one callable receiving (method-name-string args...) for
every method (eval/JavaInterop.proxy; the JVM bridge mirrors it). That shape keeps
method dispatch, overload distinction and return-type checks at run time even if the
implementation is generated, so it cannot give statically compiled code its benefit.
Design reference: Clojure (reify per-method bodies; proxy as the dynamic form; 1.12
fn -> functional-interface coercion).

Plan:
- New java:reify with per-method bodies, e.g.
  (java:reify "java.util.function.Function" ("apply" (x) ...)), with optional
  signature tags in a13's param-tag syntax ("compare(Object,Object)") to pick an
  overload. Signatures are fixed at compile time: the JVM backend generates an
  implementing class shipped beside the program (no Proxy, no reflection config
  under native-image); the interpreter implements it with Proxy but dispatches to
  the same resolved methods, so selection and marshalling match.
- A callable passed where a SAM interface is expected: invokedynamic
  LambdaMetafactory to a synthetic method calling _apply.
- java:proxy keeps its meaning (one handler for all methods: logging, mocks).
  On the JVM backend it becomes a generated class routing every method to the
  callable; decide whether --java-static accepts it.
- am.ik.jvm: MethodHandle/InvokeDynamic constants, BootstrapMethods,
  shaker/splitter/stack-map support.
- Docs (doc/en + doc/ja java-interop guide), .kb/java-interop.md, tests on both
  JavaInteropTest and JvmJavaInteropCompilerTest, and a native-image E2E with a
  Swing-free listener interface and no reachability metadata.
