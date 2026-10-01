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
