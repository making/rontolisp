# java:proxy and auto-proxied callables without java.lang.reflect.Proxy

Difficulty: High

Depends on a14; shares invokedynamic support in am.ik.jvm with a08.
Plan: SAM interfaces via invokedynamic LambdaMetafactory to a synthetic method
calling _apply; other interfaces via a generated implementing class shipped
beside the program. am.ik.jvm: MethodHandle/InvokeDynamic constants,
BootstrapMethods, shaker/splitter/stack-map support. Native E2E with a
Swing-free listener interface.
