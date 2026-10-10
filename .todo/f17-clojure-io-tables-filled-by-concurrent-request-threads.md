# f17. clojure.java.io's global tables are filled by concurrent request threads

Difficulty: Medium

`%clojure-io-url-found` (`clojure.lisp`) keeps a literal resource's contents in the global
`%clojure-io-resources`, an `equal` table it creates on first use and fills where the literal
`clojure.java.io/resource` call first runs; every read of a URL looks the table up
(`%clojure-io-kept`). A Ring handler runs one thread per request on the interpreter and the
JVM (`.kb/concurrent-served-requests.md`), and a Lisp hash table is a plain `LinkedHashMap`:
two first requests can each create the table (one entry lost) or put into it at once. A lost
entry sends the read to the file system for a `file:` URL, and for a `jar:` URL to the jar
reader or, in a program without it, to the `is not built in` refusal (a 500), until the site
runs again. `%clojure-io-register`'s `%clojure-io-streams` (a reader decoding a byte stream,
a writer encoding into one) has the same shape. Found while reading the code, not reproduced;
the jar reader's own cache is already under a mutex (`%clojure-io-jar-guard`).

## Plan

1. Reproduce: a Ring handler answering `(io/input-stream (io/resource "a.txt"))` and a
   second literal resource, a burst of concurrent first requests on the JVM.
2. Fill `%clojure-io-resources` before any request runs: the lowering knows every literal
   resource of a whole program, so one statement at the program's start can keep them all and
   a call site answer the URL alone (a session's input keeps its own at its start). A mutex
   around every read and write instead puts the mutex runtime into every program reading a URL
   on the JVM.
3. `%clojure-io-streams` is written per stream, so it needs the mutex or a thread-safe table.
