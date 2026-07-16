# rontolisp:async-defun

`(rontolisp:async-defun name (params...) body...)`

非同期関数を定義します。表面上は [`defun`](defun.md) と同じで、ラムダリストキーワード (`&optional`、`&rest`、`&key` など) をフルにサポートしますが、呼び出すと本体が直ちに開始され、値の代わりに *future* を返します: 本体は未確定の future への最初の [`rontolisp:await`](rontolisp-await.md) (または完了) まで実行され、そこで呼び出し側が再開します (「eager start」)。future は最後の本体フォームの値で確定するか、本体がシグナルしたエラーで確定します (await 時に再シグナルされます)。

```lisp
(rontolisp:async-defun add-later (a b)
  (+ a b))
(rontolisp:await (add-later 20 22))   ; => 42
```

呼び出し自体は不透明な future を返します ([`rontolisp:futurep`](../functions/rontolisp-futurep.md) が認識し、`#<FUTURE>` と印字されます):

```lisp
(add-later 1 2)   ; => #<FUTURE>
```

本体がシグナルしたエラーは呼び出し時点では外に出ず、future を確定させて `await` の時点で再シグナルされます — `handler-case` で捕捉する方法は [`rontolisp:await`](rontolisp-await.md) を参照してください。無名版は [`rontolisp:async-lambda`](rontolisp-async-lambda.md) です。

## バックエンドのサポート

- **インタプリタ / JVM**: 本体は仮想スレッド上で実行されます — 最初のサスペンド以降は呼び出し側と真に並行に動きます。
- **WASM `--component`**: 本体はステートマシンにコンパイルされます。未確定の future の `await` は本当にサスペンドし、await していたホスト操作 (例: `fetch` のレスポンス) が完了するとコンポーネントのイベントループが再開します。ひとつのコンポーネントインスタンスのタスクは協調的 (シングルスレッド) です。非同期コンポーネントの実行には `-W gc=y` に加えて `wasmtime -W exceptions=y` が必要です。
- **Preview 1 WASM**: 本体は直ちに完了まで実行されます (非同期のホスト I/O が存在しないため)。
- **`--no-gc`**: コンパイル時に拒否されます。
