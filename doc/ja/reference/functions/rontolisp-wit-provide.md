# rontolisp:wit-provide

`(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)`

[`rontolisp:wit-import`](rontolisp-wit-import.md) で取り込んだ WIT
インターフェースの**実装**を束縛します。インタプリタと JVM
バックエンドには呼び出す先の WASM ホストが存在しないため、インポートされた関数は
すべてそのインターフェースの *プロバイダ* 経由でディスパッチします。それを供給するのが
この関数です。インターフェース id を返し、そのインターフェースに既に束縛されている
プロバイダを**置き換えます**。

rontolisp はどのインターフェースについても**プロバイダを一切同梱していません**。
同梱しているのはプロバイダの仕組みであって、`wasi:keyvalue` が — あるいは他のどの
インターフェースが — 何であるかを rontolisp は知りません。WIT
インターフェースの実装は通常の Lisp コードであり、それを渡す手段がこの関数です。

プロバイダは、束縛された関数の **Lisp メンバー名** (文字列 — `"open"`、
`"bucket-get"`。生の WIT ラベルではなく、束縛が綴られる名前) に続けてその関数の引数を
受け取る、通常の Lisp 呼び出し可能オブジェクトです。以下は完全なプロバイダです —
ハッシュテーブル 1 つのストアで、ストアに必要なのはこれだけです。

```lisp
(defvar *rows* (make-hash-table :test #'equal))

(defun my-store (member &rest args)     ; ("bucket-set" bucket "visits" "41")
  (cond ((string= member "open") 1)     ; the bucket handle: any integer
        ((string= member "bucket-set")
         (setf (gethash (nth 1 args) *rows*) (nth 2 args))
         nil)
        ((string= member "bucket-get") (gethash (nth 1 args) *rows*))
        ((string= member "bucket-exists") (if (gethash (nth 1 args) *rows*) t nil))
        (t (error 'rontolisp:wit-error :payload (list :other member)))))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store) ; => "wasi:keyvalue/store@0.2.0"
```

これをソースに置けば、[`wit-import`](rontolisp-wit-import.md) の `wasi:keyvalue`
プログラムは `*rows*` を相手にします。呼び出し箇所
(`(kv:bucket-get b "visits")`) も `.wit` もそのままで、キーと値がどこにあるかを
知っているコードはプログラムの中に 1 行もありません。

## 引数

- インターフェース id (文字列)。`.wit` の完全修飾 id
  (`"wasi:keyvalue/store@0.2.0"`) です。`rontolisp:wit-import` は `:interface` に
  どの綴りを渡された場合でも、この正規化された id でディスパッチします
  (`"wasi:keyvalue/store"` や裸の `store` も同じインターフェースを指します)。
  そのため、このキー 1 つですべての綴りに対してプロバイダが束縛されます。
- プロバイダ: `(member &rest args)` の形の任意の Lisp 呼び出し可能オブジェクト —
  `#'name` 関数、`lambda`、その他 `funcall` が受け付けるものなら何でも構いません。

## rontolisp はプロバイダを同梱しません

コアが知っているのはプロバイダの**仕組み**です。`wasi:keyvalue`
が何であるかをコアは知らず、その実装も — 他のどのインターフェースの実装も —
同梱していません。これは意図的なものです: 新しいホストインターフェースに必要なのは
`.wit` ファイルであるべきで、コアのコードであるべきではありません。プロバイダが
まだ束縛されていない状態で束縛済みの関数を呼ぶと、何らかの既定値に到達するのではなく
[`rontolisp:wit-error`](#the-wit-error-condition) がシグナルされます。

```console
$ rontolisp counter.lisp
No provider is bound for the WIT interface wasi:keyvalue/store@0.2.0 -- bind one with rontolisp:wit-provide
```

プロバイダは*ただの関数*なので、偽物と本物は互換であり、プログラムはその違いを
見分けられません。
[`wit/keyvalue` の例](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)
は `wasi:keyvalue/store` に対して書かれたページビューカウンタで、その背後には 2
つのストアがあります: 可搬なインメモリ Lisp ストアと、JVM では
[`java:` interop](../../guides/java-interop.md) を通じて本物の
`java.util.LinkedHashMap` に支えられたストア (後から束縛されるので、こちらが前者を
置き換えます) です。ストアは変わりますが、`(kv:bucket-set b "/index" "3")`
という呼び出し箇所は変わらず、カウンタの出力もどちらでも同一です。実際のデプロイでは、
同じ 1 行でその map を Redis や JDBC 接続に差し替えます。代わりに Preview 1 WASM
へコンパイルすれば**ホスト**がプロバイダになり、これもプログラムの変更なしで済みます。

## `wit-error` コンディション

`rontolisp:wit-error` は WIT の `result<T, E>` の **error アーム**がシグナルする
コンディションです。ok アームは関数の戻り値、error アームはコンディション —
すべてのバックエンドでそうなります。プロバイダはマップされた `E`
をペイロードとしてこれをシグナルし (上の例では `wasi:keyvalue` の `error` variant、
すなわちタグ付きリスト)、呼び出し側は `(rontolisp:wit-error-payload e)`
でペイロードを読み戻します。

```console
;;; The caller of an imported function, in the same program as the wit-import.
(handler-case (kv:bucket-delete *bucket* "visits")
  (rontolisp:wit-error (e)
    (print (rontolisp:wit-error-payload e))))   ; (:other "read-only store")
```

これは通常のコンディションクラスです。`handler-case`、`ignore-errors`、
`unwind-protect` がすべて使え、`error` のサブクラスなので素の
`(handler-case ... (error (e) ...))` でも捕捉できます。

## バックエンド

| Backend | Effect |
| --- | --- |
| interpreter | binds the provider; imported functions dispatch to it |
| JVM (`-o Prog.class`) | the same |
| Preview 1 WASM (`-o prog.wasm`) | a top-level form is **dropped** — the WASM host is the provider |
| `--component`, `--no-gc` | these reject `rontolisp:wit-import` itself |

エラーにせず捨てるからこそ、**1 つのソースがすべてのバックエンドで動きます**。
インタプリタでプログラムを支えていた `rontolisp:wit-provide`
は、ホストが引き継いだ時点で単に無効化されるだけです。

## 制限事項

- インターフェース id は**文字列として**照合されます。したがって
  `"wasi:keyvalue/store"` で束縛したプロバイダは
  `"wasi:keyvalue/store@0.2.0"` へディスパッチする呼び出しには使われません。
  `wit-import` の `:interface` と同じ綴りにしてください。
- プロバイダはグローバルでスコープを持ちません。あるインターフェースについては最後の
  `rontolisp:wit-provide` が、プログラムの残り全体で有効になります。
- これらのバックエンドでは境界で何もマーシャリングも型チェックもされません —
  プロバイダには Lisp の値がそのまま渡され、その戻り値もそのまま返されます。
  [WIT 型の表](rontolisp-wit-import.md#supported-wit-types)
  が、プロバイダを書くときの契約です。
- 手書きの `rontolisp:wasm-import` には束縛すべきプロバイダはありません。
  `rontolisp:wit-provide` が相手にするのは `rontolisp:wit-import`
  が束縛したインターフェースです。
