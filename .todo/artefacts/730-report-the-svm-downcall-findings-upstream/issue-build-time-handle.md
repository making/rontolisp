<!-- Open at https://github.com/oracle/graal/issues/new . Title on the first line. -->

# [Native Image] A downcall handle held by a build-time-initialised class crashes the build: "unexpected input could not be handled: linkToNative"

**Describe the issue.** `Linker.downcallHandle(FunctionDescriptor)` -- the address-first shape --
holds no native pointer, so a holder class initialised at build time can carry the handle in the
image heap and AOT code would see a constant. Under
`--initialize-at-build-time=<holder>` native-image dies in analysis instead, while parsing the
`invokeExact` call site:

```
Fatal error: com.oracle.graal.pointsto.util.AnalysisError$ParsingError: Error encountered while parsing BuildTimeHandle.lambda$main$0(BuildTimeHandle.java:43)
Caused by: com.oracle.svm.core.util.VMError$HostedError: should not reach here: unexpected input could not be handled: linkToNative
	at com.oracle.svm.core.util.VMError.shouldNotReachHereUnexpectedInput(VMError.java:97)
	at com.oracle.svm.hosted.substitute.PolymorphicSignatureWrapperMethod.buildGraph(PolymorphicSignatureWrapperMethod.java:170)
	at com.oracle.svm.hosted.substitute.SubstitutionMethod.buildGraph(SubstitutionMethod.java:122)
	at com.oracle.graal.pointsto.meta.AnalysisMethod.buildGraph(AnalysisMethod.java:757)
	...
	at jdk.graal.compiler.replacements.PEGraphDecoder.doInline(PEGraphDecoder.java:1215)
```

The AOT inliner for a polymorphic-signature call on a constant `MethodHandle` appears to have no
case for `linkToNative`, and it is a hard error rather than a fallback to the interpreter. With and
without `Linker.Option.critical`.

**Not the already-fixed one.** #9727 and #7531 report the same `VMError` text and are both closed
(#9727 as completed, 2025-08-27); both come from a `DowncallStub.invoke` reached normally (Quarkus /
jline on Windows). This path is different -- the handle is a build-time constant and the failure is
in `PolymorphicSignatureWrapperMethod.buildGraph` during inlining -- and it still reproduces on
25.0.4.

**Why it matters.** A run-time downcall handle in a native image is interpreted
(`Target_java_lang_invoke_LambdaForm.forceInterpretation()` returns `true`), which costs ~1.7 us per
call plus ~0.4 us per argument -- measurements and a `perf` profile in #12219 (GR-75754). The
address-first shape held at build time is the one FFM-only way to get an AOT-compiled downcall, so
this crash closes the only door that does not require hand-written
`@InvokeCFunctionPointer` interfaces. Either outcome would be fine: compile it like any other
constant method handle, or fall back to the interpreter -- but not a build crash.

**Steps to reproduce.** `BuildTimeHandle.java` (attached, ~90 lines, no dependencies) binds the same
shape three ways -- build-time address-first, run-time address-first, run-time bound -- and times
each. `reachability-metadata.json` registering the shape is in `meta/` (attached).

```sh
javac -d classes BuildTimeHandle.java
native-image -cp classes:meta --enable-native-access=ALL-UNNAMED \
  '--initialize-at-build-time=BuildTimeHandle$Holder' -o build-time-handle BuildTimeHandle
```

Drop `--initialize-at-build-time` and the image builds and runs (all three arms ~2.1 us). On the
JVM the build-time arm is 8.4 ns, so the shape itself is sound; the address is passed at run time
as the leading argument, and nothing native is in the image heap.

The reproducer looks up `cuDriverGetVersion` in `libcuda.so.1` only because it returns immediately;
any `(ADDRESS) -> int` symbol does.

**Version.**

```
native-image 25.0.4 2026-07-21
GraalVM Runtime Environment Oracle GraalVM 25.0.4+7.1 (build 25.0.4+7-LTS-jvmci-b01)
Substrate VM Oracle GraalVM 25.0.4+7.1 (build 25.0.4+7-LTS, serial gc, compressed references)
Linux aarch64 (Cortex-X925, 20 cores)
```

Full build log attached as `build-time-handle.error.txt`.
