# c17. The Clojure REPL echo of the remaining definition forms

Difficulty: Low

Measured 2026-10-03, oracle `clj` 1.12.6 REPL vs the exec jar (`--source-language clojure`), after
the top-level `def`/`defn`/`defn-`/`defmacro`/`defmulti`/`defonce`/`defstruct` echo became the var:

| form | oracle | ronto |
|---|---|---|
| `(defprotocol P (pm [x]))` | `P` | `{}` |
| `(defrecord R [a])` | `user.R` | `map->R` |
| `(declare q)` | `#'user/q` | `nil` (`(declare a b)`: the last name's var) |
| `(do (def v 3))`, `(let [a 1] (def u a))` | `#'user/v`, `#'user/u` | `3`, `1` |

## Plan

- `defprotocol`/`defrecord`/`deftype`: append the class-name echo as the datum's last form in
  `ClojureLowering.echoingTopLevelsOf` (a session-only path; a file prints nothing).
- `declare`: append the last name's var the same way.
- A nested `def` answers the var in every context in the oracle (`(println (def x 1))` prints
  `#'user/x`); it is a value change on a file path too, so measure the size cost of building a var
  per `def` site on the wasm backends before deciding, and decide whether it is worth it.

## Pin

`RontoLispCliTest#theClojureReplEchoesTheVarADefinitionDefines` (extend the transcript).
