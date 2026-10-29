# JVM run-time lowering: a host class compiles each lowering verb into a fragment linked against itself

**Invariant: a Clojure program whose run-time code reaches a lowering verb over computed
arguments (`ClojureRunTime.ENTRIES`: `eval`, a computed `resolve` / `require` /
`load-string` ...; "What opens a program") compiles to a HOST class that carries its
program's image and the compiler. Its first verb
reads and lowers the program again from the image -- the same decisions, so the same names
-- and each verb's lowered forms compile into a FRAGMENT class defined into the host's
package through the host's own lookup, linked against the host and the fragments before
it: functions by name, globals and run-time state through the host's fields, function
values routed by id.** clojure-spec `the-interpreter-lowers-at-run-time` runs on the JVM
leg as `-o App.class` / `-o app.jar` with nothing else on the class path (2026-10-30).
Wasm refuses by name (`.kb/clojure-frontend.md`, "Lowering at run time").

Trap: one such case makes a whole concatenated corpus a host, which then checks only
host-mode output (no arm stripping, raw globals, non-threads specials or tree shaking).
Such a case is marked `host:`; the JVM leg runs the marked cases as one host program of
their own and refuses a corpus class that has `_rtImage`. Measured 2026-10-30: five
clojure-spec cases were marked -- the run-time lowering case and four naming a verb in a
macro body, a helper or a value. Once the decision became reachability (below),
`ns-map-answers-interns-refers-and-core-vars` turned out a host through its top-level
`(ns-map 'user)`, not its macro body, and is back in the corpus; the two whose macro
bodies `eval` / `resolve` each lower alone as ordinary classes but stay marked, because
the corpus expands at run time and so reaches every macro body; `ns-map` as a value stays
a host.

## What opens a program

`ClojureLowering.lowerWhole` asks `ClojureRunTime.reachesAny` of each closed pass, for the
interpreter and the JVM alike: the top-level forms (a `progn`'s members) run, and a `defun`
runs where a form that runs names it -- the library calls none the lowering defines but the
ex-info, protocol and hierarchy runtimes', which reach no verb. A macro's expander sits in
the note behind its `defmacro` and, only for a run-time expansion (`macroRuntime`: every
expander, a head computed as it runs may name any macro) or a read of the macro's var
(`macroRootRuntime` over `macroRootsRead`: those macros' alone), in `C%MACRO-EXPANDER`; so
a verb only a macro body or a helper only macro bodies call names opens nothing otherwise
(compile-if, defconversion, tools.reader's `resolve-symbol`). No use either: the
statements handing a root to a table that never calls it (`ROOT_NOTES`: a macro's noted
expander, a redefinition's validate-and-notify -- a watch meets a root only through the
var, whose object names it -- and a fn's class name), and a core var a namespace's mapping
lists (`mappedCoreVars`, by identity). Weighed 2026-10-30:

- A verb the program takes as a value or as a var (`(map eval xs)`, `(= ns-map ns-map)`,
  `(resolve 'eval)`) opens: the value may be called over arguments only the lowering
  answers, and refusing would turn programs the JVM runs into refusals.
- A run-time expansion reaches every macro body, so it opens beside any body naming a
  verb: a run-time expansion of `compile-if` keeps answering on the interpreter and the
  JVM, and only a program both expanding at run time (or taking such a macro's var) and
  defining such a macro pays.
- A mapping's core vars hold the verbs' values (`eval`, `require`, `ns-map` ...): counted,
  every `ns-map` the program runs (and `ns-publics` / `ns-interns` of `clojure.core`)
  carried the compiler, for a path -- a verb taken out of the mapping and called over
  computed arguments -- that refuses instead (all four backends).

Pins: `ClojureLoweringTest#evalAndResolveRefuseWhatTheyCannotTake`, clojure-spec
`ns-map-answers-interns-refers-and-core-vars` in the JVM leg's corpus.

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
  of the build, read once off its code source (jar or classes directory; `META-INF/` and the
  native output's shims and stubs, `am/ik/rontolisp/native/`, left out) -- to the travelling
  files: beside `-o App.class`, inside `-o app.jar` (the spec case's jar: 9.99 MB). A native
  image holds no class files (opened as a jar, its code source failed with `zip END header
  not found`): `src/native/java/.../cli/RunTimeCompilerFeature` (`--features` of the
  `native` profile) stores the same files, read off the image's class path while it builds,
  as the resource `am/ik/rontolisp/cli/run-time-compiler.zip`, which `RunTimeCompilerFiles`
  reads first; the binary's `-o App.class` / `-o app.jar` (9.92 MB) of the late-binding case
  run with nothing else on the class path (2026-10-30). Decided against resolving the
  binary's version through `am.ik.maven`: that costs the binary nothing but hands a snapshot
  binary whatever snapshot jar was published last, a compiler other than the one that
  compiled the host. The archive is about the main jar's 9.5 MB, beside the 135 MB
  linux-amd64 binary of 2026-10-29.

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

## Late binding

A name no definition of a linked class (host or fragment, `Ctx.linked`) has resolves when
the code runs: a literal `fboundp` probes, `#'name` boxes what `_lookup` answers
(`JvmFunctionFormCompiler.emitResolvedFunction`; a global of the name is a Clojure var's root,
never its function, so it does not decide), a call applies that `#'name`. The open lowering's
declared-only call `(%clojure-call (if (fboundp 'f) #'f f) ...)` takes this path. Every
program defun of a linked class is a registry row (so dispatchable), `JvmLinkage.Function`
carries its id, and `_lookup`'s miss calls the host's `_foreignLookup`, which asks the
session's merged table and answers `{id, arity}`; the calling class's dispatcher hands the
foreign id to `_foreignApply`. `_funcref` of an id outside the class's range calls the host's
`_foreignFuncref`: the owning class's own box (its `_funcref`), else one the session keeps
per id. Before the first verb the handles are unset and `_foreignLookup` answers null.
clojure-spec `a-declared-name-a-run-time-eval-defines-is-called-where-the-program-names-it`.

## Limits (2026-10-30)

- A fragment's redefinition of a name the host defines reaches the forms lowered after it
  only: the host's own calls and its `_lookup` row keep the host's function.
- Every verb compiles and defines a class, never unloaded; the first verb pays the program's
  lowering again.
- The image keeps the files the lowering read, not a `deps.edn` dependency's jars or
  coordinates: those resolve again where the class runs.

## Debug

`-Drontolisp.debug.run-time-lowering=true` prints each verb's lowered forms;
`-Drontolisp.debug.run-time-classes=<dir>` writes each fragment's class file for `javap`.

## Tests

clojure-spec `the-interpreter-lowers-at-run-time` and the other `host:` cases
(`ClojureSpecE2eTest`'s JVM leg, `HOST_CLASS` / `HOST_JAR`: compiled by the command line,
each output run as its own process).
