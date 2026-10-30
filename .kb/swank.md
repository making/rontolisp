# SWANK: `rontolisp --swank` serves the Common Lisp REPL to SLIME

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
- `cli/SwankWire` -- the framing (six hex digits counting the UTF-8 octets, then one
  S-expression) and a reader of Emacs's `prin1` output into plain values (list, string,
  `Long`, `Keyword`, `Symbol`, `nil` the empty list, `t` `TRUE`), printed back as SWANK
  prints with `*print-case* :downcase`.
- `eval/CommonLispSwank` -- the Common Lisp half, so `cli` touches no evaluator internals:
  `serve` (the package bound, `*standard-output*`/`*error-output*`/`*trace-output*` and
  `*standard-input*` bound over the request's writer and reader), evaluation, compilation,
  completion, argument lists, descriptions, expansion. It answers Java records the server
  encodes; an autodoc form arrives with the cursor symbol made `CommonLispSwank.CURSOR`.
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
| anything else in a `swank*` package | aborts: `The function SWANK:X is undefined.` |
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

No debugger: an error or an interrupt aborts the request (SLDB, its restarts and frames
are not sent). No presentation markers, inspector, xref, `M-.`, trace, profile, indentation
updates or `set-default-directory`. A package prints as its keyword, a name as rontolisp
spells it (`CL-USER::F`, `CL:MAPC`), a macro expansion on one line (no pretty printer), no
SBCL compiler output and no unused-variable note. Thread ids are Java's. SLY (Slynk's
channels) and Lem's micros are not served. Interpreter only.

## Tests

`cli/SwankServerTest` (in process, port 0: SLIME's requests replayed and answered as
recorded -- REPL values, output and input, package switches, the editor's evaluations,
both compiles with their notes, load, completions, autodoc, arglist, describe, expansions,
the aborts; an interrupt stopping the REPL thread's loop while a worker answers in its own
package), `RontoLispCliTest#aProgramTheModeCannotTakeIsAClearError`,
`LispEvaluatorAsdfTest#dependsOnBuiltinSwankResolvesWithoutNetworkAndCreateServerSignals`.
By hand: the artefacts' driver against `rontolisp --swank` (2026-10-30).
