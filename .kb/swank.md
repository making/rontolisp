# SWANK: `rontolisp --swank` serves the Common Lisp REPL to SLIME, SLY and Lem

**Invariant: one evaluator serves every request, and a request reads and runs in the
package it names, bound to its own thread (`PackageResolver.bindThreadPackage`) -- two
requests run at once in two packages, as SWANK's REPL thread and workers do.**
**Invariant: an error no handler handles enters the debugger on the thread it was
signalled on, before anything unwinds, after its handlers ran -- once per error.**
Oracle: SWANK 2.32 on SBCL 2.2.9, driven by SLIME 2.32 (`slime-fancy`) in Emacs batch mode
through a logging proxy, then the same driver against rontolisp. Every request answers message for
message as SBCL's SWANK; the differences are listed at the end. Interpreter only.

## Layout

- `cli/SwankServer` -- transport and threads (loopback `ServerSocket`, one virtual thread
  reading each connection, replies written whole under the connection's lock), SWANK's
  `:spawn` style. A `Session` per request thread is that thread's `Sldb`: its pending
  requests (innermost first), outputs, debugger levels and mailbox. Every request thread
  takes `--stack`.
- `cli/SwankDialect` -- SWANK, Slynk or micros per request; `SwankServer.MRepl` is SLY's
  REPL channel. `cli/SwankWire` -- framing (six hex digits counting UTF-8 octets) and a
  reader of Emacs's `prin1` output, printed back with `*print-case* :downcase`.
- `eval/CommonLispSwank` -- the Common Lisp half, so `cli` touches no evaluator internals.
  An autodoc form arrives with the cursor symbol made `CommonLispSwank.CURSOR`.
- `eval/SwankSources` (`M-.`, xref) and `eval/SwankInspector` reach `CommonLispSwank` only
  through `eval/SwankLisp` (locations `eval/SwankLocation`), so the three form no class
  cycle (`PackageCycleTest`).
- `eval/Debugger` (`LispEvaluator.withDebugger`) and `eval/CallFrame`. Constructing a
  `CommonLispSwank` calls `LispEvaluator.enableDebugging`: restart mode for every later
  evaluation, frames kept from then on.
- `--swank[=PORT]` is a value-less key (`=` for the port; 4005 default, 0 any free port);
  refused beside a program, `-o`, `test`, `--nrepl` or a non-Common-Lisp
  `--source-language`.

## Dialects

SLY's Slynk and Lem's micros are SWANK forks: same framing and `:emacs-rex`, their
functions in their own packages. Recorded with SLY 1.0.43 / Slynk and Lem 2.3.0 / micros
2ec61c8 on SBCL 2.2.9. The deltas
are small enough that one server answers all three on one port: the dialect is the
package prefix of the called operator (`swank*`, `slynk*`, `micros*`),
`SwankDialect.canonical` maps a fork's names to SWANK's, the few own shapes are answered,
the rest falls through to SWANK's table. A connection may switch dialect per request; a
request's output and input follow its dialect.

Shapes that differ from SWANK (the source has the rest):

- SLY: `connection-info` must say `:version "1.0.43"` (SLY asks "Versions differ"
  otherwise); the REPL is a channel (`slynk-mrepl:create-mrepl`), its history counting
  inputs that answered values; output of other requests goes to the first REPL's channel
  with no flow control; input elsewhere through `:read-from-minibuffer`; `flex-completions`
  scores in single floats as Slynk computes them (`mvbind` 0.009072388522326946, as SBCL);
  restarts say `SLY` for `SLIME`, `(:debug-activate thread level)` without the nil; an
  error's level in the REPL sends a `:prompt` carrying the level and the REPL reads on
  inside it, and an input a restart leaves answers `(:evaluation-aborted "cond")` with the
  last condition its level was entered for (`"T"` for an interrupt's); `#vN:M` reads a
  history value.
- Lem: `:version "0.0.0"` (unchecked), no contribs; values as `:write-object` with
  per-connection ids, no fresh line before them; `(:write-string text)` with no target,
  thread or `:write-done`; no `:debug-activate`, `Interrupt from Editor`; `C-c C-l` calls
  `micros:load-file`, defined in the image at `micros:connection-info`;
  `pretty-eval`'s inspector ids are its own, from 1.

## A program's server

`eval` may not import `cli`, so the command line hands every evaluator it creates an
`eval/SwankHost` (`LispEvaluator.setSwankHost`, `cli/SwankServers`); the `swank` shim
system calls it under `#+rontolisp-interpreter`. `:dont-close t` accepts on a non-daemon
thread and returns; nil (SWANK's default) accepts the first connection on the calling
thread and closes the port. `:style` and `:backlog` are ignored.

- `SwankServers` makes its `CommonLispSwank` only for the first server a program starts, so
  a program that serves nothing keeps call frames off: when the host made it eagerly,
  `CallFrame.at`/`enter` and their `ThreadLocal` reads were 3% of coalton's samples.
- Connections read on platform non-daemon threads, so a program that ran off its end is
  served until the editor disconnects (SBCL's script needs `(loop (sleep 1000))`).
- `quit-lisp` ends the process (`main` hands the CLI `System::exit`); an embedded
  `RontoLispCli` (tests) only closes that server.
- An evaluator with no host signals from `create-server`; a compiled program's shim
  (`#-rontolisp-interpreter`) signals naming the interpreter, its `stop-server` answers nil.

## A thread's package

A thread may hold its own current package and saved-package stack (`ThreadPackage` in a
`ThreadLocal`), so `in-package`, a `let` of `*package*`, a `load`'s push/pop and a macro
expander's package swap stay on that thread (`.kb/symbol-runtime-api.md`). The thread-local
is read only while a binding is live anywhere (an `AtomicInteger` count), so a program
that never binds pays one volatile read. The resolver is a monitor: a resolution walk and
the printer's `printsBare` keep transient state in fields, so every public method but the
plain reads is `synchronized` (it calls out to nothing, so it holds no other lock). The
macro-time warning sink is thread-local and the user macro table concurrent for the same
reason: a worker defines while the REPL runs.

## Output and input

- A request's output is flushed when asked, at 64000 characters, 20 ms after a write, and
  before the results; a flush waits for the editor's `:write-done` (SWANK's flow control).
  An interrupted request's output is flushed before its `:abort`.
- The stream table reads off a `BufferedReader`, so the `:read-string` reader is wrapped in
  one.

## Requests

- `re-evaluate-defvar` re-runs a `defvar` as a `defparameter` (no `makunbound` here).
- A contrib's function not answered aborts: `The function SWANK:X is undefined.` (the
  package as sent). A form of another package is evaluated in the request's package.
- `swank-macroexpand-all` expands every macro in code positions (`CallSiteWalk`). Macrostep
  places each macro form of an expansion by printing it and finding it in the printed
  whole in walk order.
- Top-level restarts (`topLevel`): the thread's `ABORT "abort thread (...)"` (the REPL
  thread then ends; the next REPL request starts another), inside it `ABORT "Return to
  SLIME's top level."`, and `SWANK::RETRY` around the evaluating requests. A request a
  restart further out leaves answers `(:abort ...)` on its way (`CommonLispSwank.isTransfer`).
  An `:abort` names the last condition a debugger level was entered for in the request
  (SWANK's `eval-for-emacs` records every one, so an outer request aborted from a nested
  level reports the inner condition), else `"NIL"`. A stack overflow puts the control state
  back first.
- `:emacs-interrupt` targets the newest running request (`LispEvaluator.interrupt`, the
  cooperative stop nREPL uses, `.kb/nrepl.md` "Interrupt"); with nothing running it is
  dropped.

## The debugger

**Where it enters.** `LispEvaluator.signalPoint`: an error a built-in raised or one a frame
is leaving runs `withHandlerBindHandlersRun`, then `offerDebugger`, unless already offered
(`LispEvalException.offerDebugger`) or a `handler-case` takes it (`anyHandlerCaseMatches`).
`signal` and `warn` never reach it. What no frame signalled (a top-level variable, an
unmatched `throw`) meets it at the request's form (`atSignalPoint`). The level runs inside
that call. Traps: the hooked `error` terminal arrives with its handlers run, so the offer
cannot ride `handlersRan`; and on a debugger thread an un-seamed error (undefined function,
unbound variable) runs its `handler-bind` handlers at the innermost frame, before the
cleanups between it and the `handler-bind` -- CL's order, not the pad's of other threads.

**Restarts.** `serve` binds `%HANDLER-CLUSTERS%` and `%RESTART-CLUSTERS%` per thread: the
globals were shared by every thread. `withRestart` pushes a real record whose invoker throws
to a fresh tag, so `compute-restarts`, `(abort)` and SLDB see SWANK's restarts and the
program's alike; the tag is registered with `catchingOutside`, since
`evalTopLevel`/`evalResolved` turn an uncaught `throw` into an error. A `throw`/`return-from`
leaving an evaluation a debugger level runs passes on (`nestedInDebugger`), so
`(invoke-restart 'use-five)` typed in SLDB reaches the debugged form's `restart-case`.

**Frames.** Every function entry pushes a `CallFrame` while `debugging`, one plain field
read per call otherwise (`evalCons` 5,273 -> 5,382 bytecodes). Printed as SWANK's backtrace
printer: `*print-length*` 6, `*print-level*` 4, cut at the first line break and 200
characters. A `restart-case` expansion's own catch tag (its variable `__`-prefixed) is left
out of a frame's catch tags.

**Frame sources** (`v`). A frame remembers the last 8 `LocatedCons` forms it stepped onto
(`CallFrame.at`; free for unlocated code and when not debugging). Only reader forms are
located, never an expansion's -- recording every step put the 20-odd forms of `error`'s
expansion in front of the `(error ...)` call -- so the server locates what the editor sends
too (`CommonLispSwank.located`), in file `""`, line 0, which `ConditionTrace` and
`Definition` ignore. With no placed form, a function answers its body printed with the
newest form marked `#:***HERE***` (SBCL's shape for a REPL function); a built-in's frame
answers its caller's.

**Restart and return** (`r`, `R`). `FrameSignal` (a control transfer) carries the frame and
an action; the activation that pushed the frame takes it after everything further in
unwound. The request answers `(:abort "NIL")`, then `:debug-return`, then the evaluation
goes on -- SBCL's order. The refusal for a return is SWANK's `to-string` of a list
(`"(\"Cannot return from frame: ...\")"`).

**Levels.** `Session.enter`: each level's own `ABORT "Return to sldb level n."` is listed
by deeper levels only (SWANK computes the restarts before establishing it). A level that
ends back into the one below makes it announce itself again (SWANK's `:sldb-return`); its
`ABORT` re-announces too, so returning from level 2 announces level 1 twice, as SBCL does.
A level isolates the thread's `handler-case` clauses (`withHandlerCasesIsolated`): a request
it runs catches nothing for the evaluation it stopped. Java code that catches what it
evaluates runs `withoutDebugger`: the compile requests (errors stay notes), the routed
printer's fallback, a compiler macro, the `deftype` probe, a lazy global.

**Interrupts.** `stopIfInterrupted` on a debugger thread calls `Debugger.interrupted`:
SWANK's `simple-break`, a level over `"Interrupt from Emacs"` under `CONTINUE`, which goes on
with the evaluation; the condition is not signalled, so the request's `:abort` stays
`"NIL"`. A wait the interrupt woke breaks the same way and then waits on:
`Connection.readString`, a level's mailbox, `sleep` (`Environment.sleepMillis` asks
`resumeAfterInterrupt`, then sleeps out its time), `mutex-acquire`. `offerDebugger` clears
a pending interrupt. A thread with no debugger (nREPL, a program's own) stops as before.

## Compilation

There is no file compiler, so compiling is evaluating, under `reportingWarningsTo`; the
first failure is an `:error` note that ends the run. Undefined-function style warnings come
from `SwankSources.record`, which walks the resolved forms through `CallSiteWalk`; a user
macro's call is expanded through `LispEvaluator.expandUserMacro`, whose memo is keyed by
the evaluator's call site, so the expander does not run again when the call runs (it does
after a `defmacro` clears the memo). A top-level reader conditional places every note at
the start. `compile-file-for-emacs` with load-p loads the file and answers loadp nil, fasl
nil, so SLIME sends no `load-file` (SBCL compiles a fasl that SLIME then loads).

## Argument lists

A `defun`, `defmacro` or `defgeneric` the interpreter runs records its lambda list as
written and its documentation string (`LispEvaluator.Definition`; `documentation` still
answers nil on every backend). A function with no record answers its desugared lambda list;
a standard operator answers `cl-arglists.txt`, generated from SBCL 2.2.9's
`sb-introspect:function-lambda-list` over the fbound externals of `COMMON-LISP`, names only
(no defaults, supplied-p or `&aux`); regenerate with `gen-arglists.lisp` in the h18
artefacts. A generic keeps its `defgeneric`'s record across the dispatcher defuns
`defineDispatcher` evaluates; one only `defmethod` made records the method's lambda list as
SBCL derives it (`recordImplicitGeneric`). The same records answer
`rontolisp::%function-lambda-list` and trivial-arguments' `arglist` ([asdf.md](asdf.md),
`eval/TrivialArgumentsForms`). A variable's autodoc (`*DRV* => 10`) is not cacheable.

## Definitions and cross references

The compile walk records each definition and, inside it, calls, special references,
bindings, assignments and user macro calls. A use is placed by searching `(name` forward
from the last hit within its top-level form's lower-cased text, so a big file's walk is
linear; a use inside an expansion that is not a cons of the source is placed at the macro
call. A definition run again replaces its record and drops its uses.

While a server runs, the same walk takes every Common Lisp file a `load` runs
(`LispEvaluator.LoadRecorder`, installed by the `SwankServer` constructor, one volatile read
per load when none is) at its true name's positions, and every REPL or editor evaluation
with no place: such a definition answers SBCL's `DEFINITION-SOURCE ... did not contain
meaningful information.` error. A definition run before a server started answers from
`LispEvaluator.Definition`. Every `M-.` and xref answer of a `C-c C-l`'d file and the REPL
equals SBCL's, positions included. A name that only names a class or structure is no symbol
`findSymbol` finds; its spelling is guessed from the package (`spellingsOf`).

## Inspector

One `SwankInspector` per connection (SWANK's is global). A page is computed once per
inspect and reshown by `pop`/`next` as SWANK's `istate>elisp` does: each showing appends to
the value and action tables, so ids grow. Range arithmetic counts raw parts as SWANK does
(`label-value-line` four, `iline` one). Content follows `swank-fancy-inspector`.
No method or generic function is a first-class object here: a method page's value is a
`LispJavaObject` around the registry row, a generic function is its dispatcher function;
`[remove method]` is `LispEvaluator.removeMethod` (drops the row, reinstalls the
dispatcher). A package is its keyword here, so a keyword `packagep` answers true for gets
the package page. A class's and a slot's documentation come from the `defclass` the walk
recorded (the evaluator keeps none).

## Differences from the oracle

Each dialect's debugger and inspector answer as SWANK's here; against Slynk and micros
they differ only as SWANK's
below do, plus: a SLY REPL thread's own abort restart leaves the REPL reading (Slynk's
thread ends); after `inspector-pop` a value still in the history keeps its `@N=` (Slynk's
history ends at the page shown).

- Frames are the interpreter's own (no SBCL or SWANK internals, every local kept: SBCL
  dropped `drv-f`'s `Y`); no stepping or `sldb-break-with-default-debugger`.
- A frame's `:position` points at the form's `(` (SBCL's one character before); a REPL
  function's source form is `(LAMBDA ...)` on one line (SBCL's pretty-printed
  `(SB-INT:NAMED-LAMBDA ...)`); a refusal names the frame as printed.
- A condition's `FORMAT-CONTROL` is its formatted report, `FORMAT-ARGUMENTS` nil; it prints
  with its report (`#<SIMPLE-ERROR "boom 1">`).
- A compile request's error is a note, never SLDB (SBCL loads the fasl, so a load-time
  error enters it); `frame-package-name` answers `"CL-USER"`.
- No presentation markers, trace, profile, indentation updates, `set-default-directory`,
  SLY stickers or trace dialog. `:calls-who` answers (SBCL's `:not-implemented`); xref
  covers code run while a server runs, in recording order; `find-definitions` of a standard
  package answers nil.
- Inspector titles use rontolisp's `type-of` (`INTEGER` where SBCL says `BIT`, `T` for a
  lambda); no disassembly or type-specifier lines; floats as doubles. The MOP pages show
  rontolisp's MOP: a class with no superclass lists none, a slot without `:initarg` has
  `(:Y)` initargs, a plain class's direct slots are its effective ones, no `Method class`
  line, a method's lambda list is its generic's; a pathname's host and version are nil.
  SWANK 2.32's `[Group by classification]` hangs SBCL's request; here it regroups.
- Macro expansions print on one line (no pretty printer), so macrostep positions differ;
  a package prints as its keyword; no SBCL compiler output or unused-variable note (nor
  micros's `; processing (DEFUN ...)`). Thread ids are Java's. `connection-info` carries
  no `:system-file-patterns`.

## Tests

`cli/SwankServerTest` (in process, port 0, SLIME's recorded requests replayed:
evaluation, I/O, compiles, completion, autodoc, definitions, xref, inspector pages,
macrostep, aborts; `anInterruptEntersTheDebugger...`; `anErrorEntersTheDebugger...`; a
program's `:dont-close t` and default servers and `stop-server`; a SLY and a Lem session on
one server), `RontoLispCliTest#aProgramTheModeCannotTakeIsAClearError`,
`LispEvaluatorAsdfTest#dependsOnBuiltinSwankResolvesWithoutNetworkAndCreateServerSignals`.
By hand: the h18 driver against `rontolisp --swank`, its `nav`, `loaded`, `sldb` and
`frames` steps recorded as `sbcl-*-wire.log` / `rontolisp-*-wire.log` pairs; h21's SLY
driver and Lem in Docker; h24's steps; a program served after its end, `quit-lisp` exiting
0, and the compiled program's message on JVM, Preview 1 and the component.
