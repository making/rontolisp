# JVM run-time lowering: a host class compiles each lowering verb into a fragment linked against itself

**Invariant: a Clojure program naming a lowering verb over computed arguments
(`ClojureRunTime.ENTRIES`: `eval`, a computed `resolve` / `require` / `load-string` ...)
compiles to a HOST class that carries its program's image and the compiler. Its first verb
reads and lowers the program again from the image -- the same decisions, so the same names
-- and each verb's lowered forms compile into a FRAGMENT class defined into the host's
package through the host's own lookup, linked against the host and the fragments before
it: functions by name, globals and run-time state through the host's fields, function
values routed by id.** clojure-spec `the-interpreter-lowers-at-run-time` runs on the JVM
leg (2026-10-30; the whole spec program is a host, every JVM case green), and as
`-o App.class` / `-o app.jar` with nothing else on the class path. Wasm refuses by name
(`.kb/clojure-frontend.md`, "Lowering at run time").

## Compile time: the host

- `CompileFrontend.run`: Clojure on a JVM target reads through
  `eval/ClojureProgramLowering` (the run-time half reads through the same class) over a
  recording loader (`cli/RunTimeImage.Recording`): every file read, existence answer and
  listing joins the image, so the run-time read decides as this one did wherever the class
  runs. A program keeping its lowering sets `Loaded.lowersAtRunTime`:
  - `ClojureLibrary.process(..., lowersAtRunTime)`: the whole library, unstripped (host arms
    and every family -- a value a fragment makes may meet any arm), the refusing
    `%clojure-lower-at-run-time` stood in by the hand-off (`HOST_LOWERING`) and the
    transport appended (`HOST_TRANSPORT`).
  - The lowering (`ClojureLowering.carriesRuntimes`: open and a compiled target) emits the
    ex-info, hierarchy, protocol, STM and macro runtimes up front: the class's library links
    what it calls when it is compiled. Measured: without it a fragment-made `ex-info`
    printed by the host printed as a raw `C%E-EXCEPTION` condition. An open program always
    defines `*ns*` (the hand-off reads it).
- `JvmLispCompiler` host mode (`Builder.runTimeImage`): `_rtImage` / `_rtLink` (the image and
  the `JvmLinkage.Table`, chunked past one `ldc`), `_lowerAtRunTime` (hands
  `MethodHandles.lookup()` to `cli/JvmRunTimeLowering.lower`, a class named, never
  imported), `_foreignApply` over `_foreign` (a `MethodHandle` the run-time half installs).
  Every member package access (`JvmClassSplitter.write(..., packageAccess)`, which also keeps
  every field); every program defun a shake root; the apply tier forced; threads mode forced
  (every special has its `_d$` ThreadLocal: a fragment may bind any of them) and raw globals
  off (one storage protocol a fragment can follow).
- The output: `JvmSourceCompiler` adds `cli/RunTimeCompilerFiles` -- every class and resource
  of the build, read once off its code source (jar or classes directory, `META-INF/` left
  out) -- to the travelling files: beside `-o App.class`, inside `-o app.jar` (the spec case's
  jar: 9.99 MB). A native image has no code source: it warns and carries nothing (the class
  then needs the rontolisp jar on its class path).

## Run time: the fragments

- **The transport.** The stand-in hands over `(%clojure-transport args consts)`: lists,
  symbols, numbers, characters as data; vectors, maps, sets and a collection's metadata as
  `:c%t-*` lists; anything else (a function, an atom, a record, a host object)
  `(:c%t-embed i)`, the value itself in the `(count . reversed)` constants cell. A string goes
  as `(symbol-name (make-symbol s))`. Trap: every string producer answers a MUTABLE character
  vector on the JVM (`MutableStringProducers`), `princ-to-string` and a string stream's
  capture included; a symbol's name never. `ClojureProgramLowering.lower` reads the
  transport back into the interpreter's values (metadata through a side map), an embedded
  value lowering to `(rontolisp::%run-time-constant i)`.
- **The program.** The entries' forms, the echoing entry's last form wrapped
  `(rontolisp::%run-time-answer f)` (a definer stays top level, its name answered after it),
  through `CompileFrontend.expand` with `lowersAtRunTime`, then
  `JvmRunTimeLowering.unlinked`: a library `defun` / `defvar` / `defparameter` /
  `defconstant` whose name a class before defines is dropped (spliced again it would start
  its state over); the forms' own definitions stay (a redefinition stores into the linked
  field).
- **The compile** (`Builder.fragment`): `ConstantPool.linkFields` -- every fieldref to a
  linked `name:descriptor` is minted naming the declaring class, and
  `ClassDefinition.Builder.addField` declares none of them; `<clinit>`'s writes of a linked
  field go to dead `_init$` fields (`MethodCode.retargetFieldWrites`, the same instruction
  size, so no position moves); a linked global, special, thread-bound or checked from the
  table, only the ones the fragment names; an external function a `FunctionInfo` whose
  methodref names its class, with a fragment-local id (a value of it is this class's value,
  whose case calls that class); ids from `funcIdBase` (`_funcrefs` indexed `id - base`);
  `_run` answers `_answer`; no `main`, no uncaught report.
- **Shared fields** (`JvmLinkage.shared`): `_g$`, `_d$`, `_ly$`, the stream table, `_col`,
  `_gensymCtr`, the condition / non-local-exit / wrong-type channels, `_jexMap`, `_jsigTl`,
  `_unbound`, `_unsupplied`, the multiple-value register, `_curThreadTl`, `_handoffTl`, the
  render guard. Everything program-specific stays the fragment's own (`_qd`, `_bi$`,
  `_funcrefs`).
- **Function values.** Every dispatcher of a linked class (`JvmRuntimeBuilder.Foreign`) hands
  an id outside its own range to the host's `_foreignApply`, whose router finds the class
  that made it and calls its `_invoke_v` (pinned in the class). `rebuildDispatchers` keeps an
  external function's case: it has no own method for a kept body to "make". Trap: dropped,
  the spread dispatcher's miss answered `nil` silently.
- **The definition.** `lookup.defineClass` for the fragment (`Host$EvalN`) and its parts and
  bridges in the package, the constants set, `_run` called on the caller's thread (its
  bindings); lowering, compile and definition under the session lock, the id ranges replaced
  whole (a foreign call reads them without one). Each class's table is `located`: a function
  re-pointed to the class declaring its method, since a split class keeps functions in its
  `$PartN` classes (checked with `-Drontolisp.jvm.class-pool-limit=4000`, host and fragments
  split, oracle-identical).
- Found on the way: `usesMutexes` counts `with-mutex` (a fragment holds its host's mutex and
  makes none).

## Limits (2026-10-30)

- The host's compiled forms see no name a fragment defines: a name the program only
  declares and a later call defines stays undefined at the host's call sites (the
  interpreter resolves it when the call runs), and a native binary's host carries no
  compiler (`.todo/h11`).
- Every verb compiles and defines a class, never unloaded; the first verb pays the program's
  lowering again.
- The image keeps the files the lowering read, not a `deps.edn` dependency's jars or
  coordinates: those resolve again where the class runs.

## Debug

`-Drontolisp.debug.run-time-lowering=true` prints each verb's lowered forms;
`-Drontolisp.debug.run-time-classes=<dir>` writes each fragment's class file for `javap`.

## Tests

clojure-spec `the-interpreter-lowers-at-run-time` (the JVM leg),
`ClojureSpecE2eTest#aClassThatLowersAtRunTimeRunsAloneAsAClassAndAsAJar` (the case compiled
by the command line, each output run as its own process).
