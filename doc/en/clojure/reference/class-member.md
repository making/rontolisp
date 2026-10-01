# Class/member

`(Class/member args...)` `(Class/FIELD)` and a bare `Class/member` value

Calls the static method. With no arguments -- `(System/currentTimeMillis)` or
`(. System currentTimeMillis)` -- it is the zero-argument static method when the
host class has one, else the static field read (so `(Integer/MAX_VALUE)` and
`(. Math PI)` read fields). A bare `Class/member` value reads the static field
when the host class has one, else answers a member-as-value function dispatching
per arity over the static call, so `(every? Character/isWhitespace s)` runs; a
variadic-only member is refused. A static call or member value whose overloads
all answer a boolean answers `true`/`false`. The class resolves dotted,
imported, or `java.lang`. Runs on the interpreter and the JVM only -- the wasm
backends reject `java:`.

```clojure
(ns doc-static (:import (java.awt.event KeyEvent)))
(println (Integer/parseInt "42")) ; 42
(println KeyEvent/VK_LEFT) ; 37
(println (every? Character/isWhitespace "   ")) ; true
(println (> (System/currentTimeMillis) 0)) ; true
```
