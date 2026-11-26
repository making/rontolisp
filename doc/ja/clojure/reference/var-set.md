# var-set

`(var-set x val)`

拒否される。スレッドに束縛されていない var は `set` で根を取らない。oracle の `IllegalStateException`（`Can't change/establish root binding of: 短名 with set`）と同じ。スレッド束縛はここでは実体を持たないため、常に拒否される。値としては 2 引数を取る。

```clojure
(println (try (var-set (intern 'user 'doc-eg-vs) 1) (catch IllegalStateException e (.getMessage e)))) ; Can't change/establish root binding of: doc-eg-vs with set
```
