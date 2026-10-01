# State

An atom is a tagged cell every verb reads and writes; each verb answers the new value and works as a function value, so `(map deref atoms)` runs. Volatile is the same cell without the compare-and-set.

| Name | Example | Result |
|---|---|---|
| `atom` | `(do (atom 1) nil)` | `nil` |
| `deref` | `(deref (atom 1))` | `1` |
| `swap!` | `(swap! (atom 1) + 10 20)` | `31` |
| `reset!` | `(reset! (atom 1) 2)` | `2` |
| `compare-and-set!` | `(compare-and-set! (atom 2) 2 3)` | `true` |
| `volatile!` | `(deref (volatile! 1))` | `1` |
| `vswap!` | `(vswap! (volatile! 1) + 2)` | `3` |
| `vreset!` | `(vreset! (volatile! 1) 2)` | `2` |
| `ref` | `(deref (ref 1))` | `1` |
| `dosync` | `(do (def r (ref 0)) (dosync (alter r inc)))` | `1` |
| `alter` | `(do (def r (ref 0)) (dosync (alter r + 2)))` | `2` |
| `commute` | `(do (def r (ref 1)) (dosync (commute r * 3)))` | `3` |
| `ref-set` | `(do (def r (ref 0)) (dosync (ref-set r 9)))` | `9` |
| `ensure` | `(do (def r (ref 0)) (dosync (ensure r) :ok))` | `:ok` |
| `agent` | `(deref (agent 1))` | `1` |
| `send` | `(do (def a (agent 0)) @(send a + 5))` | `5` |
| `send-off` | `(do (def a (agent 0)) @(send-off a + 5))` | `5` |
| `await` | `(do (def a (agent 0)) (await a))` | `nil` |
| `shutdown-agents` | `(shutdown-agents)` | `nil` |
