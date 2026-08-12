# rontolisp

Java で実装された最小限の Common Lisp サブセットです。3 つの実行モードをサポートします。

- **インタプリタ** -- REPL をサポートするツリーウォーク評価
- **JVM コンパイラ** -- Lisp を任意の JRE 上で実行できる `.class` バイトコードにコンパイル
- **WASM コンパイラ** -- wasm-GC を使って Lisp を `.wasm` にコンパイル。WASI Preview 1 のコアモジュール、または WASI 0.3（コンポーネントモデル）のコンポーネントを出力可能

ここで今すぐ試せます。このページの例は、[playground](../../playground.html) と同じ
WebAssembly ビルドを使ってブラウザ上で実行されます。

```lisp
(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
(print (fact 10))
```

上の **Run** を押してください（初回実行時に rontolisp ランタイムが読み込まれます）。

AI コーディングエージェントと作業する場合は、同じページ群から生成される
[エージェントスキル](../../skill/)（デプロイのたびに再生成）も利用できます。
