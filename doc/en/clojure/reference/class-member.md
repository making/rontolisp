# Class/member

`(Class/member args...)` `(Class/FIELD)` `(Class/.method target args...)` `(Class/new args...)`
and a bare `Class/member` value

Calls the static method. With no arguments -- `(System/currentTimeMillis)` or
`(. System currentTimeMillis)` -- it is the zero-argument static method when the
host class has one, else the static field read (so `(Integer/MAX_VALUE)` and
`(. Math PI)` read fields). A bare `Class/member` value reads the static field
when the host class has one, else answers a member-as-value function dispatching
per arity over the static call, so `(every? Character/isWhitespace s)` runs; a
variadic-only member is refused. A static call or member value whose overloads
all answer a boolean answers `true`/`false`. The class resolves dotted,
imported, or `java.lang`. A bare loadable class name (`String`) is the class
object, equal to `(Class/forName "java.lang.String")`, and `.getClass` on a
string answers it. A class object prints its name (`java.lang.String`), and `str`
answers its `toString` (`class java.lang.String`), like the oracle. Runs on the
interpreter and the JVM only -- the wasm backends reject `java:`.

`Class/.method` is the instance method: in call position the first argument is the
target, as `(.method target args...)`, and as a value it is a function taking the
target first, dispatching per arity of the class's public instance methods (a name with
none is refused when the program is read). `Class/new` is the constructor, as
`(Class. args...)`, in call position and as a value; `R/new` of a record or deftype is
its positional constructor. `^[types]` param tags before any of these name the overload:
the value then takes exactly that many arguments (plus the target). A tag is a class
name, a primitive, `ints`/`longs`/... or `objects` for a primitive or `Object` array,
`T/N` for an `N`-dimensional array, or `_` for any type; a tagged call with another
argument count is refused when the program is read.

```clojure
(ns doc-static (:import (java.awt.event KeyEvent)))
(println (Integer/parseInt "42")) ; 42
(println KeyEvent/VK_LEFT) ; 37
(println (every? Character/isWhitespace "   ")) ; true
(println (> (System/currentTimeMillis) 0)) ; true
(println (= String (.getClass "s"))) ; true
(println (.getName String)) ; java.lang.String
(println String (str String)) ; java.lang.String class java.lang.String
```

```clojure
(ns doc-qualified (:import (java.util ArrayList)))
(println (String/.toUpperCase "abc")) ; ABC
(println (map String/.length ["ab" "abcd"])) ; (2 4)
(println (String/new "q")) ; q
(let [a (ArrayList/new)] (.add a 1) (println (ArrayList/.size a))) ; 1
(println (map ^[double] Math/abs [-1 2])) ; (1.0 2.0)
(println (map ^[int] String/.charAt ["ab"] [1])) ; (b)
```
