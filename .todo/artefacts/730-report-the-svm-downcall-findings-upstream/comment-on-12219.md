<!-- Paste as a comment on https://github.com/oracle/graal/issues/12219 (GR-75754). -->

We hit the same thing and went looking for where the time goes. Attribution, a cost model and a
same-image comparison, in case they are useful for GR-75754.

**Environment.** Oracle GraalVM 25.0.4+7.1 native-image (JDK 25), Linux aarch64 (Cortex-X925, 20 cores).
Every probe below is standalone, ~100 lines, and does nothing but call one function two million
times; all shapes are registered in `reachability-metadata.json`.

### It is the method-handle interpreter, not the transition and not the callee

`perf record -F 4000 -g` over an image whose only work is one downcall in a loop: 97.9% of the
calling thread's samples are in the image's own code, 1.6% in the kernel and **0.06% in the callee's
library**. Flat profile, self %:

| self % | symbol |
| --- | --- |
| 15.0 | `java.lang.invoke.LambdaForm::interpretName` |
| 11.1 | `com.oracle.svm.core.invoke.MethodHandleUtils::cast` |
| 9.4 | `com.oracle.svm.core.methodhandles.MethodHandleIntrinsicImpl::execute` |
| 6.7 | `java.lang.invoke.MethodHandle::invokeBasic` (SVM's substitution) |
| 3.8 | `LambdaForm::interpretWithArguments` |
| 3.5 | `Util_java_lang_invoke_MethodHandle::convertArgs` |
| 2.6 | `Util_java_lang_invoke_MethodHandle::invokeInternal` |
| 2.4 / 1.5 / 1.9 | `Class::searchFields`, `Class::reflectionData`, `arrayRegionEqualsS1S1` |
| 2.1 / 2.0 | `HashSet::contains`, `HashMap::getNode` |
| 1.5 | `jdk.internal.foreign.abi.DowncallLinker::invokeInterpBindings` |
| 1.2 / 1.0 | `Module::isExplicitlyExportedOrOpened`, `Module::allows` |

A downcall handle is created at run time, so it has no AOT code and its `LambdaForm` is walked Name
by Name with every argument boxed into an `Object[]`; each `DirectMethodHandle` in the chain goes
through a reflection accessor (`SubstrateMethodAccessor.invoke`, `convertArgs`), and each result
through `MethodHandleUtils.cast`. The `linkToNative` stub at the end -- the part native-image
compiled at build time from `reachability-metadata.json` -- is cheap. Everything in front of it is
the cost. A second thread (`Reference Handler`) is busy the whole run: the interpreter allocates per
call.

### The cost model: ~1.7 us per call plus ~0.4 us per argument

Two million calls, ns each, steady state. Callees chosen so they return immediately (null handle /
zero extent), so this is the binding cost and nothing else:

| target (arguments) | GraalVM JIT | native image |
| --- | --- | --- |
| `cuDriverGetVersion(int*)` (1) | 8.8 | 2103-2110 |
| same, `critical(true)` | 4.6 | 2140-2148 |
| libc `abs(int)` (1) | 7.3 | 2147-2153 |
| same, `critical(false)` | 2.7 | 2142-2147 |
| libc `getpid()` (0, a syscall) | 119.3 | 1860 |
| no downcall (the same loop) | 5.7 | 32.1 |
| `cuCtxSetCurrent` (1) | 9.4 | 2133 |
| `cuMemcpyHtoD_v2` (3) | 15.4 | 2933 |
| `cuLaunchKernel` (11) | 17.1 | 6173-6185 |
| `cblas_dgemv`, critical (12) | 7.7 | 6414-6422 |
| `cblas_dgemm`, critical (14) | 6.9 | 7210-7220 |
| `cblas_dgemm`, plain (14) | 10.9 | 7029-7057 |

`getpid` is the control: 119 ns on the JVM (the syscall) against 1860 in the image (the
interpreter). `critical(true)` does not help -- the transition it skips is a few ns, and it adds a
binding. The per-argument term is the boxing.

### The same address through SVM's own AOT route is 10.7 ns

Same image, same run-time-resolved address, invoked through an `@InvokeCFunctionPointer` interface
that native-image compiles at build time:

| route | ns |
| --- | --- |
| FFM downcall handle | 2104 |
| `CFunctionPointer`, with the thread transition | **10.7** |
| `CFunctionPointer`, `Transition.NO_TRANSITION` | 3.2 |

So nothing about AOT, the ABI or the transition costs 2 us; only the run-time handle chain does.

### The ask

**A downcall stub whose shape is in `reachability-metadata.json` already exists AOT at build time,
and that set is closed by construction** -- it is what registration is for. Could a handle created
at run time be matched to its registered shape and given an AOT invoker, instead of falling to the
`LambdaForm` interpreter? That is the difference between the two rows above.

For what it is worth, the workaround we shipped is exactly that done by hand: take the address the
FFM `SymbolLookup` found and re-issue the hot calls through a hand-written
`@InvokeCFunctionPointer` interface per shape, in a source set compiled only for the image. It
brought a 12-argument BLAS call from 6.4 us to 89 ns (three `PinnedObject`s, ~25 ns each, are most
of the remainder). It needs one interface per shape written by hand -- which the registered-shape
table already knows.

Related: the address-first shape (`Linker.downcallHandle(FunctionDescriptor)`, no native pointer in
the heap) is the one shape that could be a build-time constant, and holding it in a
build-time-initialised class crashes the image build instead; filed separately.
