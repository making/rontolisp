# declare

`(declare declaration...)`

宣言はプログラムの計算結果を変えません。`declare` フォーム全体は nil に評価され、引数は評価も検証もされないため、標準のあらゆる宣言（`ignore`、`ignorable`、`type`、`optimize`、`inline`、`special` など）を本体のどこにでも書け、他の Common Lisp 処理系向けに書かれたソースコードを変更なしにロードできます。

3 つの宣言ファミリはコンパイルに影響します。`(declare (special ...))` は標準の CL と同じく変数を動的スコープにします。WASM バックエンドでは、配列型を指名する `type` 宣言 — `(simple-array (unsigned-byte 8) (*))`、`simple-vector`、`simple-string` など — により、その表現専用の要素アクセサを直接エミットするため、コンパイル済みモジュールは小さく速くなります。さらに JVM バックエンドでは、浮動小数点型を指名する `type` 宣言 — `double-float`、`single-float`、`float` — により、宣言されたローカル変数を生の `double` スロットに保持し、その算術を unboxed な機械命令にコンパイルするため、特に JIT のウォームアップ前が速くなります。*正しい*宣言が結果を変えることはありません。*偽の*宣言（Common Lisp では未定義動作）は WASM ではアクセス時にトラップし、JVM では宣言された読み書きの箇所で捕捉可能な型エラーを通知し、インタープリタでは引き続き無視されます。

```lisp
(let ((x 10))
  (declare (type integer x) (optimize (speed 3)))
  (* x 2)) ; => 20
```
