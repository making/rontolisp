# rontolisp:wit-import

`(rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)`

プログラムが **WIT インターフェースを呼び出す**ことを宣言します。インターフェースが
宣言するすべての関数が、名前・ラムダリスト・型を `.wit` ファイルから取って通常の
Lisp 関数として束縛されます。[`rontolisp:wit-export`](rontolisp-wit-export.md)
の鏡像であり、それと同様に、新しい呼び出し経路ではなく **既存のフォームへローワリング
される**コンパイル時ディレクティブです。何にローワリングされるかはバックエンドごとに
異なり、それこそが要点です: **1 つの WIT、バックエンドごとに異なる実装、ソース変更は
ゼロ**。**インタプリタ**と **JVM** では各束縛が *プロバイダ* — 自分で
[`rontolisp:wit-provide`](rontolisp-wit-provide.md)
で束縛する、通常の Lisp 呼び出し可能オブジェクト — 経由でディスパッチする
`defun` になり、**Preview 1 WASM** では
[`rontolisp:wasm-import`](rontolisp-wasm-import.md) になります。そして
**`--component`** では、インターフェースがコンポーネントモデルの本物の**インポート**
になり、その関数群はモジュールへ `canon lower` されます — つまりプロバイダは*ホスト*
であり、そのインターフェースをエクスポートする相手となら誰とでも合成できます。詳細は
[WIT インターフェースのインポート](../../compiling/wasm.md#importing-a-wit-interface-wit-import)
を参照してください。

このディレクティブはディスク上の `.wit` ファイルを読むため、例は静的に示します。

```console
// wit/store.wit -- an excerpt of the real wasi:keyvalue/store@0.2.0
package wasi:keyvalue@0.2.0;

interface store {
  variant error {
    no-such-store,
    access-denied,
    other(string),
  }

  resource bucket {
    get: func(key: string) -> result<option<list<u8>>, error>;
    set: func(key: string, value: list<u8>) -> result<_, error>;
    delete: func(key: string) -> result<_, error>;
    exists: func(key: string) -> result<bool, error>;
  }

  open: func(identifier: string) -> result<bucket, error>;
}
```

```console
;;; counter.lisp -- the directive comes FIRST: it defines the kv package and the
;;; functions the rest of the file calls.
(rontolisp:wit-import "wit/store.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)

;;; What those functions CALL is a provider -- ordinary Lisp code, and yours to
;;; write. rontolisp ships none: it knows the mechanism, not the interface.
(defvar *rows* (make-hash-table :test #'equal))

(defun my-store (member &rest args)
  (cond ((string= member "open") 1)              ; the bucket handle: any integer
        ((string= member "bucket-set")
         (setf (gethash (nth 1 args) *rows*) (nth 2 args))
         nil)
        ((string= member "bucket-get") (gethash (nth 1 args) *rows*))
        ((string= member "bucket-exists") (if (gethash (nth 1 args) *rows*) t nil))
        (t (error 'rontolisp:wit-error :payload (list :other member)))))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)

(defvar *bucket* (kv:open "counts"))

(kv:bucket-set *bucket* "visits" "41")
(print (kv:bucket-get *bucket* "visits"))
(print (kv:bucket-exists *bucket* "missing"))
```

```bash
rontolisp counter.lisp                     # the provider bound above
# "41"
# nil
rontolisp counter.lisp -o Counter.class && java Counter
# "41"
# nil
```

手で束縛するものは何もありません。`kv:bucket-get` も、その `(self key)`
というラムダリストも WIT から来ています。それらの関数が*何を呼ぶか*だけは `.wit`
には書けません。それが**プロバイダ**であり、rontolisp
はどのインターフェースについても**プロバイダを一切同梱していません**。同梱しているのは
プロバイダの*仕組み*であって、`wasi:keyvalue` が何であるかを rontolisp
は知りません。したがって WIT インターフェースの実装は、上の `my-store`
のような通常の Lisp コードです。そのハッシュテーブルを本物のストアに差し替えるのは
1 行で済み、プログラムはそれに気づきもしません。
[`examples/wit/keyvalue`](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)
がまさにそれです: 1 つのページビューカウンタに対して、インタプリタでは可搬な
インメモリ Lisp ストアが、JVM では `java.util.LinkedHashMap`
のストアが後ろに立ち、そして `--component` でコンパイルすれば
**wasmtime 自身の `wasi:keyvalue` 実装** — このプログラムのことなど何も知らない
ホスト — が後ろに立ちます。出力は 3 通りとも同一です。

## 引数

- WIT ファイルのパス (文字列)。相対パスは、それを書いたソースファイルのディレクトリ
  を基準に解決されます ([`load`](load.md) と同じ)。
- `:interface` — 束縛するインターフェース (必須)。完全修飾 id
  (`"wasi:keyvalue/store@0.2.0"`)、バージョンなしの id
  (`"wasi:keyvalue/store"`)、またはファイル中で一度しか定義されていなければ素の
  インターフェース名 (`store`) で書けます。文字列または素のシンボルです。
- `:package` — 束縛が入る Lisp パッケージ (`kv:open`、`kv:bucket-get`)。それらを
  エクスポートする `defpackage` が合成されるため、`defpackage` を手で書く必要は
  ありません。省略すると、名前は現在のパッケージに入ります。
- `:from` — Preview 1 WASM のインポートモジュール名。既定値はインターフェースの素の
  名前 (`store`) です。他のバックエンドでは無視されます (コンポーネントは
  インターフェースを完全修飾 id でインポートし、これは改名できません)。
- `:field-style` — WIT のラベルを Preview 1 のインポート**フィールド**としてどう綴るか:
  `:camel` (既定 — `create-shader` は `createShader` になります。JavaScript
  の慣習であり、`jco` が生成するものでもあります) または `:kebab` (ラベルをそのまま)。
  他のバックエンドでは無視されます。

## 何が束縛されるか

| WIT | Lisp function | Call |
| --- | --- | --- |
| `open: func(identifier: string) -> ...` | `kv:open` | `(kv:open "counts")` |
| `resource bucket` method `get: func(key: string) -> ...` | `kv:bucket-get` | `(kv:bucket-get b "visits")` |
| `resource bucket` `constructor(...)` | `kv:bucket-new` | `(kv:bucket-new ...)` |
| `resource bucket` `static func from-name` | `kv:bucket-from-name` | `(kv:bucket-from-name "x")` |

リソースのメンバーにはリソース名が前置されるため、2 つのリソースが同じメソッド名を
宣言してもフラットな Lisp-2 の関数名前空間で衝突しません。また**メソッドはハンドルを
第 1 引数として取ります** (`self`。WIT では暗黙のもの)。残りの引数は WIT
の名前のままで、リソース自体は不透明な整数ハンドルです。各束縛は**通常の関数**なので、
`#'kv:bucket-get`、`funcall`、`mapcar`、`eval` が追加の配線なしにそのまま使えます。

## どうローワリングされるか

| Backend | The directive becomes |
| --- | --- |
| interpreter | one `defun` per WIT function, dispatching through the interface's provider |
| JVM (`-o Prog.class`) | the same `defun`s, compiled |
| Preview 1 WASM (`-o prog.wasm`) | one [`rontolisp:wasm-import`](rontolisp-wasm-import.md) per WIT function |
| `--component` | a component-model **instance import** of the interface, each function `canon lower`ed into the core module |
| `--no-gc` | a compile error (its MVP module imports nothing) |

Preview 1 では、生成されるモジュールは手書きの等価物と**バイト単位で同一**であり、
[`--optimize`](../../compiling/wasm.md#optimize-tree-shaking)
はプログラムが呼び出さないインポートを従来どおり削ぎ落とします。

```console
;;; What (rontolisp:wit-import "wit/host.wit" :interface "example:host/math@0.1.0")
;;; lowers to on Preview 1 WASM, for `add-ints: func(a: s32, b: s32) -> s32`:
(rontolisp:wasm-import 'add-ints :from "math" :as "addInts"
                       :params '(:int :int) :returns :int)
```

`--component` では、インターフェースはコンポーネントのインスタンスインポートになり、
束縛された各関数は `canon lower` されたコアインポートになります。コンポーネントが
**インポートするのはプログラムが実際に呼ぶ関数だけ**です (コンポーネント経路には
コアのツリーシェイカーがないため、使われないインターフェースメンバーはインポート自体
から落とされます。`--no-prune` ですべて残せます)。
[`--emit-wit`](../../compiling/wasm.md) はその刈り込まれたインターフェースを
コンポーネントの world に書き出し、`wasm-tools component wit`
の出力とバイト単位で一致します。インポートのないコンポーネントは従来どおりです。

```bash
rontolisp counter.lisp -o counter.wasm --component
wasmtime run -W gc=y -W exceptions=y -W component-model-more-async-builtins=y \
    -S keyvalue=y counter.wasm             # the HOST is the provider
```

これは **serve される**コンポーネント
([`rontolisp:http-handler`](rontolisp-http-handler.md) + `--component`)
でも同じです。そのインポートはもはや固定の `wasi:http`
表面だけではないので、ハンドラの状態をプロセスローカルなハッシュテーブルではなく本物の
ストアに置けます — `wasi:http` ホストはリクエストごとにコンポーネントを新しく
インスタンス化するので、serve されるコンポーネントが状態を保つ道はこれしかありません。

```bash
rontolisp page-hits-server.lisp -o server.wasm --component
wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y server.wasm
```

その状態が実際に*残る*かどうかはコンポーネントではなくホストの都合です:
wasmtime 組み込みのキーバリュープロバイダはインスタンスごとに作り直されるインメモリ
ストアなので (`wasmtime serve` の下ではリクエストごと)、カウントは残りません。一方、
プロセス外のプロバイダをリンクするホスト (たとえば wasmCloud) なら残ります。
コンポーネント自体はどちらでも同じものです。

## プロバイダ

インタプリタと JVM にはホストが存在しないため、呼び出しは**プロバイダ**へ向かいます。
プロバイダとは、束縛された関数の Lisp メンバー名 (**文字列** — `"open"`、
`"bucket-get"`) に続けてその関数の引数を受け取る、通常の Lisp
呼び出し可能オブジェクトです。[`rontolisp:wit-provide`](rontolisp-wit-provide.md)
がそれを束縛します。

```console
(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)
```

- **rontolisp はどのインターフェースについてもプロバイダを同梱していません。**
  インターフェースの関数を束縛するのは言語の仕事ですが、それらを実装するのは
  あなたの仕事です。新しいホストインターフェースに必要なのはコアのコードではなく、
  `.wit` ファイル 1 つと `rontolisp:wit-provide` 1 行だけです。
- 束縛された関数を、そのインターフェースの**プロバイダが束縛されていない状態**で呼ぶと
  [`rontolisp:wit-error`](rontolisp-wit-provide.md#the-wit-error-condition)
  がシグナルされます:
  `No provider is bound for the WIT interface wasi:keyvalue/store@0.2.0 -- bind one with rontolisp:wit-provide`。
- `rontolisp:wit-provide` はそのインターフェースのプロバイダを**置き換えます**。
  そのため、偽のストアを本物に差し替えるのは — 好きなら別ファイルに置いた — 1
  行で済み、呼び出し箇所は 1 つも変わりません。
- **WASM** バックエンドではホストがインポートを供給するため、トップレベルの
  `rontolisp:wit-provide` はエラーにはならず**捨てられます** (無効化されます) —
  1 つのソースがどこでも動きます。

## エラー (`result<T, E>`)

WIT の `result<T, E>` は値ではありません。**ok アームが関数の戻り値**であり、
**error アームは `rontolisp:wit-error` をシグナルして**、マップされた `E`
をペイロードとして運びます (確定済みのマッピングで、すべてのバックエンドで同じです)。
シグナルするのはプロバイダであり、呼び出し側は `handler-case` で捕捉し、
[`rontolisp:wit-error-payload`](rontolisp-wit-provide.md#the-wit-error-condition)
でペイロードを読みます。

これは「戻り値」方向の話であり、`result` にはこれまでこの方向しかありませんでした。
**引数として渡す** `result` は何もシグナルできません (引数はどちらのアームなのかを
*値として言う*しかありません)。そのためアームを保ったまま渡します: エンベロープの
cons `(:ok . V)` / `(:error . E)` — これは戻り値の `result` が ok
アームをほどかれる前に持っている形とまったく同じです。この非対称は一方向で、意図的です:
**出るときはほどく、入るときは包んだまま**。だからこそ、ある呼び出しが返した値を
そのまま次の呼び出しに渡せます。

## サポートする WIT 型

境界は 3 段構えです。**インタプリタと JVM**
では呼び出しは通常の Lisp 呼び出しなので、あらゆる表現が渡ります — この表は
マーシャラーの仕様ではなく、プロバイダを書くときの契約です。**Preview 1 WASM**
の境界では `rontolisp:wasm-import` が運べるフラットな型集合だけが渡ります。コア
インポートは素のホスト関数であり、より豊かな形をホストに伝えるためのコンポーネント型
が存在しないからです。**`--component`** では canonical ABI
が豊かな型をマーシャリングするので、`record`、`variant`、`enum`、`option`、`tuple`、
`result` は**両方向で** — 戻り値としてだけでなく引数としても — 渡ります。渡らないのは
2 つだけです: `flags` (どちらの向きでも)、および**引数としての** `list<T>`
(`list<u8>` はバイト文字列として渡ります)。サポートされない型は WIT
ファイル名と行番号を示すコンパイルエラーになります。

| WIT type | Lisp value | Preview 1 | `--component` |
| --- | --- | --- | --- |
| `s8` `s16` `s32` `u8` `u16` `u32` | an integer | `:int` | yes |
| `s64` `u64` | an integer | no | yes |
| `f32` `f64` | a float | `:float` | yes |
| `bool` | `t` / `nil` | `:bool` | yes |
| `string` | a string | `:string` | yes |
| `char` | a character | no | yes |
| `list<u8>` | a string of raw bytes (one per char) | `:string` | yes |
| `list<T>` | a proper list | no | result only |
| `tuple<...>` | a proper list, positional | no | yes |
| `option<T>` | the value, or `nil` | no | yes |
| `result<T, E>` | returned: the ok value, the error arm signals `rontolisp:wit-error`; passed: the `(:ok . V)` / `(:error . E)` envelope | no | yes |
| `record` | a keyword plist | no | yes |
| `enum` | a keyword | no | yes |
| `variant` | a keyword, or `(keyword . payload)` | no | yes |
| `flags` | a list of keywords | no | no |
| `resource`, `borrow<R>`, `own<R>` | an opaque integer handle | `:int` | yes |
| `stream`, `future` | — | no | no |

`stream` と `future` はどのバックエンドにも対応する rontolisp
の値がないため (言語レベルの async が必要)、すべてのバックエンドで拒否されます。

### 豊かな値を引数として渡す

引数は、**同じ型が戻り値として取るのとまったく同じ形**を取ります。ある呼び出しが返した値を、
そのまま次の呼び出しに渡せます:

```console
;;; variant: case のキーワード。ペイロードを持つ case は (keyword . payload)
(http:outgoing-request-set-method req :post)
(http:outgoing-request-set-method req '(:other . "PATCH"))
(http:outgoing-request-method req)                 ; => (:other . "PATCH")

;;; enum: キーワード
(sock:tcp-socket-create :ipv4)

;;; record はキーワード plist、tuple は位置指定のリスト。ここでは variant の case
;;; ペイロードの中に両方が入っている
(sock:tcp-socket-bind s '(:ipv4 :port 0 :address (127 0 0 1)))

;;; result の「引数」は (:ok . V) / (:error . E) というエンベロープ — result が
;;; 「戻り値」のとき ok アームがほどかれる前と同じ形です。ペイロードのないアームは
;;; 裸のキーワードでも書けます。
(cli:exit '(:error))
(cli:exit :ok)
```

variant のどの case でもないキーワードを渡すのは**型エラー**です。WASM
バックエンドではトラップし (`(+ 1 "a")` など他のあらゆる型エラーとまったく同じ挙動)、
インタプリタと JVM ではそのままプロバイダに届き、扱いはプロバイダが決めます。

## 制限事項

- `--no-gc` はこのディレクティブを明確なエラーで拒否します。その契約は、何もインポート
  しない素の MVP モジュールだからです。
- Preview 1 の境界を渡れるのは上表のフラットな集合だけです。`record`、`option`、
  `result`、`s64` は WIT ファイル名と行番号を示すコンパイルエラーになります —
  `wit/store.wit:12: 'bucket-get': the WIT type of the result does not cross the Preview 1 WASM import boundary, which carries the flat set (...)` —
  `--component`、インタプリタ、JVM のいずれもが束縛できるとしてもです。上の
  `wasi:keyvalue` の例はしたがって Preview 1 のプログラムではなく、コンポーネント
  (あるいはインタプリタ／JVM) のプログラムです: その `result`
  アームが Preview 1 の境界から遠ざけています。
- `--component` では、**引数としての `list<T>`** (`list<u8>` を除く)
  はコンパイルエラーになります。同じ型が*戻り値*としては渡るとしてもです。引数はフラット化
  されますが、リストは代わりに canonical な配列としてリニアメモリに書き込む必要があるためです。
  `flags` は今のところどちらの向きでも渡りません。
- `--component` では、コンポーネントが**自身の WASI 表面としてすでにインポートしている**
  インターフェースを束縛できません (その表面はプログラムが使う機能に応じて増えます:
  `rontolisp:fetch` は `wasi:http` と `wasi:io` を、`rontolisp:tcp-*`
  組み込みは `wasi:sockets/types` を追加します)。コンポーネントは同じインターフェースを
  2 回インポートできないため、これはインターフェース名を示すコンパイルエラーになります —
  組み込みと併用するのではなく、組み込みの*代わりに* WIT 束縛経由で使ってください。
- ディレクティブは**トップレベルで、インターフェースを呼ぶコードより前**に置かなければ
  なりません ([`wit-export`](rontolisp-wit-export.md) は逆に最後に置きます)。
  パッケージと束縛を定義するのがこのディレクティブだからです。呼び出し箇所より後に
  置くと、どのバックエンドでも `No such package: kv` エラーになります。
- `:package` を使ってください。指定しないと束縛は現在のパッケージに入り、`cl`
  の名前 (`open`、`close`、`delete` など) と衝突する WIT
  ラベルはバックエンドごとに解決が食い違います — インタプリタは束縛を、JVM
  バックエンドは `cl` の関数を選びます。
- 束縛できるのは**インターフェース**だけです。world の `import`
  項目は読まれません (コンポーネントの WASI インポートは、それが構築される固定の
  アダプタ表面から来ます)。
- リソースハンドルは不透明で、rontolisp が解放することはありません。`drop`
  はありません。インターフェース自身が解放用の関数を宣言していれば、それは他の
  メンバーと同様に束縛されます。
- WASM バックエンドでは、他の関数と同様に 7 引数のアリティ制限が束縛にも適用されます。
  メソッドの先頭の `self` も数えます。
- コンパイルされた Preview 1 モジュールをインスタンス化するには、ツリーシェイキング後に
  残るすべてのインポートをホストが供給する必要があります。`wasmtime run` には
  `--preload <module>=<file>.wasm` が必要で、JavaScript
  ホストはインポートオブジェクトを渡します。
