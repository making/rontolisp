# WASI 0.3 コンポーネント(`--component`)

`--component` を追加すると、Preview 1 コアモジュールの代わりに WASI 0.3(Preview 3)**コンポーネント**が出力されます。コンポーネントは `wasi:cli/stdout@0.3.0` を通じて印字します:

```bash
rontolisp hello.lisp --component -o hello.wasm
wasmtime run -W gc=y hello.wasm
```

```
3
```

WASI 0.3 ではすべてのバイト I/O が組み込みのコンポーネントモデル型 `stream<u8>` / `future<T>` と非同期正準 ABI を流れます。rontolisp は同じ Preview 1 コアモジュールを無変更のまま保ち — 依然として 8 つの `wasi_snapshot_preview1` 関数をインポートします — **アダプタ**コアモジュールがそれらを WASI 0.3(`wasi:cli`、`wasi:filesystem`、`wasi:clocks`、`wasi:random`)の上に `stream.new`/`stream.read`/`stream.write` と `future.read` を使って実装します。これらの組み込みは**非同期**(ノンブロッキング)版です: BLOCKED が報告されると、タスクは完了イベントが届くまでブロッキング待機の `waitable-set.wait` で待つため、アダプタは直線的なコードのままです。コンポーネントの `wasi:cli/run@0.3.0` エクスポート(`async func`)は非同期型付きのエクスポートとしてリフトされ、そこからこのブロッキング待機は合法です。これらはすべて wasmtime 46+ でデフォルト有効な基本のコンポーネントモデル非同期 ABI の上に成り立っています — ゲートされた機能フラグは残っておらず、必要なのは(wasm-GC コアのための)`-W gc=y` だけです。

wasmtime の起動方法が出力の種類を選ぶわけでは**ありません**。`wasmtime run` は wasmtime のデフォルトサブコマンドで、コアモジュールかコンポーネントかを自動検出するため、`wasmtime run -W gc` は Preview 1 の `hello.wasm` も同様に実行します。Preview 1 コアモジュールと WASI 0.3 コンポーネントのどちらが生成されるかを決めるのは、コンパイル時の `--component` フラグだけです。(実際上の違いはコンポーネント専用ランタイムで現れます。そこではコンポーネントは動きますが Preview 1 コアモジュールは動きません。)

## コンポーネント内で動くもの

コンポーネント内で動くもの、そして各機能が実行時に必要とするもの:

- `print`/標準出力、標準入力(`read`、0 引数の `read-line`、`wasi:cli/stdin@0.3.0` 経由)、ファイル I/O(`open`、`close`、`write-line`、ストリーム `read-line`、`load`、`with-open-file`)はすべて動作します。async ボディ内では、保留中の標準入力の `read-line`/`read-char` はソケット読み取りと同様に自分のタスクだけをサスペンドします — 入力を待っている間も、並行する `rontolisp:wait-for` タイマーは動き続けます。ファイルアクセスには `--dir` が必要です(パスは最初にプリオープンされたディレクトリに対して解決されます):

```bash
cat > fileio.lisp <<'EOF'
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out))
(with-open-file (in "greeting.txt")
  (print (read-line in)))
EOF
rontolisp fileio.lisp --component -o fileio.wasm
wasmtime run -W gc=y --dir . fileio.wasm
# "hello"
```

- `random` は `wasi:random@0.3.0` から本物のエントロピーを引きます(Preview 1 はホストの `random_get` を使います)。そのため `(random N)` は実行ごとに異なります。`get-universal-time` / `get-internal-real-time` / `get-internal-run-time` は `wasi:clocks@0.3.0`(`system-clock`/`monotonic-clock`)を読み、`getenv` は `wasi:cli/environment@0.3.0` を読みます。
- 送信 HTTP(`rontolisp:fetch` と `rontolisp:await` / `rontolisp:futurep` の future 操作)はコンポーネントモードで動作し、真の非同期性も含みます: `fetch` はリクエストを送って(処理中の `wasi:http` レスポンスハンドルをラップした)future を即座に返すため、`await` が各リクエストをサスペンドする前に複数のリクエストを重ねられます。future 操作自体はどのモードでもコンパイルできます。コンポーネント専用なのは `fetch` だけです。fetch は非同期の `wasi:http@0.3.0`(`wasi:http/types` + `wasi:http/client`)をインポートします — コンポーネントの他の部分と同じく一様に WASI 0.3 です。fetch コンポーネントは通常のフラグに加えて `-S http=y`(ホストに `wasi:http` を提供させるフラグ)で実行してください。fetch を使わないコンポーネントは `wasi:http` をインポートしないため、`-S http` は不要です。トランスポートの失敗(接続拒否、名前解決不能)はどのバックエンドでも `await` 時に `rontolisp:wit-error` をシグナルします。`nil` が返るのはリクエストを開始できなかった場合だけです。リクエスト/レスポンスの形については [HTTP fetch ガイド](http-fetch.md)を参照してください。
- TCP ソケット(`rontolisp:tcp-connect` / `tcp-listen` / `tcp-accept` / `tcp-local-port`)はコンポーネントモードで `wasi:sockets@0.3.0` の上で動作します(ネイティブに WASI 0.3 — 0.2 ハイブリッドではありません)。ソケットは双方向ストリームハンドルなので、`read-line` / `write-line` / `write-string` / `read-byte` / `write-byte` / `close` が直接使えます。ソケットコンポーネントは通常のフラグに加えて `-W exceptions=y -S tcp=y -S inherit-network=y` で実行してください(tcp コンポーネントは常に exception-handling モードでコンパイルされます)。`-S` フラグがなくてもコンポーネントは起動しますが、すべてのソケット操作が失敗して `nil` を返します。ホストは IPv4 リテラルでなければなりません(ホスト名解決はまだありません)。`rontolisp:fetch` と tcp 関数は 1 つのコンポーネントで組み合わせられ、tcp は `rontolisp:http-handler`(serve)コンポーネントの中でも使えます。async 本体では、保留中の `tcp-accept` やソケット読み取りはそのタスクだけをサスペンドします — 他のタスク(`rontolisp:wait-for` タイマーや別のリクエスト)は動き続けます。API 全体は [TCP ソケットガイド](tcp-sockets.md)を参照してください。
- それ以外の点では、コンパイルされた Lisp はサポートされる機能について Preview 1 出力と同一に振る舞います。受信 HTTP のサービング(`rontolisp:http-handler`)もコンポーネントにコンパイルされますが、別種のコンポーネント(`wasi:http/handler@0.3.0` をエクスポート)で、`wasmtime serve` のもとで動きます — [HTTP ハンドラーガイド](http-handler.md)を参照してください。

## コンポーネントモデル関数エクスポート(`wasm-export`)

`--component` のもとでは、[`rontolisp:wasm-export`](wasm-host-boundary.md#exporting-lisp-functions) は**型付きコンポーネントモデルエクスポート**になり、正準 ABI を通じて WAVE 構文(`wasmtime run --invoke 'name(args)'`、experimental 警告なし)で呼び出せます — しかも `wasi:cli/run` のコマンドエントリと共存するため、同じコンポーネントは引き続きコマンドとしても実行できます:

```lisp
(defun sumsquared (a b) (* (+ a b) (+ a b)))
(rontolisp:wasm-export 'sumsquared :params '(:int :int) :returns :int)
(print (sumsquared 10 10))
```

```bash
rontolisp sumsq.lisp --component -o sumsq.wasm
wasmtime run -W gc=y --invoke 'sumsquared(2, 3)' sumsq.wasm
# 25    (the export's return value, rendered by wasmtime)
wasmtime run -W gc=y sumsq.wasm
# 400    (the ordinary run entry executes the top-level program)
```

2 つのコマンドは異なるものを表示します: `--invoke` は名前付きエクスポート**だけ**を呼び出し、トップレベルのプログラム(`wasi:cli/run` エントリ)は実行されません — `25` は wasmtime がエクスポートの戻り値を WAVE 構文でレンダリングしたものであり、`print` の出力ではありません。素の `run` は代わりにトップレベルのプログラムを実行するため、`400` は `(print (sumsquared 10 10))` の出力です。

型付きシグネチャ(`:int` → `s32`、`:float` → `f64`、`:bool` → `bool`、`:string` → `string`、`:s-expr` → 印字された S 式テキストを運ぶ `string`、`:returns` 省略 → 結果なし)は任意のコンポーネントホストから見え、`:as` はコア側と同様にコンポーネントエクスポートの名前を変更します。

`:string` 境界は本物のコンポーネントモデル `string` として越えます — どちら側にも手動のポインタ処理はありません。ホストは引数のバイト列をリニアメモリへローワリングし、結果を正準 ABI を通じて読み出します。その後モジュールは呼び出しごとの確保を解放する(正準 *post-return* 関数がバンプアロケータをポップする)ため、常駐インスタンスは繰り返し呼び出しでもフラットに保たれます:

```lisp
;; greet.lisp
(defun greet (s) (concatenate 'string "Hello, " s))
(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
```

```bash
rontolisp greet.lisp --component -o greet.wasm
wasmtime run -W gc=y --invoke 'greet("世界")' greet.wasm
# "Hello, 世界"
```

デフォルトではエクスポートは**同期的に**リフトされます。それでも、その中の I/O は通常動作します: 非同期組み込みはホストが即座に受理する限り(標準出力はそうです)ブロックせずに完了し、BLOCKED を報告するホストだけがブロッキング待機を強制します。この待機は同期タスク内では "cannot block a synchronous task" でトラップします。**`:async t`** でエクスポートを非同期と宣言すると、代わりに非同期関数型に対してリフトされ — `run` エントリと同じ非同期型付きリフトです — この残余リスクがなくなります。`wasmtime --invoke` は非同期エクスポートもまったく同じ方法で呼び出します:

```lisp
;; status.lisp
(rontolisp:async-defun fetch-status (url)
  (print "fetching")
  (getf (rontolisp:await (rontolisp:fetch url)) :status))
(rontolisp:wasm-export 'fetch-status :params '(:string) :returns :int :async t)
```

```bash
rontolisp status.lisp --component -o status.wasm
wasmtime run -W gc=y -W exceptions=y -S http=y \
  --invoke 'fetch-status("https://httpbin.ik.am/status/204")' status.wasm
# "fetching"
# 204
```

コンポーネントの WIT レベルの契約では、`:async t` エクスポートは `async func` です(例えば jco は Promise を返す関数として型付けし、同期エクスポートは普通の関数のままです)。同期と非同期のエクスポートは 1 つのコンポーネント内で自由に混在でき、`:async` は `:string`/`:s-expr` を含むすべての境界型と組み合わせられ、`:async` エクスポートのないプログラムはバイト単位で同一の出力を生成します。

コンポーネントエクスポートの現在の制限:

- **同期**(デフォルト)エクスポートでも I/O は通常動作します(非同期組み込みはホストが即座に受理する限りブロックせずに完了します)。BLOCKED を報告するホストだけがブロッキング待機を "cannot block a synchronous task" でトラップさせます。エクスポートが印字・fetch・その他の I/O を行うときは `:async t` にオプトインしてこの残余リスクを除き、純粋計算のエクスポートは同期のままにしてください。
- `:async` が意味を持つのはここだけです: Preview 1 / `--no-wasi` のコアエクスポートは無視し(そこではホストが直接 I/O を提供します)、`--no-gc --component` は拒否します(コンパクトなリアクターコンポーネントには非同期アダプタがありません)。
- jco(1.25.2)は `:async t` エクスポートをトランスパイルして非同期として型付けしますが、まだ呼び出せません — 0.3 非同期 ABI のサポートが上流で未実装です(トランスパイルされた `run` を呼べないのと同系統のギャップです)。非同期エクスポートの検証済みパスは `wasmtime run --invoke` です。同期エクスポートはどちらでも動作します。
- エクスポート名は lower-kebab-case のコンポーネントモデル名(`sum-squared`)でなければなりません。その文法から外れる Lisp 名については、コンパイラが `:as` での改名を求めます。
- エクスポートの呼び出しはプログラムのトップレベルを先に実行しないため、`defvar`/`defparameter` のグローバルを読むエクスポートは未初期化の値を見ることになります(これは Preview 1 の `--invoke` の動作と一致します)。

純粋計算のエクスポートキットには、コンパクトな [`--no-gc --component`](wasm-nogc.md#compact-component-output---no-gc---component) が同じ型付きエクスポート(加えて `:long` → `s64`、ただし `:s-expr` なし)を、wasmtime のフラグを一切必要としない数百バイトのコンポーネントとして出力します。
