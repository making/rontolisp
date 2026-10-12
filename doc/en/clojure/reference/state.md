# State

An atom is a tagged cell every verb reads and writes; each verb answers the new value and works as a function value, so `(map deref atoms)` runs. Volatile is the same cell without the compare-and-set.

| Name | Example | Result |
|---|---|---|
| `atom` | `(do (atom 1) nil)` | `nil` |
| `deref` | `(deref (atom 1))` | `1` |
| `swap!` | `(swap! (atom 1) + 10 20)` | `31` |
| `reset!` | `(reset! (atom 1) 2)` | `2` |
| `compare-and-set!` | `(compare-and-set! (atom 2) 2 3)` | `true` |
| `swap-vals!` | `(swap-vals! (atom 1) inc)` | `[1 2]` |
| `reset-vals!` | `(reset-vals! (atom 1) 5)` | `[1 5]` |
| `add-watch` | `(let [a (atom 0)] (add-watch a :k (fn [k r o n] (println o n))) (reset! a 1))` | `1` (prints `0 1`) |
| `remove-watch` | `(let [a (atom 0)] (= a (remove-watch a :k)))` | `true` |
| `set-validator!` | `(set-validator! (atom 1) pos?)` | `nil` |
| `get-validator` | `(get-validator (atom 1))` | `nil` |
| `volatile!` | `(deref (volatile! 1))` | `1` |
| `vswap!` | `(vswap! (volatile! 1) + 2)` | `3` |
| `vreset!` | `(vreset! (volatile! 1) 2)` | `2` |
| `ref` | `(deref (ref 1))` | `1` |
| `dosync` | `(do (def r (ref 0)) (dosync (alter r inc)))` | `1` |
| `alter` | `(do (def r (ref 0)) (dosync (alter r + 2)))` | `2` |
| `commute` | `(do (def r (ref 1)) (dosync (commute r * 3)))` | `3` |
| `ref-set` | `(do (def r (ref 0)) (dosync (ref-set r 9)))` | `9` |
| `ensure` | `(do (def r (ref 7)) (dosync (ensure r)))` | `7` |
| `agent` | `(deref (agent 1))` | `1` |
| `send` | `(do (def a (agent 0)) @(send a + 5))` | `5` |
| `send-off` | `(do (def a (agent 0)) @(send-off a + 5))` | `5` |
| `await` | `(do (def a (agent 0)) (await a))` | `nil` |
| `await-for` | `(do (def a (agent 0)) (await-for 100 a))` | `true` |
| `agent-error` | `(agent-error (agent 0))` | `nil` |
| `restart-agent` | `(do (def a (agent 0 :validator pos?)) (send a dec) (restart-agent a 5))` | `5` |
| `set-error-handler!` | `(set-error-handler! (agent 0) println)` | `nil` |
| `set-error-mode!` | `(set-error-mode! (agent 0) :continue)` | `nil` |
| `error-handler` | `(error-handler (agent 0))` | `nil` |
| `error-mode` | `(error-mode (agent 0))` | `:fail` |
| `shutdown-agents` | `(shutdown-agents)` | `nil` |
| `delay` | `(deref (delay (+ 1 2)))` | `3` |
| `force` | `(force (delay 4))` | `4` |
