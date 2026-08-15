# uiop/image

`uiop/image` は、プログラムがその「端」で行うことをまとめたものです。ステータス
コードを付けてプロセスを終了する、誰も処理しなかったコンディションを報告する、
イメージの復元時やダンプ時に走る処理を登録する。**30 個のエクスポートのうち 25
個が実装済み**で、未実装の 5 つはコマンドライン関連です（[このページの末尾](#what-is-missing-the-command-line)）。

どの名前も 2 通りの綴りで参照できます。`uiop:quit` と `uiop/image:quit` は同じ
関数です（[uiop パッケージ](../uiop.md#sub-packages)）。

ここでの 3 つの決定は rontolisp 独自のもので、いずれも欠落ではなく決定です。

- **`uiop:quit` は 4 つのバックエンドすべてでホストの exit そのもの**であり、その
  後には何も走りません。[終了する](#exiting)を参照してください。
- **バックトレースはフレームを持ちません**。Lisp レベルの呼び出しスタックを保持
  するバックエンドはないので、「このコンディションのバックトレース」の正直な表現
  はコンディションそのものだけです。上流の UIOP 自身も、バックトレース API のない
  処理系向けのフォールバックは同じ形をしています。
- **ダンプ・復元・生成できるイメージが存在しない**ため、その 3 つはシグナルします。
  ただし周囲のフックは本物です。フックへの登録は単なるリストへの push だからです。
  [イメージフック](#image-hooks)を参照してください。

## 終了する

| 関数 | 動作 |
|------|------|
| `uiop:quit` | 標準出力ストリームを finish-output したうえで、ステータスコード（既定は `0`）を付けてプロセスを終了する |
| `uiop:die` | `format` メッセージを `*error-output*` に報告し、与えられたコードで終了する |
| `uiop:shell-boolean-exit` | 引数が真なら `0`、`nil` なら `1` で終了する -- シェル流の真偽値 |

```console
$ cat quit.lisp
(print :before)
(uiop:quit 3)
(print :after)
$ rontolisp quit.lisp
:BEFORE
$ echo $?
3
```

同じプログラムをクラス、Preview 1 モジュール、コンポーネントにコンパイルしても、
同じ 1 行を印字して `3` で終了します。下にあるプリミティブは JVM では
`System.exit`、WASM Preview 1 では `proc_exit`、`--component` では
`wasi:cli/exit` の `exit-with-code` であり、インタプリタは CLI がプロセスコードに
変換する終了シグナルを送出します。

これが本物のホスト終了であることから 2 つの帰結があり、どちらもすべてのバック
エンドで成り立ちます。

- **その後には何も走りません**。`quit` を囲む `unwind-protect` のクリーンアップは
  実行されません。プロセスは呼び出しのその場で終わります。
- **コンディションではありません**。`handler-case`、`ignore-errors`、`catch` タグ
  のいずれからも見えないので、ライブラリのエラー処理の内側にある `quit` もきちんと
  終了します。

ステータスコードは 8 ビットにマスクされます。POSIX ホストがどのみちそう扱い、
`wasi:cli/exit` の `u8` が受け取るのもそれだからです。`(uiop:quit 300)` はどこでも
`44` で終了し、あるバックエンドだけ 300 になることはありません。

これに手を伸ばす典型的な理由は、テストランナーの終了コードです。

```console
$ cat run-tests.lisp
(uiop:quit (if (rove:run :my-app/tests) 0 1))
```

`uiop:quit` は終了できるホストプロセスを必要とするので、`--no-wasi` と `--no-gc`
では**コンパイル時に拒否されます**。これらが出力するのはホストが呼ぶエクスポート
を入口とするリアクタであり、リアクタは終了ではなくエクスポートからの復帰をします。

## 致命的コンディション

*致命的コンディション*とは `serious-condition` のことです。型は `deftype` の別名
なので、`typep` も `handler-bind` の節も同じように一致します。

| 名前 | 動作 |
|------|------|
| `uiop:fatal-condition` | 型そのもの: `serious-condition` |
| `uiop:fatal-condition-p` | `(typep c 'uiop:fatal-condition)` |
| `uiop:handle-fatal-condition` | コンディションを `*error-output*` に報告し、ステータス `99` で `uiop:die` する |
| `uiop:call-with-fatal-condition-handler` | そのハンドラを束縛してサンクを呼ぶ |
| `uiop:with-fatal-condition-handler` | 上のマクロ版: `(uiop:with-fatal-condition-handler () body...)` |
| `uiop:*lisp-interaction*` | `nil` |

```lisp
(list (uiop:fatal-condition-p (make-condition 'error))
      (uiop:fatal-condition-p (make-condition 'warning))
      (uiop:fatal-condition-p 42))   ; => (T NIL NIL)
```

`uiop:*lisp-interaction*` は、上流の既定が `t` であるのに対しここでは `nil` です。
この変数は「これは対話的な Lisp 環境か、それともバッチ処理か」を問うものであり、
rontolisp のどのバックエンドもプログラムを走らせて終わります。入るべきデバッガも、
コンパイル済み成果物の下にある REPL もありません。この値があるからこそ
`uiop:handle-fatal-condition` は、存在しない `invoke-debugger` を呼ぶのではなく
報告して終了します。

```console
$ cat fatal.lisp
(print :start)
(uiop:with-fatal-condition-handler ()
  (error "the sky is falling"))
(print :unreachable)
$ rontolisp fatal.lisp
:START
Fatal condition:
the sky is falling
the sky is falling
the sky is falling
$ echo $?
99
```

コンディションが 3 回現れるのは上流が 3 回印字するからです（報告として 1 回、
バックトレースと共に 1 回、`die` のメッセージとして 1 回）。ここでは真ん中の 1 回の
上にフレームがないというだけです。

## バックトレース

| 関数 | 印字するもの |
|------|--------------|
| `uiop:raw-print-backtrace` | `:condition` 引数があればそれ |
| `uiop:print-backtrace` | 同じものを `uiop:raw-print-backtrace` 経由で |
| `uiop:print-condition-backtrace` | 引数のコンディションを `:stream`（既定は `*error-output*`）へ |

```lisp
(let ((report (with-output-to-string (s)
                (uiop:print-condition-backtrace
                 (make-condition 'simple-error :format-control "boom")
                 :stream s))))
  (string-right-trim (list #\Newline) report))   ; => "boom"
```

`:count` は受け取って無視します。制限すべきフレームがないからです。これに手を
伸ばすライブラリは `lack-middleware-backtrace` で、そのエラー報告は
`uiop/image:print-condition-backtrace` から始まります。ここではその報告は 1 行です。

## イメージフック

| 名前 | 動作 |
|------|------|
| `uiop:register-image-restore-hook` | `uiop:*image-restore-hook*` に関数を push する。第 2 引数が `nil` でない限り即座に呼ぶ |
| `uiop:register-image-dump-hook` | `uiop:*image-dump-hook*` に関数を push する。第 2 引数が真のときだけ即座に呼ぶ |
| `uiop:call-image-restore-hook` | 登録順に restore フックを呼ぶ |
| `uiop:call-image-dump-hook` | dump フックを呼ぶ |
| `uiop:*image-restore-hook*` / `uiop:*image-dump-hook*` | 2 つのリスト |
| `uiop:*image-prelude*` / `uiop:*image-entry-point*` / `uiop:*image-postlude*` / `uiop:*image-dumped-p*` | `nil` |

イメージをダンプできるものがなくてもフックは**本物**です。ライブラリはロード中に
フックへ登録することがあり、それがエラーになってはいけないからです。

```lisp
(defvar *log* nil)
(uiop:register-image-restore-hook (lambda () (push :restored *log*)) nil)
(uiop:call-image-restore-hook)
*log*   ; => (:RESTORED)
```

`uiop:*image-dumped-p*` は `nil` であり、`nil` のままです。ダンプするものがない
ので、誰も設定しません。

## イメージのダンプ

| 関数 | シグナルする内容 |
|------|------------------|
| `uiop:dump-image` | `uiop:not-implemented-error` -- ヒープを保存できるバックエンドはありません。代わりにプログラムをコンパイルしてください |
| `uiop:restore-image` | `uiop:not-implemented-error` -- プログラムはソースから開始されるもので、再開されるものではありません |
| `uiop:create-image` | `uiop:not-implemented-error` -- リンクすべき Lisp オブジェクトファイルがありません |

rontolisp には SBCL 的な意味でのイメージがありません。プログラムは読まれて実行
されるか、読まれて 1 つの成果物にコンパイルされるかのどちらかです。

```bash
rontolisp app.lisp -o App.class      # a JVM class
rontolisp app.lisp -o app.wasm       # a WASM module
```

`uiop:dump-image` が担っていたのはこの役割です。

## 未実装: コマンドライン

`uiop:argv0`、`uiop:command-line-arguments`、
`uiop:raw-command-line-arguments`、`uiop:setup-command-line-arguments` は**まだ
未実装**です。他の未実装 uiop 名と同様に `uiop:not-implemented-error` をシグナル
し、`uiop:*command-line-arguments*` は `nil` です。今日入力を必要とするプログラム
は、環境変数（[`uiop:getenv`](../functions/uiop-getenv.md)）か標準入力から読んで
ください。
