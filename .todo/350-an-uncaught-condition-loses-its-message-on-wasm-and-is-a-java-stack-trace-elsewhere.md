# An uncaught condition loses its message on wasm, and is a Java stack trace elsewhere

Difficulty: High

A program that signals a condition nobody catches reports it THREE different ways,
and the wasm one carries no information at all. This is the last mile of the error
system: everything upstream of it works -- the condition is built, the message is
rendered, `handler-case` can bind it and print it -- and then the top level throws
the text away.

Surfaced from a user investigation into a PostgreSQL authentication failure. The
server's answer (`password authentication failed for user "..."`) is the whole
diagnosis, and on the wasm backends the user's only output was
`wasm trap: wasm 'unreachable' instruction executed`.

## Measured

`(print "before")` then `(error "boom: ~a" 42)`, plus a cl-postgres connect against
a role whose stored verifier the server's `pg_hba` method cannot check:

| backend | what the user sees |
| --- | --- |
| interpreter | `Exception in thread "main" am.ik.rontolisp.eval.LispEvalException: boom: 42` + a JVM stack trace -- 16 lines for the toy, **212 lines** for the cl-postgres connect (frame after frame of `LispEvaluator.evalLet`) |
| JVM | `Exception in thread "main" java.lang.RuntimeException: boom: 42` + 10 frames of mangled Lisp names (`Fail.$pctERROR-RT-47`) |
| wasm Preview 1 | `wasm trap: wasm 'unreachable' instruction executed` -- **no message** |
| wasm component | same -- **no message** |

The message is not unavailable, only unread: the same condition printed through a
`handler-case` on the same wasm module gives `caught: boom: 42`.

## The two wasm regimes -- only one is cheap to fix

`.kb/error-handling.md` ("Top-level trap shape") already builds the machinery; the
landing pad just discards.

- **EH mode ON** (the program contains `handler-case`/`ignore-errors`/
  `unwind-protect` anywhere -- which every program that loads a real library like
  cl-postgres does): `%error` throws a `$lisp-cond` whose payload is
  `(condition-instance . message-string)`, and `_start`'s
  `try_table (catch_all)` catches it. `WasmEmitHelper.emitCatchAllEpilogue` then
  writes exactly one instruction for that landing: `UNREACHABLE`. **The payload,
  with the message in it, is on hand at the catch and dropped.** Confirmed by the
  trap site: EH-on traps in `_start` (wasm function 9), EH-off traps inside the
  `%error` runtime (function 415).
- **EH mode OFF**: `%error` is a bare `unreachable` that evaluates nothing at all
  and there is no tag section. Reporting here means turning EH mode on for every
  program -- 122 KB -> 176 KB on the toy above -- and that trades a byte-identity
  guarantee (`.kb/error-handling.md`: "A program without the forms is
  byte-identical", stash-dance proven across every flag combination) for an error
  path most programs never take.

So the scoped fix is the EH-mode one, and it costs non-EH programs nothing. Whether
EH-off programs get anything (a static "unhandled condition" line, or nothing) is a
decision to record, not to route around silently.

## fd 2 is already reachable from there

`warn` prints `WARNING: on fd 2: 42` on the component backend today, so the
stderr writer and the format/report machinery both already exist in the module and
are already exempted from the lazy-message narrowing. The landing pad needs the
same call, then its `unreachable` (the non-zero exit is right; only the silence is
not).

## The one real design collision: lazy messages (todo-324)

`.kb/error-handling.md` "Signal messages are lazy on wasm-GC" is licensed by an
observation this change FALSIFIES:

> on these backends an uncaught condition exits as a bare `unreachable` trap (no
> text) [...] So the eagerly rendered message every signal site used to build --
> interpreter/JVM need it for the uncaught text -- was written and never read on
> wasm.

That is the re-evaluation trigger `.kb` was told to leave behind, and it has fired.
But it does not simply revert: the cheap answer renders AT THE LANDING PAD, from
the payload car (the condition instance) through the report machinery -- ONE site,
not one per signal. What still needs deciding is the plain-`%error` case, whose
message lives in the payload cdr that `Ctx.condMessagesObservable` currently gates
off. Options, in order of preference:

1. Make the landing pad the second reader of the payload cdr and force
   `condMessagesObservable` on -- correct everywhere, and pay the per-`%error`
   message operand back in module size. Measure it; the gate was introduced to
   delete report-render code from typed signal sites (chipz's `%INFLATE`), and
   those keep their nil cdr either way.
2. Print the CLASS name only when the cdr is nil (`error: SIMPLE-ERROR`), keeping
   the gate. Cheaper, and strictly worse than what the interpreter prints.

Whichever is chosen, the reason goes into `.kb/error-handling.md` next to the
paragraph above, replacing the falsified observation -- not appended below it.

## The interpreter/JVM half is separate and easy

Neither is silent, but both hand a Lisp-level error to the user as a JVM stack
trace: `RontoLispCli` never catches `LispEvalException` (it propagates out of
`main`), and a compiled `.class` throws `RuntimeException` from `main`. 212 frames
of `LispEvaluator.evalLet` is not a diagnostic. What it should print is one line --
the condition's report, and its source position when the frontend recorded one
(`SourceProvenance`, already used for compile failures at `locateCompileFailure`) --
with the stack trace behind a flag or an env var for debugging rontolisp itself.
Exit code stays 1.

## Acceptance

- The same uncaught condition prints a recognizable one-line report on ALL FOUR
  backends (EH mode; `--no-gc` is exempt -- it has no error channel at all,
  `.kb/no-gc-scalar-wasm.md`), pinned by a `ci-spec.yaml` case. The case must
  assert stderr, so check the driver can slice it before writing the case.
- A wasm program with no EH forms is still byte-identical to today's output
  (stash-dance).
- `.kb/error-handling.md` records the new landing-pad contract AND replaces the
  falsified laziness observation with the measured trade-off.
- `doc/{en,ja}/guides/wasm-gc-module.md` says `RuntimeError: unreachable` naming
  nobody is what a load-time failure looks like; if that stops being true for
  signaled conditions, both language versions change in the same commit.

## Not a bug, recorded so it is not re-investigated

The investigation that surfaced this asked whether md5 authentication was broken.
It is not. Against `postgres:13-alpine` (13.23, the reporter's exact version) with
a per-role `pg_hba.conf`, `trust` / `password` / `md5` all connect and return
`current_user` correctly on the interpreter, the JVM and `--component`, matching
what `ClPostgresE2eTest` already pins on `postgres:17-alpine`. A `pg_hba` line of
`scram-sha-256` against an md5-stored verifier fails identically for `psql` and for
rontolisp -- PostgreSQL cannot check an md5 verifier with SCRAM. The client-side
branch is `cl-postgres/protocol.lisp`'s `authenticate` (`ecase` on the `#\R`
message's uint4: 3 = cleartext, 5 = md5, 10/11/12 = SASL), and rontolisp takes
every rung.
