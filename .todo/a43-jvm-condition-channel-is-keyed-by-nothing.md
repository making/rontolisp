# The JVM condition channel `_condTl` is keyed by nothing

Difficulty: High

Found 2026-09-26 while doing a33. A compiled program's typed condition lives in two places: the
thrown `RuntimeException` and the per-thread `_condTl`, which `%error-cond` / `%signal-cond` set
and a `handler-case` landing or the `%hb-guard` pad reads and clears. Nothing ties the two
together, so a plain `error` (or a raw failure) raised while a typed condition is on its way out
is read as that condition, and the typed one is lost after it:

```lisp
(define-condition typed-failure (error) ())
(print (handler-case
           (unwind-protect (error 'typed-failure)
             (print (handler-case (error "plain") (typed-failure () :read-as-the-typed-one) (error () :plain))))
         (typed-failure () :typed)
         (error () :lost-its-type)))
```

| printed | interpreter | wasm-GC | JVM (2026-09-26, before and after a33) |
|---|---|---|---|
| inner | `:PLAIN` | `:PLAIN` | `:READ-AS-THE-TYPED-ONE` |
| outer | `:TYPED` | `:TYPED` | `:LOST-ITS-TYPE` |

- The exit channel `_nleTl` already has the shape this needs: a stack whose entries are keyed by
  their throwable (`entry[0] == caught`). Direction: `_condTl` as a stack of `{throwable,
  condition}`; a landing takes the entry of the throwable it caught (a raw failure's synthesized
  instance pushed under its own throwable); no clause matched -> the entry goes back.
- What reads or writes the slot: `JvmErrorCondCompiler`, `JvmSignalCondCompiler`,
  `JvmHandlerCaseCompiler` (landing, no-match restore, the `_hbGuard` pad),
  `JvmAsyncRuntimeBuilder` / `JvmThreadRuntimeBuilder` (the `{EMARKER, t, _condTl.get()}` error
  payload), restart mode's `%run-handlers` / `%handlers-ran%` marks, and a33's `_jsig` / `_jfail`
  (`JvmJavaDirectSites.finishHelpers`), which take whatever the slot holds when a callback's
  throwable leaves it. Keyed, `_jsig` would take only its own throwable's entry, and the one
  loss `.kb/java-interop.md` names ("What a callback raises") would go.
- A program without typed conditions must stay byte-identical (`ConditionChannel.used` gates the
  channel today).
- Pin: a `ci-spec.yaml` case with the program above, run on all four backends; the `Stream.close`
  and `FutureTask` rows of `testsupport/JavaImplementationPrograms.CALLBACK_SIGNALS` stay green.
