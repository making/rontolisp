# SWANK: `rontolisp --swank` serves the Common Lisp REPL to SLIME

**Invariant: one evaluator serves every request, and a request reads and runs in the
package it names, bound to its own thread (`PackageResolver.bindThreadPackage`) -- two
requests run at once in two packages, as SWANK's REPL thread and workers do.**
**Invariant: an error no handler handles enters the debugger on the thread it was
signalled on, before anything unwinds, after its handlers ran -- once per error.** Shapes
measured against SWANK 2.32 on SBCL 2.2.9, driven by a real SLIME 2.32 (`slime-fancy`) in
Emacs 29.3 batch mode through a logging proxy, then the same driver against rontolisp
(2026-10-30, `.todo/artefacts/h18-common-lisp-swank-server/`): every request below
answered message for message as SBCL's SWANK answers, the differences listed at the end.

## Layout

- `cli/SwankServer` -- transport and threads: a `ServerSocket` on the loopback interface,
  one virtual thread reading each connection, replies written whole under the connection's
  lock. SWANK's `:spawn` style: an `:emacs-rex` naming `:repl-thread` (or the REPL thread's
  id) queues on the connection's REPL thread, `t` gets a thread of its own, a running
  worker's id its `Session`'s mailbox (what its debugger level answers), another id
  `(:invalid-rpc id "Thread not found: N")`. Every request thread takes `--stack`. A
  `Session` per request thread: its requests (innermost first: SWANK's pending
  continuations), their outputs, its debugger levels; it is the thread's `Sldb`.
- `cli/SwankWire` -- the framing (six hex digits counting the UTF-8 octets, then one
  S-expression) and a reader of Emacs's `prin1` output into plain values (list, string,
  `Long`, `Keyword`, `Symbol`, `nil` the empty list, `t` `TRUE`), printed back as SWANK
  prints with `*print-case* :downcase`.
- `eval/CommonLispSwank` -- the Common Lisp half, so `cli` touches no evaluator internals:
  `serve` (the package bound, `*standard-output*`/`*error-output*`/`*trace-output*` and
  `*standard-input*` bound over the request's writer and reader, the thread's debugger),
  evaluation, compilation, completion, argument lists, descriptions, expansion, and the
  debugger's half (`Debugging`, `withRestart`, below). It answers Java records the server
  encodes; an autodoc form arrives with the cursor symbol made `CommonLispSwank.CURSOR`.
- `eval/Debugger` (the evaluator's per-thread seam, `LispEvaluator.withDebugger`) and
  `eval/CallFrame` (a thread's call stack). Constructing a `CommonLispSwank` calls
  `LispEvaluator.enableDebugging`: the restart runtime loaded (restart mode for every
  evaluation after), frames kept from then on.
- `RontoLispCli`: `--swank[=PORT]` (value-less key, `=` for the port), SWANK's 4005 by
  default, 0 any free port; refused beside a program, `-o`, `test`, `--nrepl` or a
  non-Common-Lisp `--source-language`; prints `;; Swank started at port: P.`
- The `swank` shim system (`swank.lisp`) keeps signalling from `create-server`, its
  message now naming `rontolisp --swank`: a program cannot start the server.

## A thread's package

Before (2026-10-30) the resolver's current package was one cell per evaluator, `*package*`
read and written through it on every thread (`.kb/symbol-runtime-api.md`). Now a thread may
hold its own current package and saved-package stack (`ThreadPackage` in a `ThreadLocal`):
`current()`/`setCurrent()`/`stack()` go to it when the thread has one, so an `in-package`,
a `let` of `*package*`, a `load`'s push and pop and a macro expander's package swap stay on
that thread. The reads take the thread-local only while a binding is live anywhere (an
`AtomicInteger` count), so a program that never binds pays one volatile read. A request's
package comes from SWANK's `guess-package` reading (`:foo`, `#:foo`, `"FOO"`, any case),
`cl-user` when none matches. The resolver is a monitor: a resolution walk keeps transient
state in fields (`inQuotedData`, `exactCase` ...), and a printer's `printsBare` sets one too,
so every public method but the plain reads is `synchronized` (the resolver calls out to
nothing, so it holds no other lock). The macro-time warning sink is thread-local and the
user macro table concurrent for the same reason: a worker defines while the REPL runs.

## Output and input

- A request's output is its own writer: flushed when asked, at 64000 characters, 20 ms
  after a write (on a virtual thread: a flush waits), and before the results. A flush sends
  `(:write-string text nil id)` and waits for the editor's `(:write-done id)`, SWANK's flow
  control. An interrupted request's output is flushed before its `:abort`.
- REPL results go as `(:write-string "value\n" :repl-result)`, `"; No value"` for none,
  after a fresh line on the output (the presentation contrib's `fresh-line`); a package
  that changed is announced `(:new-package name prompt)` before the `:return`.
- Standard input asks `(:read-string id tag)` and waits for `(:emacs-return-string id tag
  text)`; the stream table reads off a `BufferedReader`, so the reader is wrapped in one.

## Requests

| Request | Answer |
|---|---|
| `connection-info` | pid, `:spawn`, `utf-8-unix`, `rontolisp` and its version, the host, `*features*`, the modules, `CL-USER`, protocol `"2.32"` |
| `swank-require` | every module asked for joins the list (a contrib's functions not answered abort) |
| `create-repl` | the REPL thread started; `("CL-USER" "CL-USER")` |
| `listener-eval` | `eval-region`: each form in turn, the last one's values; `*` `**` `***` `/` `//` `///` `+` `++` `+++` set (defined nil at start) |
| `interactive-eval`, `interactive-eval-region` | `format-values-for-echo-area`: `=> 5 (3 bits, #x5, #o5, #b101)`, a ratio with its float, `=> 1, 2`, `""` for no value (SWANK's `cond` answers `"; No value"` without writing it), truncated to the echo area |
| `eval-and-grab-output`, `pprint-eval`, `re-evaluate-defvar` | the output and the values; a `defvar` re-run as a `defparameter` (no `makunbound` here) |
| `set-package`, `list-all-package-names` | `(name prompt)`; the names, nicknames when asked |
| `compile-string-for-emacs`, `compile-file-for-emacs`, `load-file` | below |
| `simple-completions`, `completions`, `fuzzy-completions` | prefix, compound prefix per `-` (`m-v-b`) with packages, SWANK's fuzzy score; `(text flags qualified)` with the `bfgctmspa` flags |
| `autodoc`, `operator-arglist` | below |
| `describe-symbol`, `describe-function`, `documentation-symbol` | what the symbol names, its lambda list and documentation string, a variable's value |
| `swank-macroexpand-1`, `swank-macroexpand`, `swank-macroexpand-all` | `macroexpand-1`, `macroexpand`, and every macro in the form (`CallSiteWalk`'s code positions) |
| `buffer-first-change`, `init-presentations`, `update-indentation-information`, `fuzzy-completion-selected` | nil; `find-definitions-for-emacs`, `xref`, `xrefs` nil (no definition known) |
| `throw-to-toplevel`, `invoke-nth-restart-for-emacs`, `invoke-nth-restart`, `sldb-abort`, `sldb-continue`, `backtrace`, `debugger-info-for-emacs`, `frame-locals-and-catch-tags`, `frame-package-name`, `eval-string-in-frame`, `pprint-eval-string-in-frame` | the thread's innermost debugger level, below; `frame-source-location` `(:error "No source location")` |
| anything else in a `swank*` package | aborts: `The function SWANK:X is undefined.` |
| a form of another package | evaluated in the request's package, its value as a reply carries it (a string, number, symbol, list; anything else `prin1`'d) |

A request a thread starts with runs under SWANK's top-level restarts (`topLevel`): the
thread's `ABORT "abort thread (#<THREAD \"repl-thread\" RUNNING {ID}>)"` (the REPL thread
then ends, the next REPL request starts another) and inside it `ABORT "Return to SLIME's
top level."`, the quit restart. `listener-eval` (`"Retry SLIME REPL evaluation request."`),
`interactive-eval`(-`region`) (`"... interactive evaluation request."`),
`eval-and-grab-output` and `re-evaluate-defvar` (`"Retry SLIME evaluation request."`) run
under `SWANK::RETRY`, which runs them again. A request a restart further out leaves answers
`(:abort ...)` on its way (`CommonLispSwank.isTransfer`). An `:abort` names the last
condition a debugger level was entered for in the request (SWANK's `eval-for-emacs` records
every one, so an outer request aborted from a nested level reports the inner condition),
else `"NIL"`; any other failure aborts with `#<TYPE "report">`; a stack overflow puts the
control state back first. An `:emacs-interrupt` targets the newest running request
(`LispEvaluator.interrupt`, the cooperative stop nREPL uses, `.kb/nrepl.md` "Interrupt");
one with nothing running is dropped.

## The debugger

**Where it enters.** `LispEvaluator.signalPoint`: an error a built-in raised (`apply`'s seam,
the built-in's call kept as a frame unless it is a `%` primitive or a signalling operator)
or one a frame is leaving (`evalCons`'s catch, on a thread with a debugger -- elsewhere only
the `funcall` seam as before) runs `withHandlerBindHandlersRun`, then `offerDebugger`: the
thread's debugger, unless the error was offered already (`LispEvalException.offerDebugger`)
or a `handler-case` takes it (`anyHandlerCaseMatches`). `signal` and `warn` never reach it.
What no frame signalled (a top-level variable, an unmatched `throw`) meets it at the
request's form (`atSignalPoint`). The level runs inside that call; it leaves by a restart's
`throw`, or returns when the connection closed and the error unwinds as before. Two traps:
the hooked `error` terminal arrives with its handlers run, so the offer cannot ride
`handlersRan`; and on a debugger thread an un-seamed error (undefined function, unbound
variable) runs its `handler-bind` handlers at the innermost frame, before the cleanups
between it and the `handler-bind` -- CL's order, not the pad's of other threads.

**Restarts.** `serve` binds `%HANDLER-CLUSTERS%` and `%RESTART-CLUSTERS%` per thread (nil
for a thread's first request, the debugged evaluation's for a nested one): the globals were
shared by every thread. `withRestart` pushes a real record whose invoker throws a
`ThrowSignal` to a fresh tag, so `compute-restarts`, `(abort)` and SLDB see SWANK's restarts
and the program's alike; the tag is registered with `catchingOutside`, since
`evalTopLevel`/`evalResolved` turn a `throw` nothing inside catches into an error. A
`throw`/`return-from` leaving an evaluation a debugger level runs passes on too
(`nestedInDebugger`): `(invoke-restart 'use-five)` typed in SLDB reaches the debugged form's
`restart-case`. A report string shows as written, a report function's output, else the name.

**Frames.** `CallFrame`: every function entry pushes one (`evalCons`'s loop, a tail call
replacing it; `apply`'s frame-keeping path) while `debugging`, one plain field read per call
otherwise (`evalCons` 5,273 -> 5,382 bytecodes). Markers: `(EVAL form)` per top-level form,
the request's form, `(SWANK::SLDB-LOOP n)` under each level. A frame's locals walk its
innermost scope up to its call scope (the signal's scope for the innermost, the next frame's
caller scope further out, the scope its own `let`s made for a marker);
`eval-string-in-frame` evaluates there. Printed under `*print-length*` 6 / `*print-level*`
4, cut at the first line break (` ..` and the open lists closed) and at 200 characters,
SWANK's backtrace printer. `ControlState` restores the stack top after an overflow.

**Levels.** `Session.enter`: the requests' conditions recorded, output flushed, then under
`(SWANK::SLDB-LOOP n)` and its own `ABORT "Return to sldb level n."` -- listed by deeper
levels only, as SWANK computes the restarts before establishing it -- `(:debug tid n
(message type nil) restarts frames(0..20) conts)` and `(:debug-activate tid n nil)`, then
the mailbox's requests run nested until a transfer leaves; `(:debug-return tid n nil)` on
the way out. A level that ends back into the one below makes it announce itself again
(`returned`, SWANK's `:sldb-return` event); its `ABORT` restart re-announces too, so
returning from level 2 announces level 1 twice, as SBCL does. A level isolates the thread's
`handler-case` clauses (`withHandlerCasesIsolated`): a request it runs catches nothing for
the evaluation it stopped. Java code that catches what it evaluates runs without the
debugger (`withoutDebugger`): the compile requests (errors stay notes), helpers, the routed
printer's fallback, a compiler macro, the `deftype` probe, a lazy global.

**Interrupts.** `stopIfInterrupted` on a debugger thread calls `Debugger.interrupted`:
SWANK's `simple-break`, a level over `"Interrupt from Emacs"` under `CONTINUE "Continue
from break."`, which goes on with the evaluation; the condition is not signalled, so the
request's `:abort` stays `"NIL"`. A wait the interrupt woke breaks the same way and waits
on: `Input.fill` (`:read-aborted` only when left), the level's own mailbox (a deeper
level), `sleep` (`Environment.sleepMillis` asks `resumeAfterInterrupt`, then sleeps out its
time). `offerDebugger` clears a pending interrupt, which the level answers. A thread with
no debugger (nREPL) stops as before.

## Compilation

There is no file compiler, so compiling is evaluating. `compile-string-for-emacs` runs
each form in turn under `reportingWarningsTo` (a warning no handler muffles is a
`:warning`/`:style-warning` note at its form); the first failure is an `:error` note that
ends the run, the place the text stops reading a `:read-error` note, and once it ran, a
call of a function nothing defines a `:style-warning` `undefined function: PKG::NAME` at
the first call in the text. That walk takes the forms as resolved
(`LispEvaluator.evalTopLevel`'s consumer) through `CallSiteWalk`, local functions in scope;
a built-in operator outside the shapes it knows is not looked into, nor a user macro's call
(expanding it again would run its expander twice). Locations: `(:buffer name) (:offset
start offset)` and `(:file path) (:position 1-based)`, a form's offset from a scan of the
text's top-level data (a top-level reader conditional places every note at the start).
`compile-file-for-emacs` with load-p loads the file this way and answers loadp nil, fasl
nil, so SLIME sends no `load-file` (SBCL's compiles a fasl and SLIME then loads it);
without load-p it only reads.

## Argument lists

A `defun`, `defmacro` or `defgeneric` the interpreter runs writes down its lambda list as
written and its documentation string (`LispEvaluator.Definition`; `documentation` still
answers nil on every backend). A function with no record answers its desugared lambda list,
a standard operator the table `cl-arglists.txt` generated from SBCL 2.2.9's
`sb-introspect:function-lambda-list` over the fbound externals of `COMMON-LISP`, names only
(no defaults, supplied-p or `&aux`); regenerate with `gen-arglists.lisp` in the artefacts.
`autodoc` takes the innermost operator around the cursor with an argument list and marks
the parameter the argument binds -- by position, `&rest`/`&body` past them, a keyword by
the keyword before the value, inside a destructuring parameter the item within it -- or
shows a variable's value when the cursor is on one (`*DRV* => 10`, not cacheable).

## Differences from the oracle

The debugger's frames are the interpreter's own (no SBCL or SWANK internals, none
restartable, every local kept: SBCL dropped `drv-f`'s `Y`), a frame shows no source and no
catch tags, no `restart-frame`, `sldb-return-from-frame`, stepping or
`sldb-break-with-default-debugger`; a condition prints with its report
(`#<SIMPLE-ERROR "boom 1">`, SBCL `#<SIMPLE-ERROR "boom ~a" {...}>`); a compile request's
error is a note, never SLDB (SBCL loads the fasl, so a load-time error enters it); a
`with-simple-restart`'s or `cerror`'s report is its control without the arguments. No
presentation markers, inspector, xref, `M-.`, trace, profile, indentation updates or
`set-default-directory`. A package prints as its keyword, a name as rontolisp spells it
(`CL-USER::F`, `CL:MAPC`), `frame-package-name` `"CL-USER"`, a macro expansion on one line
(no pretty printer), no SBCL compiler output and no unused-variable note. Thread ids are
Java's. SLY (Slynk's channels) and Lem's micros are not served. Interpreter only.

## Tests

`cli/SwankServerTest` (in process, port 0: SLIME's requests replayed and answered as
recorded -- REPL values, output and input, package switches, the editor's evaluations,
both compiles with their notes, load, completions, autodoc, arglist, describe, expansions,
the aborts; `anInterruptEntersTheDebugger...`: the loop's simple-break continued and quit
while a worker answers in its own package, a sleep continued;
`anErrorEntersTheDebugger...`: `q`, `c`, a numbered restart, `cerror`'s continue, a
worker's `a`, frames, locals, eval in frame, a nested level returned from),
`RontoLispCliTest#aProgramTheModeCannotTakeIsAClearError`,
`LispEvaluatorAsdfTest#dependsOnBuiltinSwankResolvesWithoutNetworkAndCreateServerSignals`.
By hand: the artefacts' drivers against `rontolisp --swank` (2026-10-30; the SLDB steps in
`drive-sldb.el`, recorded as `sbcl-sldb-wire.log` / `rontolisp-sldb-wire.log`).
