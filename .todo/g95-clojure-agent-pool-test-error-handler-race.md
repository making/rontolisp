# ClojureAgentPoolTest.errorModesMatchTheOracle races its error handler's send

Difficulty: Low

Failed once under a parallel run (2026-10-29, interpreter leg), passed when the class ran
alone: the last line printed `[1 nil]` where `[101 nil]` is pinned. In `ERRORS`, the
`:continue` agent's handler sends `(+ 100)` when the first action fails. When the main
thread has already queued `inc` and `await`'s marker, the handler's send lands behind the
marker, so `await` returns before it runs. The program's answer depends on which thread
wins, so the pin is racy, not the pool.

Plan: check what the oracle guarantees here (its handler also sends straight to the queue),
then make the program order-independent (e.g. `await` a second time after the handler's
send is known to be queued) instead of loosening the expected line.
