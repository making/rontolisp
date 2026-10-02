# b66. `throw` of a host `Throwable` loses its class and message

Difficulty: Medium

Measured 2026-10-02 (interpreter and JVM; wasm has no host objects):
`(throw (Exception. "boom"))` signals `(error (%clojure-str-of value))`, whose message is
`#<java java.lang.Exception>`. So `(try ... (catch Exception e (.getMessage e)))` cannot
read the message (`e` is the CL condition), `clojure.test`'s
`(is (thrown-with-msg? Exception #"bo" (throw (Exception. "boom"))))` fails, and an error
report prints `#<java java.lang.Exception>` where the oracle prints
`java.lang.Exception: boom`.

The corpus throws host exceptions in `macros.clj` (`(throw (Exception.))`) and its test
files assert on them with `thrown?`.

## Plan

- Make `C%E-THROW` of a host `Throwable` signal a condition carrying the object (its
  message from `getMessage`, its class name), on the interpreter and the JVM, behind the
  `java:` surface the interop lowering already uses.
- `ex-message` and the `clojure.test` describe path read it; a `catch` binding of the
  host object (`(.getMessage e)`) is the open design point.
- Pin in `ClojureInteropTest` (interpreter and JVM).
