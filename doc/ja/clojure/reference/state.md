# 状態

atom は全操作が読み書きするタグ付きセルです。各操作は新しい値を返し、関数値としても動くので (map deref atoms) が動きます。volatile は compare-and-set のない同じセルです。

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