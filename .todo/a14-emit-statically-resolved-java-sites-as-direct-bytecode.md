# Emit statically resolved java: sites as direct bytecode

Difficulty: High

Depends on a13.
Prototype 2026-09-26: invokestatic Math.max(II)I, new/invokespecial
StringBuilder(String), invokevirtual length/append, invokeinterface List.size,
getstatic Integer.MAX_VALUE with inline marshal (l2i, quote strip) and
unmarshal (i2l+Long.valueOf, quote re-frame): class 80,896 -> 6,699 bytes; the
jar native-images with NO config and runs (7.9MB vs 15.5MB agent route).
Traps found: static interface methods need InterfaceMethodref; picking a
covariant bridge method gives IllegalAccessError.

Plan:
- Per-site synthetic static method _jsite$N: typed marshal, direct invoke,
  unmarshal, exceptions wrapped with the bridge's exact messages
  (LinkageError -> "No such class ...").
- Bridge/eval runtime emitted only when an unresolved site remains.
- --java-static: an unresolved site is a compile error. --java-release default
  17 (class 61). Output byte-identical when no java option is given
  (.kb/emitted-output-determinism.md: the classpath is an input).
- Tests: JvmJavaInteropCompilerTest parity; javap shape; native-image E2E
  without reachability metadata.
