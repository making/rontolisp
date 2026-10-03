# c20. Clojure 1.12 qualified methods: `Class/.method` and `Class/new`

Difficulty: Medium

Measured 2026-10-03, exec jar at `c258faf95` vs oracle `clj` 1.12.6. Interpreter and JVM answer alike.

Already work (static method as a value, param tags):

| form | oracle | ronto |
|---|---|---|
| `(map #(Integer/parseInt %) ["1" "2" "3"])` | `(1 2 3)` | same |
| `(map Integer/parseInt ["1" "2" "3"])` | `(1 2 3)` | same |
| `(map Math/abs [-1 2])` | `(1 2)` | same |
| `(map ^[long] Long/toString [1 2])` | `(1 2)` | same |

Fail:

| form | oracle | ronto |
|---|---|---|
| `(String/.toUpperCase "abc")` | `ABC` | `No matching method java.lang.String..toUpperCase with 1 argument(s)` |
| `(map String/.length ["ab" "abcd"])` | `(2 4)` | `error reading field .length: java.lang.NoSuchFieldException` |
| `((fn [f] (f "x" 0)) String/.charAt)` | `x` | same field error |
| `(String/new "q")` | `q` | `No matching method java.lang.String.new` |
| `(map String/new ["a"])` | `(a)` | field error `new` |

Support, on the interpreter and JVM:

- `Class/.method` in call position: the first argument is the target, as `(.method target args...)`.
- `Class/.method` as a value: a function taking the target first.
- `Class/new` in call position and as a value: the constructor, as `(Class. args...)`.
- `^[types]` param tags on the new forms, selecting an overload the way they already do for static methods.

Imported short names (`(:import (java.util ArrayList))` then `ArrayList/new`) must work too.
On WASM these forms are host calls and stay refused like `java:call`/`java:static`; check that the refusal names the form.
Pin the cases in the Clojure interop tests against the oracle, and update `doc/{en,ja}/clojure` interop pages in the same commit.
