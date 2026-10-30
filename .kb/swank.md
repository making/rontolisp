# SWANK: `rontolisp --swank` serves the Common Lisp REPL to SLIME, SLY and Lem

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
  `Session` per request thread (a SLY REPL's too): its requests (innermost first: SWANK's
  pending continuations) and SLY REPL inputs, their outputs, its debugger levels, its
  mailbox and what answers an event of it; it is the thread's `Sldb`.
- `cli/SwankDialect` -- SWANK, Slynk or micros, told per request by the package prefix of the
  function the form calls (below, "Dialects"); `SwankServer.MRepl` is SLY's REPL channel.
- `cli/SwankWire` -- the framing (six hex digits counting the UTF-8 octets, then one
  S-expression) and a reader of Emacs's `prin1` output into plain values (list, string,
  `Long`, `Keyword`, `Symbol`, `nil` the empty list, `t` `TRUE`, either in any case), printed back as SWANK
  prints with `*print-case* :downcase`.
- `eval/CommonLispSwank` -- the Common Lisp half, so `cli` touches no evaluator internals:
  `serve` (the package bound, `*standard-output*`/`*error-output*`/`*trace-output*` and
  `*standard-input*` bound over the request's writer and reader, the thread's debugger),
  evaluation, compilation, completion, argument lists, descriptions, expansion, macrostep,
  and the debugger's half (`Debugging`, `withRestart`, below). It answers Java records the
  server encodes; an autodoc form arrives with the cursor symbol made
  `CommonLispSwank.CURSOR`.
- `eval/SwankSources` -- what compiled, loaded and typed code defines and uses (`M-.`,
  `xref`); `eval/SwankInspector` -- one connection's inspector state and pages. Both reach
  `CommonLispSwank` only through the `eval/SwankLisp` interface (locations are
  `eval/SwankLocation`), so the three form no class cycle (`PackageCycleTest`).
- `eval/Debugger` (the evaluator's per-thread seam, `LispEvaluator.withDebugger`) and
  `eval/CallFrame` (a thread's call stack). Constructing a `CommonLispSwank` calls
  `LispEvaluator.enableDebugging`: the restart runtime loaded (restart mode for every
  evaluation after), frames kept from then on.
- `RontoLispCli`: `--swank[=PORT]` (value-less key, `=` for the port), SWANK's 4005 by
  default, 0 any free port; refused beside a program, `-o`, `test`, `--nrepl` or a
  non-Common-Lisp `--source-language`; prints `;; Swank started at port: P.`
- A program starts the same server (below).

## Dialects

SLY's Slynk and Lem's micros are SWANK forks: same framing, same `:emacs-rex`, their functions
in their own packages. Recorded 2026-10-30 (`.todo/artefacts/h21-slynk-and-lem-micros-protocols/`):
SLY 1.0.43 against Slynk and Lem 2.3.0 against micros 2ec61c8, both on SBCL 2.2.9, each then
against rontolisp. The deltas were small enough that one server answers all three on one port:
`call` takes the dialect from the operator's package (`swank*`, `slynk*`, `micros*`), maps a
fork's `slynk-require` / `slynk-macroexpand-1` to SWANK's names (`SwankDialect.canonical`),
answers the few shapes of its own, and falls through to SWANK's table. A connection may switch
dialect request by request; a request's output and input follow its dialect.

| | SLY (Slynk) | Lem (micros) |
|---|---|---|
| `connection-info` | `:version "1.0.43"` (SLY asks "Versions differ" otherwise), modules `SLYNK/...` | `:version "0.0.0"` (unchecked) |
| contribs | `slynk-add-load-paths` (nil), `slynk-require` answers `(all-modules loaded-now)` | none required |
| REPL | a channel: `slynk-mrepl:create-mrepl R` answers `(channel thread)` after `(:channel-send R (:write-string "; SLY 1.0.43 (#<MREPL mrepl-C-R>)\n"))` and a prompt; input `(:emacs-channel-send C (:process text))`; answers on `R`: `:write-string`, `(:write-values ((presented history-entry copy)...))` (a number with its notations, `copy` `'sym` / `:kw` / the number / nil), `(:evaluation-aborted "cond")`, `(:prompt pkg prompt 0 history-length)`. The history counts inputs that answered values. A read sends `(:set-read-mode :read)`, takes the next `:process`, then `:finished-reading` | SWANK's `listener-eval` (with `:window-width`); each value `(:write-object "printed" id :repl-result)` then `(:write-string "\n" :repl-result)`, ids per connection; no fresh line before them |
| output | other requests write to the first REPL's channel, no flow control (none without a REPL) | `(:write-string text)`: no target, no thread, no `:write-done` |
| input elsewhere | `(:read-from-minibuffer thread tag prompt nil)` / `(:emacs-return thread tag text)` | SWANK's `:read-string` |
| `eval-for-mrepl` | on the REPL's thread (the request names its id): `guess-and-set-package`, `sync-package-and-default-directory`; answers `(prompt-arguments values)` | |
| echo area | `echo-for-emacs`: no `=> `, `; No value`, hex up to 64 bits, octal/binary up to 8, ratio with float and percentage | SWANK's, uncut, `; No value` (`Echo`) |
| completion | `slynk-completion:simple-completions` `(strings common-prefix)`; `flex-completions` `((text score chunks classification)...) nil`, Slynk's score in single floats (`mvbind` 0.009072388522326946, as SBCL), accessible symbols then other packages' externals qualified by each name, `::` every symbol at home | `micros/lsp-api:completions`: SWANK's fuzzy (100) as `(label chunks kind signature markdown sort-key)` |
| other | | `micros/pretty-eval:pretty-eval` `(:value echo :id n)`, `micros/lsp-api:hover-symbol` Markdown, `micros/macrostep:macrostep-expand-1` SWANK's macrostep; `C-c C-l` sends a form calling `micros:load-file`, defined in the image at `micros:connection-info` |
| interrupt | `(:emacs-interrupt thread)` of the REPL's thread | `(:emacs-interrupt :repl-thread)` |
| debugger | SWANK's levels; `SLY` for `SLIME` in the restarts (`Return to SLY's top level.`, `Retry SLY interactive evaluation request.`, the REPL's `Retry SLY mREPL evaluation request.`, `Return to sly-db level 1.`), threads `slynk-worker` and `sly-channel-C-mrepl-remote-R`, `(:debug-activate thread level)` without the nil, `Interrupt from client`; `sly-db-abort` / `sly-db-continue` | SWANK's restarts and levels, no `:debug-activate`, `Interrupt from Editor` |
| debugger in the REPL | an error's level sends `(:prompt pkg prompt level history "cond")` first (level: the error levels open, once per input), the REPL reads on inside it, its prompts showing the level; an input a restart leaves answers `(:evaluation-aborted "cond")` -- the last condition its level was entered for, not one of an input nested in it, `"T"` for an interrupt's -- and a prompt | |
| inspector | `(slynk:eval-for-inspector current target 'fn args...)`: `fn` in the inspector `current` names (nil `default`, SLIME's), a value it inspects shown in `target`; `slynk-mrepl:inspect-entry` / `describe-entry` / `pprint-entry` `(channel entry value)`; `#vN:M` in the REPL reads as that history value, `#vN` as the entry's values | `micros:inspect-printed-object id` (a REPL value's `:write-object` id, history kept), `micros/pretty-eval:inspect-evaluation-value id` (`pretty-eval`'s own ids from 1, inspector reset, one value or the list), `clear-printed-objects`, `remove-evaluated-values` |

## A program's server

`eval` may not import `cli`, so the command line hands every evaluator it creates (a file,
`-e`, the REPL, `--swank`'s) an `eval/SwankHost` before the program runs
(`LispEvaluator.setSwankHost`, `cli/SwankServers`). The `swank` shim system
(`swank.lisp`) calls it under `#+rontolisp-interpreter`: `create-server` takes SWANK's
keys, `rontolisp::%swank-listen` binds (`:interface`, loopback by default), the shim prints
`;; Swank started at port: P.` on `*error-output*` (SWANK's `*log-output*`), then
`%swank-serve`: `:dont-close t` accepts on a non-daemon thread `Swank P` and returns,
nil (SWANK's default) accepts the first connection on the calling thread and closes the
port. It answers the port. `stop-server` (`%swank-stop`) closes the port, connections going
on; an unknown port warns `No server for :PORT: P`, as SWANK does. `:style` and `:backlog`
are ignored.

- Such a server's connections read on platform non-daemon threads, so a program that ran
  off its end is served until the port closes and the editor disconnects (SBCL's script
  needs `(loop (sleep 1000))` for that).
- A request's quit (`quit-lisp`) closes the server and ends the process: `main` hands the
  CLI `System::exit`; an embedded `RontoLispCli` (tests) only closes that server.
- An evaluator with no host (embedded) signals from `create-server`. A compiled program's
  shim (`#-rontolisp-interpreter`) signals, naming the interpreter, and its `stop-server`
  answers nil.

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
| `buffer-first-change`, `init-presentations`, `update-indentation-information`, `fuzzy-completion-selected` | nil |
| `find-definitions-for-emacs`, `xref`, `xrefs` | below |
| `init-inspector`, `inspect-nth-part`, `inspector-nth-part`, `inspector-range`, `inspector-call-nth-action`, `inspector-pop`, `inspector-next`, `inspector-reinspect`, `inspector-toggle-verbose`, `inspector-eval`, `inspector-history`, `quit-inspector`, `describe-inspectee`, `describe-inspector-part`, `pprint-inspector-part` | below |
| `swank-macrostep:macro-form-p`, `macrostep-expand-1` (micros's too) | `(:ok :macro)`/`(:ok nil)`; `(:ok "expansion" (("WHEN" :macro 6)))`, each macro form of the expansion placed by printing it and finding it in the printed whole in walk order; `(:error "Not a macro form")` |
| `throw-to-toplevel`, `invoke-nth-restart-for-emacs`, `invoke-nth-restart`, `sldb-abort`, `sldb-continue`, `backtrace`, `debugger-info-for-emacs`, `frame-locals-and-catch-tags`, `frame-package-name`, `eval-string-in-frame`, `pprint-eval-string-in-frame` | the thread's innermost debugger level, below; `frame-source-location` `(:error "No source location")` |
| anything else in a dialect's package | aborts: `The function SWANK:X is undefined.` (the package as sent) |
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
on: `Connection.readString` (`:read-aborted` only when left), the level's own mailbox (a deeper
level), `sleep` (`Environment.sleepMillis` asks `resumeAfterInterrupt`, then sleeps out its
time). `offerDebugger` clears a pending interrupt, which the level answers. A thread with
no debugger (nREPL, a program's own) stops as before.

## Compilation

There is no file compiler, so compiling is evaluating. `compile-string-for-emacs` runs
each form in turn under `reportingWarningsTo` (a warning no handler muffles is a
`:warning`/`:style-warning` note at its form); the first failure is an `:error` note that
ends the run, the place the text stops reading a `:read-error` note, and once it ran, a
call of a function nothing defines a `:style-warning` `undefined function: PKG::NAME` at
the first call in the text. That walk (`SwankSources.record`) takes the forms as resolved
(`LispEvaluator.evalTopLevel`'s consumer) through `CallSiteWalk`, local functions in scope;
a built-in operator outside the shapes it knows is not looked into. A user macro's call is
expanded through `LispEvaluator.expandUserMacro`, whose memo is keyed by the call site the
evaluator runs, so the expander does not run again when the call runs (it does after a
`defmacro` clears the memo) -- before 2026-10-30 the walk skipped macro calls and missed
the calls in their arguments. Locations: `(:buffer name) (:offset
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

## Definitions and cross references

The compile walk records, per top-level form, each definition -- `defun`, `defmacro`,
`defgeneric`, `defmethod`, `defvar`/`defparameter` (`(DEFVAR X)` as SBCL), `defconstant`,
`deftype`, `defclass`/`define-condition` with each accessor, reader and writer as a method
at its slot, `defstruct` with its constructor, predicate, copier and accessors as `DEFUN`s
at the form, `defpackage` -- and, inside a definition, each call (and `#'`), special
variable reference (`CallSiteWalk.Rewriter.variable`, the hook symbols in code positions
reach), `let`/`let*` binding, `setq`/`setf`/`psetq`/`psetf` assignment and user macro call.
A use is placed at its form's text, found by searching `(name` forward from the last hit
within the top-level form; a use inside a macro's expansion that is not a cons of the
source is placed at the macro call. A definition run again replaces its record and
drops its uses. Places: `(:file f) (:position p)` with a 256-character `(:snippet)` for
`C-c C-k`; `(:buffer-and-file buffer file) (:offset start offset)` with the datum's text
for `C-c C-c` (the request's fourth argument; `(:buffer ...)` without one).

The same walk takes, while a server runs, every Common Lisp file a `load` runs
(`C-c C-l`, a program's `load`, an ASDF component; `LispEvaluator.LoadRecorder`, installed
by the `SwankServer` constructor, one volatile read per load when none is) at its true
name's positions -- a form starts at the first top-level datum on its `LocatedCons` line,
a datum a top-level reader conditional left out skipped -- and every REPL or editor
evaluation (`listener-eval`, `interactive-eval` ...) with no place: such a definition
answers `(:error "Error: DEFINITION-SOURCE of KIND NAME did not contain meaningful
information.")` (`function`, `macro`, `variable` ... as SBCL), also as an xref container.
The search for a use stays inside its top-level form's lower-cased text, so a big file's
walk is linear. A definition run before a server started answers from
`LispEvaluator.Definition` (the `LocatedCons` file and line of a `defun`, `defmacro` or
`defgeneric`, position the line's first non-blank), else the error above. Measured
against SBCL with the driver's `loaded` steps (2026-10-30): every `M-.` and xref answer
of a `C-c C-l`'d file and the REPL equal SBCL's, positions included, in recording order.
A built-in answers nil. A name that only names a class or structure is no symbol `findSymbol` finds;
its spelling is guessed from the package (`spellingsOf`). `xref` answers `:calls`,
`:references`, `:binds`, `:sets`, `:macroexpands` at the use labelled with the container
(`F`, or `(DEFMETHOD F (SPECIALIZERS))`), `:callers` at the container, `:calls-who` and
`:callees` the callees' definitions, `:specializes` `(DEFMETHOD F)`; another kind
`:not-implemented`.

## Inspector

One `SwankInspector` per connection (SWANK's is global). A page is computed once per
inspect and reshown by `pop`/`next` as SWANK's `istate>elisp` does: each showing appends
to that state's value and action tables, so ids grow (`:id 4` on the second showing). A
value printed after it was inspected carries `@N=` (an `eql` position in the history).
Range arithmetic is SWANK's, counting raw parts: `label-value-line` is four, `iline` one.
Content follows `swank-fancy-inspector`: list (proper, improper, circular), cons, array
(dimensions, element type, size, adjustable, fill pointer, displacement, elements),
integer (`format` line, code-char, integer-length, universal time), ratio, complex, float,
character, hash table (`[clear hashtable]`, `[remove entry]`), symbol (value, function,
documentation, package with `[export]`/`[unintern]`, plist, class), function (name,
argument list, documentation), a `defclass` instance (the check boxes, grouping by
declaring class, alphabetical sort, `[set value]` reading the minibuffer through
`:read-from-minibuffer`/`:emacs-return`, `[make unbound]`), a structure as SBCL's
`The object is a STRUCTURE-OBJECT of type X.`, anything else SWANK's generic page.

The metaobject pages (2026-10-30, the `loaded` steps' `inspector-pages`): a class (name,
superclasses, direct and effective slots, documentation, subclasses, precedence list, the
methods specialized on it, a prototype from `allocate-instance`, then its own slots); a
slot definition (name, documentation, initargs, initform -- shown when the initform or
initfunction is non-nil, a plain class's slot carrying no function --, initfunction); a
generic function (name, arguments, documentation, method combination, methods by
applicability with `[remove method]`, which `LispEvaluator.removeMethod` drops from the
registry and reinstalls the dispatcher); a method; a package (a package is its keyword
here, so a keyword `packagep` answers true for gets the package page) with SWANK's
`%package-symbols-container` pages and their `[Group by classification]`; a pathname.
No method or generic function is a first-class object here: a method page's value is a
`LispJavaObject` around the registry row, a generic function is its dispatcher function.
A class's and a slot's documentation come from the `defclass` the walk recorded (the
evaluator keeps none). A metaobject prints as SBCL's `#<STANDARD-CLASS NAME>` on a page.

## Differences from the oracle

Each dialect's debugger and inspector answer as SWANK's here. Measured 2026-10-30 with SLY
1.0.43 against Slynk on SBCL 2.2.9 and against rontolisp (`.todo/artefacts/h24-sly-and-lem-debugger-and-inspector/`):
every debugger message of the `sldb` steps agrees but the frames, and `M-.`, xref and the
inspector of the `nav` steps differ from Slynk only as SWANK's below do; Lem 2.3.0's
error, restart, `cerror` continue and interrupt steps answer as micros's recording. A SLY
REPL thread's own abort restart leaves the REPL reading (Slynk's thread ends). After
`inspector-pop` a value still in the history keeps its `@N=` in SLY's inspector too
(Slynk's history ends at the page shown). The debugger's frames are the interpreter's own (no SBCL or SWANK internals, none restartable, every
local kept: SBCL dropped `drv-f`'s `Y`), a frame shows no source and no catch tags, no
`restart-frame`, `sldb-return-from-frame`, stepping or `sldb-break-with-default-debugger`;
a condition prints with its report (`#<SIMPLE-ERROR "boom 1">`, SBCL
`#<SIMPLE-ERROR "boom ~a" {...}>`); a compile request's error is a note, never SLDB (SBCL
loads the fasl, so a load-time error enters it); `frame-package-name` answers `"CL-USER"`.
No presentation markers, trace, profile, indentation updates or
`set-default-directory`; SLY's stickers and trace dialog. `:calls-who` answers (SBCL's `:not-implemented`); `xref` covers
code run while a server runs, in recording order (SBCL's order is its own);
`find-definitions` of a
standard package answers nil. An inspector title is `#<TYPE {identity}>` with rontolisp's
`type-of` (`INTEGER` where SBCL says `BIT`, `T` for a lambda; `PACKAGE`,
`STANDARD-GENERIC-FUNCTION`, `STANDARD-METHOD` for those pages), no disassembly or
type-specifier lines, floats as doubles (53 digits). The metaobject pages show rontolisp's
MOP: a class with no superclass lists none and its precedence list only itself (SBCL's
`STANDARD-OBJECT`, `SLOT-OBJECT`, `T`), a slot without `:initarg` has `(:Y)` initargs, a
plain class's direct slots are its effective slot definitions, a generic function page
has no `Method class` line and no slots, a method's lambda list is its generic's, method
documentation is a slot accessor's only; a pathname's host is nil and its version nil
(SBCL's `#<UNIX-HOST>`, `:NEWEST`); `CL-USER`'s name is `"CL-USER"`, `CL`'s `"CL"`.
SWANK 2.32's `[Group by classification]` hangs SBCL's request; here it regroups. Macrostep expansions print on one
line, so positions differ from SBCL's pretty-printed ones. A package prints as its keyword, a name as rontolisp
spells it (`CL-USER::F`, `CL:MAPC`), a macro expansion on one line (no pretty printer), no
SBCL compiler output and no unused-variable note (nor micros's `; processing (DEFUN ...)`).
Thread ids are Java's. `connection-info` carries no `:system-file-patterns`.
Interpreter only.

## Tests

`cli/SwankServerTest` (in process, port 0: SLIME's requests replayed and answered as
recorded -- REPL values, output and input, package switches, the editor's evaluations,
both compiles with their notes, load, completions, autodoc, arglist, describe, expansions,
definitions, xref (a loaded file's and a REPL definition's too), the inspector (the class,
slot definition, generic function, method, package, symbols and pathname pages), macrostep,
the aborts; `anInterruptEntersTheDebugger...`: the loop's simple-break continued and quit
while a worker answers in its own package, a sleep continued;
`anErrorEntersTheDebugger...`: `q`, `c`, a numbered restart, `cerror`'s continue, a
worker's `a`, frames, locals, eval in frame, a nested level returned from; an `-e`
program starting a `:dont-close t` server and a default one, both serving its evaluator,
then `stop-server`; a SLY and a Lem session on one server, their recorded shapes, SLY's
REPL debugger with a level read inside, a history entry inspected and read by `#v`, a SLY
worker's level, an interrupt continued and quit, Lem's inspection by id and its levels),
`RontoLispCliTest#aProgramTheModeCannotTakeIsAClearError`,
`LispEvaluatorAsdfTest#dependsOnBuiltinSwankResolvesWithoutNetworkAndCreateServerSignals`.
By hand: the artefacts' driver against `rontolisp --swank` (2026-10-30; the `nav` steps --
`M-.`, xref, inspector, macrostep -- recorded in `sbcl-nav-wire.log` and
`rontolisp-nav-wire.log`; the `loaded` steps -- `C-c C-l`, REPL definitions, their `M-.`
and xref, the metaobject, package and pathname pages -- in `sbcl-loaded-wire.log` and
`rontolisp-loaded-wire.log`; the `sldb` steps -- the debugger -- in `sbcl-sldb-wire.log`
and `rontolisp-sldb-wire.log`); h21's SLY driver and Lem in Docker against it, h24's
`sldb` and `nav` steps and Lem steps (2026-10-30)
(2026-10-30); a program file served after its end and `quit-lisp` ending the
process with 0, and the compiled message on JVM, Preview 1 and the component (2026-10-30).
