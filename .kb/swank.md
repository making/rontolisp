# SWANK: `rontolisp --swank` serves the Common Lisp REPL to SLIME, SLY and Lem

**Invariant: one evaluator serves every request, and a request reads and runs in the
package it names, bound to its own thread (`PackageResolver.bindThreadPackage`) -- two
requests run at once in two packages, as SWANK's REPL thread and workers do.** Shapes
measured against SWANK 2.32 on SBCL 2.2.9, driven by a real SLIME 2.32 (`slime-fancy`) in
Emacs 29.3 batch mode through a logging proxy, then the same driver against rontolisp
(2026-10-30, `.todo/artefacts/h18-common-lisp-swank-server/`): every request below
answered message for message as SBCL's SWANK answers, the differences listed at the end.

## Layout

- `cli/SwankServer` -- transport and threads: a `ServerSocket` on the loopback interface,
  one virtual thread reading each connection, replies written whole under the connection's
  lock. SWANK's `:spawn` style: an `:emacs-rex` naming `:repl-thread` (or the REPL thread's
  id) queues on the connection's REPL thread, `t` gets a thread of its own, another id
  answers `(:invalid-rpc id "Thread not found: N")`. Every request thread takes `--stack`.
- `cli/SwankDialect` -- SWANK, Slynk or micros, told per request by the package prefix of the
  function the form calls (below, "Dialects"); `SwankServer.MRepl` is SLY's REPL channel.
- `cli/SwankWire` -- the framing (six hex digits counting the UTF-8 octets, then one
  S-expression) and a reader of Emacs's `prin1` output into plain values (list, string,
  `Long`, `Keyword`, `Symbol`, `nil` the empty list, `t` `TRUE`, either in any case), printed back as SWANK
  prints with `*print-case* :downcase`.
- `eval/CommonLispSwank` -- the Common Lisp half, so `cli` touches no evaluator internals:
  `serve` (the package bound, `*standard-output*`/`*error-output*`/`*trace-output*` and
  `*standard-input*` bound over the request's writer and reader), evaluation, compilation,
  completion, argument lists, descriptions, expansion, macrostep. It answers Java records
  the server encodes; an autodoc form arrives with the cursor symbol made
  `CommonLispSwank.CURSOR`.
- `eval/SwankSources` -- what compiled code defines and uses (`M-.`, `xref`), fed by the
  compile walk; `eval/SwankInspector` -- one connection's inspector state and pages.
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
| `init-inspector`, `inspect-nth-part`, `inspector-nth-part`, `inspector-range`, `inspector-call-nth-action`, `inspector-pop`, `inspector-next`, `inspector-reinspect`, `inspector-toggle-verbose`, `inspector-eval`, `inspector-history`, `quit-inspector`, `describe-inspectee`, `pprint-inspector-part` | below |
| `swank-macrostep:macro-form-p`, `macrostep-expand-1` (micros's too) | `(:ok :macro)`/`(:ok nil)`; `(:ok "expansion" (("WHEN" :macro 6)))`, each macro form of the expansion placed by printing it and finding it in the printed whole in walk order; `(:error "Not a macro form")` |
| anything else in a dialect's package | aborts: `The function SWANK:X is undefined.` (the package as sent) |
| a form of another package | evaluated in the request's package, its value as a reply carries it (a string, number, symbol, list; anything else `prin1`'d) |

An `:emacs-interrupt` stops the newest running request (`LispEvaluator.interrupt`, the
cooperative stop nREPL uses, `.kb/nrepl.md` "Interrupt"), which answers
`(:abort "#<SIMPLE-ERROR \"Interrupt from Emacs\">")` and its thread goes on; one with
nothing running is dropped. A failure aborts with `#<TYPE "report">`; a stack overflow
puts the control state back first.

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
source is placed at the macro call. A definition compiled again replaces its record and
drops its uses. Places: `(:file f) (:position p)` with a 256-character `(:snippet)` for
`C-c C-k`; `(:buffer-and-file buffer file) (:offset start offset)` with the datum's text
for `C-c C-c` (the request's fourth argument; `(:buffer ...)` without one). A definition
the editor did not compile answers from `LispEvaluator.Definition`, which now keeps the
operator and the `LocatedCons` file and line of a `defun`, `defmacro` or `defgeneric`
(position: the line's first non-blank), else `(:error "Error: DEFINITION-SOURCE of function
F did not contain meaningful information.")` as SBCL answers a REPL definition. A built-in
answers nil. A name that only names a class or structure is no symbol `findSymbol` finds;
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

## Differences from the oracle

No debugger: an error or an interrupt aborts the request (SLDB, its restarts and frames
are not sent). No presentation markers, trace, profile, indentation updates or
`set-default-directory`; SLY's stickers, trace dialog and history references (`#v`), Lem's
inspection of a printed value (`M-.`, xref and the inspector measured with SLIME only). `:calls-who` answers (SBCL's `:not-implemented`); `xref` covers
only editor-compiled code, in compile order (SBCL's order is its own), with no entry for a
REPL-defined caller (SBCL lists one with an `:error` place); `find-definitions` of a
standard package answers nil. An inspector title is `#<TYPE {identity}>` with rontolisp's
`type-of` (`INTEGER` where SBCL says `BIT`, `T` for a lambda), no disassembly or
type-specifier lines, floats as doubles (53 digits). Macrostep expansions print on one
line, so positions differ from SBCL's pretty-printed ones. A package prints as its keyword, a name as rontolisp
spells it (`CL-USER::F`, `CL:MAPC`), a macro expansion on one line (no pretty printer), no
SBCL compiler output and no unused-variable note (nor micros's `; processing (DEFUN ...)`).
Thread ids are Java's. An aborted SLY REPL input names `Interrupt from Emacs` where Slynk's
debugger says `Interrupt from client`. `connection-info` carries no `:system-file-patterns`.
Interpreter only.

## Tests

`cli/SwankServerTest` (in process, port 0: SLIME's requests replayed and answered as
recorded -- REPL values, output and input, package switches, the editor's evaluations,
both compiles with their notes, load, completions, autodoc, arglist, describe, expansions,
definitions, xref, the inspector, macrostep, the aborts; an interrupt stopping the REPL
thread's loop while a worker answers in its own package; an `-e` program starting a
`:dont-close t` server and a default one, both serving its evaluator, then `stop-server`; a
SLY and a Lem session on one server, their recorded shapes),
`RontoLispCliTest#aProgramTheModeCannotTakeIsAClearError`,
`LispEvaluatorAsdfTest#dependsOnBuiltinSwankResolvesWithoutNetworkAndCreateServerSignals`.
By hand: the artefacts' driver against `rontolisp --swank` (2026-10-30; the `nav` steps --
`M-.`, xref, inspector, macrostep -- recorded in `sbcl-nav-wire.log` and
`rontolisp-nav-wire.log`); h21's SLY driver and Lem in Docker against it (2026-10-30); a
program file served after its end and `quit-lisp` ending the
process with 0, and the compiled message on JVM, Preview 1 and the component (2026-10-30).
