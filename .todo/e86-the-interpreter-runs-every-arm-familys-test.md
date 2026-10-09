# e86. The interpreter runs every arm family's test

Difficulty: Medium

The compile path splices the Clojure library with the arms of every family the program
makes no value of stripped (`eval/ClojureLibrary.process`); the interpreter keeps every arm,
so each family adds a function call to the hot paths that test it whatever the program
holds. Measured 2026-10-08 when `clojure.java.io` added one test (`%clojure-io-p`) to
`%clojure-str-of`'s non-readable `cond`: a loop of 300,000 `(str i :k)` took 20.4 s against
19.3 s before on the interpreter (mean of five, loaded host, ~+5%); `=` and `pr-str` loops
stayed within noise. A number already passes about ten such tests in that `cond` before its
spelling.

## Plan

1. Confirm where the interpreter's library comes from and count the family tests a number,
   a string and a keyword pass in `str`, the printer and `=`.
2. Either give the interpreter the stripped variant of the program's families (a session
   grows its families as inputs make values), or order the arms so the common kinds answer
   ahead of every family test; measure both on the interpreter and keep the compile path's
   bytes.
