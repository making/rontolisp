# Emit java: call sites as invokedynamic with a guarded inline cache

Difficulty: High

Only start this after a07 (the java: overload cache) has landed and a real
workload shows the remaining per-call overhead (measured ~100-200 ns: _javaInit
call, Object[] packing, class/method name substring, key allocation,
Method.invoke boxing) matters. A constant-MethodHandle call measured at the
harness floor (~15-20 ns) on 2026-09-26.

Scope note (2026-09-26): sites a13/a14 resolve statically become direct
bytecode, which beats indy and suits native-image (MutableCallSite does not).
Narrow this item to UNRESOLVED sites only, or cancel it once a15 lands. The
am.ik.jvm indy infrastructure is shared with a16.

Plan:
- am.ik.jvm: add CONSTANT_MethodHandle / CONSTANT_InvokeDynamic entries and the
  BootstrapMethods class attribute; teach JvmClassShaker, JvmClassSplitter
  (carry bootstrap entries into $PartN) and StackMapAugmenter about them.
- Bootstrap in the bridge template: MutableCallSite + guardWithTest on
  (receiver class, argument kinds), fallback relinks through the shared
  selection; per-argument marshal filters and a return filter specialized to
  the chosen method's types; exceptions wrapped exactly as fail() does.
  No nested classes in the template.
- Literal class/method names become bootstrap static arguments; fixed arity
  call-site descriptors replace the Object[] packing.
- Behavior must stay identical to the interpreter (same tests as a07, plus a
  megamorphic site).
