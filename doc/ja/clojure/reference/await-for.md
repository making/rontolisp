# await-for

`(await-for timeout-ms a...)`

上限つきの `await` です。それまでに agent へ送ったアクションを走らせるか、それを走らせているスレッドを最大 `timeout-ms` ミリ秒待ち、すべて走れば `true`、時間切れなら `false` を返します（失敗の後ろに並んだアクションは走りません）。`await` と同じく、アクションの中（`Can't await in agent action`）、トランザクションの中（`await-for in transaction`）、失敗状態の agent ではシグナルを上げます。

```clojure
(def a (agent 0))
(send a + 5)
(println (await-for 1000 a) @a)
```

```
true 5
```
