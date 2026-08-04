# 非同期プログラミング（async / await / future）

`rontolisp` パッケージは、JavaScript のプロミスや `async`/`await` をモデルに
した小さな非同期の表面を Lisp で提供します。いずれも Common Lisp の一部では
ないため、各オペレータは `rontolisp:` 修飾子で参照します
([パッケージ](../reference/packages.md)を参照)。このモデルの単位は **future**
です: まだ完了していないかもしれない計算を表す値のことです。
[`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md)
を呼び出すと future が返り、
[`rontolisp:await`](../reference/special-forms/rontolisp-await.md) がそれを解決
します。そしてその上にいくつかのコンビネータが乗ります。

| オペレータ | 用途 |
|----------|---------|
| [`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md) | 非同期関数を定義する（future を返す） |
| [`rontolisp:async-lambda`](../reference/special-forms/rontolisp-async-lambda.md) | 無名版 |
| [`rontolisp:async`](../reference/special-forms/rontolisp-async.md) | `(async (defun ...))` / `(async (lambda ...))` — 上記2つの JavaScript 風の綴り |
| [`rontolisp:await`](../reference/special-forms/rontolisp-await.md) | future が確定するまでサスペンドして値を返す |
| [`rontolisp:futurep`](../reference/functions/rontolisp-futurep.md) | 値が future なら `t` |
| [`rontolisp:wait-for`](../reference/functions/rontolisp-wait-for.md) | N ミリ秒後に `nil` に確定する future（`cl:sleep` の非同期版） |
| [`rontolisp:then`](../reference/functions/rontolisp-then.md) / [`then*`](../reference/functions/rontolisp-then-star.md) | future に変換を *値として* 付与する |
| [`rontolisp:catch`](../reference/functions/rontolisp-catch.md) | future にエラー時のフォールバックを値として付与する |
| [`rontolisp:finally`](../reference/functions/rontolisp-finally.md) | 成功・エラー両方のチャネルでクリーンアップのサンクを実行する |
| [`rontolisp:make-stream`](../reference/functions/rontolisp-make-stream.md) / [`stream-read`](../reference/functions/rontolisp-stream-read.md) / [`stream-write`](../reference/functions/rontolisp-stream-write.md) / [`stream-close`](../reference/functions/rontolisp-stream-close.md) / [`read-all`](../reference/functions/rontolisp-read-all.md) | 非同期のバイト/文字列ストリーム |

> **バックエンドのサポート。** この表面全体はインタプリタ、JVM バックエンド、
> WASM `--component` バックエンドで動作しますが、その下の機構は異なります。
> **インタプリタと JVM** では非同期本体は仮想スレッド上で走り — 最初の
> サスペンド以降は呼び出し元と *真に並列に* 走ります。**`--component`** では
> 本体は WASI 0.3 コンポーネントモデルの非同期 ABI 上の、協調的で
> シングルスレッドの状態機械へコンパイルされます
> ([後述](#内部の仕組み-wasi-preview-3-の-future-と-stream))。そうした
> コンポーネントは `wasmtime -W exceptions=y` を付けて実行する必要が
> あります。**Preview 1** WASM には非同期のホスト I/O がないため、非同期本体は
> 即座に最後まで走り (観測上は一貫した縮退的な同期モード)、`wait-for` と
> ゲスト側のストリーム操作はそこではコンパイルエラーになります。
> **`--no-gc`** は非同期の表面全体をコンパイル時に拒否します。

## Future と即時開始

`rontolisp:async-defun` は、*呼び出す* と本体を即座に開始し、値ではなく future
を返す関数を定義します。本体は最初の未確定 future の `await` まで (あるいは
完了まで) 走り — 「即時開始」— それから呼び出し元が再開します:

```lisp
(rontolisp:async-defun add-later (a b)
  (+ a b))
(rontolisp:await (add-later 20 22))   ; => 42
```

呼び出しそのものは不透明な future です — `rontolisp:futurep` がそれを認識し、
`#<FUTURE>` と印字されます:

```lisp
(rontolisp:futurep (add-later 1 2))   ; => t
```

future は本体の最後のフォームの値で確定するか、本体がシグナルしたエラーで
確定します (future を await したときに再シグナルされます —
[エラー](#await-境界をまたぐエラー)を参照)。無名版は
[`rontolisp:async-lambda`](../reference/special-forms/rontolisp-async-lambda.md)
で、`(rontolisp:async (defun ...))` / `(rontolisp:async (lambda ...))` はその2つの
等価な JavaScript 風の綴りです。

## await する

`rontolisp:await` は、future が確定するまで現在の非同期関数をサスペンドし、
確定した値を返します。これは *汎用* です: 確定済みの future はサスペンドせず、
ネストした future は平坦化され、future でない値はそのまま通ります — なので
`await` は future かもしれない値に一様に適用できます。

```lisp
(rontolisp:await 42)   ; => 42
```

`await` の配置は **字句的** です: `async-defun`/`async-lambda` の本体の中、
またはトップレベル (暗黙に非同期) でのみ合法です。素の `defun`/`lambda` の中は
— たとえ非同期本体にネストしていても — 定義時にエラーです:

```console
> (defun bad () (rontolisp:await 1))
rontolisp:await is only allowed inside rontolisp:async-defun/async-lambda or at top level
```

### await 境界をまたぐエラー

非同期本体がシグナルしたエラーは呼び出し時には脱出しません。future を確定させ、
`await` のところで条件を再シグナルします。`await` を囲む `handler-case` で
捕捉してください — 条件型のディスパッチは境界をまたいで機能します:

```lisp
(rontolisp:async-defun failing () (error "boom"))
(handler-case (rontolisp:await (failing))
  (error (e) (declare (ignore e)) "caught"))   ; => "caught"
```

## 処理をオーバーラップさせる

呼び出しが future を返す時点で処理は既に走っているので、複数の非同期処理は
オーバーラップします — 全部開始してからそれぞれを (どの順番でも) await します。
最も分かりやすい例が `rontolisp:wait-for` で、遅延後に確定する future を返します:
これは `cl:sleep` (プログラム全体を *ブロック* し、単位は秒) の非同期版です。
タイマーは並行して走るので、同時に開始した2つは開始順ではなく遅延順に確定し、
両方を await しても長い方の遅延ぶんほどで済み、合計にはなりません:

```lisp
(rontolisp:async-defun delayed (ms tag)
  (rontolisp:await (rontolisp:wait-for ms))
  tag)
(let ((slow (delayed 200 "slow"))
      (fast (delayed 20 "fast")))              ; 両方のタイマーが走り始める
  (list (rontolisp:await fast) (rontolisp:await slow)))   ; => ("fast" "slow")
```

複数の [`rontolisp:fetch`](http-fetch.md) リクエストが並行して走るのも、同じ
オーバーラップです: 全部開始してからレスポンスを await します。

## future を値として合成する（then / then* / catch / finally）

`await` は future が非同期本体の中に直接あるときに適した道具です。しかし future
は境界をまたげる第一級の値でもあり — 返したり、格納したり、引き回したり
できます — その先の呼び出し元は、呼び出し先が `async-defun` だからといって自身が
`async-defun` である必要はありません。コンビネータ四重奏は future を *値として*
変換し、それぞれ新しい future を返します:

- [`rontolisp:then`](../reference/functions/rontolisp-then.md) は成功時の変換を
  付与します。入力が成功で確定するとその値で関数を呼び、結果に確定します。
  上流でエラーが起きるとコールバックはスキップされ、条件がそのまま伝播します。
  関数自身が future を返した場合は `await` が平坦化します
  (`future<future<T>>` にはなりません):

```lisp
(rontolisp:async-defun some-future-producer () 21)
(defun caller ()                                     ; 非同期ではない素の defun
  (rontolisp:then (some-future-producer) (lambda (v) (* 2 v))))
(rontolisp:await (caller))   ; => 42
```

- [`rontolisp:then*`](../reference/functions/rontolisp-then-star.md) は可変長の
  チェーン糖衣です — 手書きのチェーンが必要とするネストなしに、値を複数の
  ステージに通します:

```lisp
(rontolisp:async-defun produce () 40)
(rontolisp:await (rontolisp:then* (produce) #'1+ #'1+))   ; => 42
```

- [`rontolisp:catch`](../reference/functions/rontolisp-catch.md) はエラー時の
  フォールバック (JavaScript の `.catch`) を付与します。成功値はそのまま通ります:

```lisp
(rontolisp:async-defun boom () (error "nope"))
(rontolisp:await
  (rontolisp:catch (boom) (lambda (c) (declare (ignore c)) :fallback)))   ; => :fallback
```

- [`rontolisp:finally`](../reference/functions/rontolisp-finally.md) は引数なしの
  クリーンアップのサンクを、成功・エラー *両方* のチャネルで実行します。元の結果は
  そのまま持ち越されます (`unwind-protect` と同様):

```lisp
(defvar *cleanup-log* nil)
(rontolisp:async-defun make-value () 5)
(let ((v (rontolisp:await
           (rontolisp:finally (make-value)
                              (lambda () (push :done *cleanup-log*))))))
  (list v (reverse *cleanup-log*)))   ; => (5 (:done))
```

四つのいずれも、第1引数が future でない場合は `type-error` です — JavaScript 風の
解決済みプロミスへの自動変換はありません。また `rontolisp:catch` は Common Lisp の
[`catch`](../reference/special-forms/catch.md)/[`throw`](../reference/special-forms/throw.md)
(タグベースの非局所脱出の特殊形式) では *ありません*: 別の
パッケージにあり、修飾名は決して衝突しません (命名の詳細は
[catch のリファレンスページ](../reference/functions/rontolisp-catch.md)を参照)。

## 非同期ストリーム

future が一度きり確定するのに対し、**ストリーム** は時間をかけてチャンクの列を
届けます。ゲストが作るストリームは読み書き両端を1つの値が所有します: 生産側は
[`rontolisp:stream-write`](../reference/functions/rontolisp-stream-write.md) で
追記し、[`rontolisp:stream-close`](../reference/functions/rontolisp-stream-close.md)
で終えます。消費側は
[`rontolisp:stream-read`](../reference/functions/rontolisp-stream-read.md) で
チャンクを取り出す (各読み取りは future を返す) か、
[`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md) で文字列
チャンクを一度の await で読み尽くします:

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:stream-write s "hello ")
  (rontolisp:stream-write s "world")
  (rontolisp:stream-close s)
  (rontolisp:await (rontolisp:read-all s)))   ; => "hello world"
```

`stream-read` は次のチャンクに確定する future を返すか、ストリームが閉じて
読み尽くされると `nil` に確定します — チャンクが `nil` になることはないので、
`nil` の結果は常にストリーム終端を意味します。開いていて空のストリームへの
読み取りは、書き込みが来るまでペンディングのままです。その保留された読み取りが、
await 中の非同期関数がサスペンドする対象です。

ゲストが作るストリーム (`make-stream` / `stream-write`) はインタプリタと JVM
バックエンドに存在します。`--component` ではストリーム *操作* も動作しますが、
ストリーム自体はホストから届きます: [`rontolisp:fetch`](http-fetch.md) の
レスポンスの `:body` と [`rontolisp:http-handler`](http-handler.md) の
リクエストの `:raw-body` (デフォルトの `:stream` モード) は、どのバックエンド
でも非同期ストリームです。

## 内部の仕組み: WASI Preview 3 の future と stream

`--component` バックエンドは、非同期モデルがホストスレッドではなく
プラットフォームのプリミティブへ対応づけられる唯一の場所です。WASI 0.3
(Preview 3) コンポーネントはコンポーネントモデルの **非同期正準 ABI** の上に
構築され、その2つの組み込みパラメトリック型が `future<T>` (一度きりの非同期
結果) と `stream<T>` (チャンクの列) です。rontolisp の future と非同期ストリームは
これらへ直接ローワリングされます:

- `async-defun` / `async-lambda` の本体 (および `await` を含むトップレベル) は、
  コンポーネントモデルの第一級 future 上の **エントリ + 再開の状態機械** へ
  コンパイルされます。既に確定した値の `await` はそのまま続行し、保留中のホスト
  操作の `await` は本当に **タスクをサスペンド** させ、待っていたイベントが到着
  するとコンポーネントのイベントループがそれを再開します。タスクは **協調的で
  シングルスレッド** です — 1つのコンポーネントインスタンスの2つの進行中の
  操作はインターリーブしますが、決して互いをプリエンプトしません。これは
  インタプリタ/JVM の仮想スレッド (本体が最初のサスペンド以降 *真に* 並列に走り、
  共有グローバル状態の競合はプログラム自身の責任) との意図的な相違です。
- `rontolisp:wait-for` はホストタイマー、`wasi:clocks/monotonic-clock@0.3.0` の
  `wait-for` へローワリングされ、イベントループが確定させる保留 future として
  返されます — なのでコンポーネントでもタイマーは実際にオーバーラップします。
- fetch レスポンスの `:body` / serve されるリクエストの `:raw-body` は、
  rontolisp のストリームとしてラップされた
  コンポーネントモデルの `stream<u8>` です。ホストがまだ処理中のチャンクの
  `stream-read` は保留 future なので、遅いボディの読み取りは自分のタスクだけを
  パークさせ、その間ほかのタスクのタイマーや fetch は走り続けます。

非同期 ABI はコンポーネントモデルの例外機構を使うため、非同期コンポーネントは
`-W gc=y` に加えて `wasmtime -W exceptions=y` を付けて実行する必要があります。
これらはすべて wasmtime 46+ でデフォルト有効なコンポーネントモデルの非同期
サポートの上に乗っており — 実験的なフィーチャーフラグはもう残っていません。
コンポーネントランタイム全体については
[WASI 0.3 コンポーネントガイド](wasm-component.md)を参照してください。

**Preview 1** WASM にはこれが一切ありません — Preview 1 コアモジュールには
非同期のホスト I/O がありません — なので非同期本体は呼び出された瞬間に単に最後
まで走り、その future は最初から確定済みで生まれます。観測される挙動は、future を
生んだ呼び出しの隣に `await` がある限り (よくある形) 他のバックエンドと一致し、
違うのはエラーが `await` ではなく *呼び出し* でシグナルされる点と、`wait-for` /
ゲストのストリーム操作がコンパイル時に拒否される点だけです。**`--no-gc`** は
非同期の表面全体を名指しで拒否します。

## 非同期が現れる場所

非同期の表面は意図的に小さく保たれています。ほとんどのプログラムは、その上に
構築された I/O 機能のいずれかを通してそれに出会います:

- [HTTP リクエスト（fetch）](http-fetch.md) — `fetch` は future を返す。
  レスポンスボディは `read-all` で読み尽くす。
- [HTTP を提供する（http-handler）](http-handler.md) — await するハンドラ
  (たとえば fetch するもの) は自身が `async-defun` でなければならない。
- [TCP ソケット](tcp-sockets.md) — コンポーネントの中では、保留中の
  `tcp-accept` やソケット読み取りは自分のタスクだけをサスペンドさせる。
