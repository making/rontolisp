# i98. An async body whose first `await` is already settled never hands off

Difficulty: Medium

`ThreadTest#aThreadsHandlersAreItsOwn` hung a full `./mvnw test` for over 4 hours (2026-11-07,
develop `a6a7b1b2a`, load around 17). jstack: the test thread parked in `AsyncRuntime.run`
(`handoff.await()`, line 72), nothing else of ours running.

The `ASYNC_BODY` shape (`OwnHandlerStacksPrograms`) is
`(funcall (rontolisp:async-lambda () (rontolisp:await (rontolisp:wait-for 1)) (worker)))`.
`AsyncRuntime.run` blocks the caller until the body's first suspension.
`releaseHandoffIfPending` runs only when `await` meets an UNSETTLED future. If the 1 ms timer
settles before the `await` (a loaded box), the body never suspends. It runs `worker`, which spins
until the caller sets `*phase*` to 2, while the caller is still blocked on the handoff: a deadlock.

Decide the semantics first. Either `await` always hands off, even on a settled future, as an
ECMAScript `await` always yields. Or the eager-start rule stays and the test shape stops relying on
timing. Then make the hang impossible rather than unlikely, and check the other users of the
`(await (wait-for N))` pattern in tests and ci-spec.
