# f35. The JVM's `gensym` counter races across threads

Difficulty: Low

`JvmGensymCompiler` emits `getstatic _gensymCtr; iconst_1; iadd; dup; putstatic` inline: two
threads (a served handler runs one per request, `.kb/concurrent-served-requests.md`) can read
the same value and answer two `gensym`s of one name. The interpreter's counter is an
`AtomicLong` (`Environment`); wasm is single-threaded. On the JVM a symbol is its name, so the
two are the same symbol.

The Clojure `reify` no longer calls `gensym` per evaluation (its rows are its site's), so no
shipped runtime depends on it under concurrency; a user `gensym` in a handler does.

Found while reading the code, not reproduced.

## Plan

1. Reproduce: a JVM program calling `gensym` from many threads at once (a served handler,
   or `bt2` threads) collects a duplicate name.
2. Make the increment atomic: a static helper emitted `ACC_SYNCHRONIZED` (as the lazy inits
   are) or an `AtomicInteger` field. Pin with a JVM test; the other backends need no change.
