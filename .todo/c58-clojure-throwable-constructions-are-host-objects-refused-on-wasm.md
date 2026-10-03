# c58. Clojure: `(Exception. "boo")` and its kin are host objects: refused on wasm, message lost on `throw`

Difficulty: Medium

Measured 2026-10-03 (clj 1.12.6 as the oracle): `(try (throw (Exception. "boo")) (catch
Exception e (ex-message e)))` answers `"boo"`. The interpreter and the JVM answer
`"java.lang.Exception: boo"` (`C%E-THROW` renders the host object through `str` into an
ex-info condition, so the message is lost, b66); wasm and the component fail on `JAVA:NEW`
(undefined) -- every construction of a standard throwable (`Exception`, `RuntimeException`,
`IllegalStateException`, `IllegalArgumentException`, ... now all resolve by name) is refused
there, though a throwable needs no host object: only a message, a cause, `ex-message`.

Plan: lower a construction of a default-imported `Throwable` class with 0, 1 (message or
cause) or 2 arguments to the ex-info condition (message, nil data, optional cause), so
`throw`/`ex-message`/`ex-cause`/`ex-data` agree with the oracle on all four backends. It
makes `(.getMessage (Exception. "x"))`, which works on the host today, a method call on a
condition, so land it with (or after) the `.getMessage`-on-a-condition item. `ex-cause` is
unknown today (`unknown name: ex-cause`). Pin in clojure-spec (all four backends).
