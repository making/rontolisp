# WIT 契約(`wit-export` / `wit-import`)

プログラムの境界を、誰かが書いた(あるいはバインディングジェネレータが生成した)`.wit` ファイルから直接持ってこられる 2 つのディレクティブです: **`rontolisp:wit-export`** は world を実装し、**`rontolisp:wit-import`** はインターフェースを呼び出します。どちらも新しいローワリングパスを追加するものではありません — 手動の [`wasm-export` / `wasm-import`](wasm-host-boundary.md) 機構への型付きフロントエンドと、同じソースがどこでも動くようにするバックエンドごとの実装(`--component` では型付きコンポーネントモデルエクスポート、インタプリタと JVM ではプロバイダコールバック、Preview 1 ではバイト単位で同一のインポート)です。

## WIT world の実装(`wit-export`)

**`rontolisp:wit-export`** は誰かが書いた world をコンパイラに渡し、プログラムがそれを**実装**します:

```console
// wit/greeter.wit
package example:greeter;

world greeter {
  /// Greet someone by name.
  export greet: func(who: string) -> string;
}
```

```console
;;; greet.lisp -- the directive comes last: on the interpreter it sees only the
;;; functions defined so far.
(defun greet (who)
  (concatenate 'string "Hello, " who "!"))

(rontolisp:wit-export "wit/greeter.wit" :world greeter)
```

```bash
rontolisp greet.lisp --component -o greet.wasm
wasmtime run -W gc=y --invoke 'greet("world")' greet.wasm
# "Hello, world!"
```

どこにも `:params '(:string) :returns :string` はありません — 型は world から来ます。これこそが要点です: 手書きの境界型は、別途生成される `.wit` の隣に置かれ、両者は乖離していき、最終的に `wasmtime --invoke` が実行時に失敗して初めて気づきます。`wit-export` では **WIT が唯一の真実の源**です:

- world がプログラムのエクスポート一覧であるため、同じプログラム内の手書きの `rontolisp:wasm-export` はコンパイルエラーです。
- すべてのエクスポートには正しいアリティの `defun` が必要で、すべての WIT 型は境界が運べるもの(`s8` 〜 `u64` の固定幅整数すべてと `f64`、`bool`、`string`)でなければならず、world 中の `async func` はそのエクスポートを `:async t` としてリフトします(I/O を行うエクスポートは推測されるのではなく WIT によって非同期と宣言されます)。各不一致は WIT ファイル名と行番号を示すコンパイルエラーになります:
  `wit/greeter.wit:5: export 'greet' declares 1 parameter(s), but (defun greet ...) takes 2`。
- 契約は**すべての**バックエンドで検査されます: 素の `rontolisp greet.lisp` 実行(や `-o Greet.class` ビルド)は world を検証するだけでエクスポートは行いません。したがって乖離は WASM ビルドよりずっと手前で捕まります。

このディレクティブは前節までの機構のフロントエンドであって、第 2 のエクスポート経路ではありません: 手書きの実装が持つのとまったく同じ `rontolisp:wasm-export` ディレクティブへローワリングされるため、**生成されるコンポーネントはそれとバイト単位で同一**です — GC パスでも [`--no-gc --component`](wasm-nogc.md#compact-component-output---no-gc---component) でも同様です(`s64`/`u64` を使う world は両者で動作します。GC バックエンドはボックス化された正確な整数で 64 ビット型を運びます)。

ビルドに [`--emit-wit`](#emitting-the-wit-world---emit-wit) を追加するとコンポーネントの実際の型が書き出され、そのエクスポート行は書いたとおりに戻ってきます。引数名も含めてです — WIT の名前はコンポーネントの関数型まで運ばれます(手書きのエクスポートは、自分で `:param-names '(who)` と宣言しない限り引数を `p0`、`p1`、... と名づけます)。

```bash
rontolisp greet.lisp --component -o greet.wasm --emit-wit   # writes greet.wit
```

```text
export greet: func(who: string) -> string;
```

ただしこの行は不動点であって、判定ではありません: world *から*導出されたものである以上、world と矛盾しえないのです。それでも出力する理由はファイルの残りにあります — world が何も語らない `wasi:*` インポートと `wasi:cli/run` エクスポート、すなわちホストが供給しなければならないものです。`greet.wit` は、あの 1 つのエクスポートのまわりに 149 行あります。入力との意図的な違いが 2 つあります: `///` ドキュメントコメントは失われます。コンポーネントの型がそれを保持しないためです(`wasm-tools` も復元できません)。そして出力される world は常に `package root:component; world root` です。それがコンポーネントの型*そのもの*だからです。

### インターフェースをエクスポートする

ほとんどの WIT world は、素の関数ではなく**インターフェース**をエクスポートします — 定石は、インターフェース定義を world から分離して書く形です:

```console
// wit/adder.wit
package docs:adder@0.1.0;

interface add {
  add: func(x: s32, y: s32) -> s32;
}

world adder {
  export add;
}
```

`wit-export` はこれも同じように実装します: `export add;` をファイル内で定義された `add` インターフェースへ解決し、その各関数をプログラムと照合します。するとコンポーネントは本当にそのインターフェースをエクスポートするので、`wasm-tools component wit` もホストも、平坦化されたトップレベル関数ではなく `docs:adder/add` を見ます:

```console
;;; adder.lisp
(defun add (x y) (+ x y))

(rontolisp:wit-export "wit/adder.wit" :world adder)
```

```bash
rontolisp adder.lisp --component -o adder.wasm
wasmtime run -W gc=y --invoke 'add(20, 22)' adder.wasm
# 42
```

インライン `export ops: interface { ... }` も同じように、その素の名前をキーとして動作します。`--emit-wit` はインターフェースを復元します — `export docs:adder/add@0.1.0;` とその `interface` 定義を、`wasm-tools` が印字するのとバイト単位で同じ形で。

現在の制限:

- 束縛されるのは world の**エクスポート**側だけです。`import` 項目は無視され(コンポーネントの WASI インポートは world からではなくビルドから来ます — それを見る手段が [`--emit-wit`](#emitting-the-wit-world---emit-wit) です)、インラインの `import name: func(...)` は黙って捨てるのではなく拒否されます。プログラムが呼び出す関数は、[`wit-import`](#importing-a-wit-interface-wit-import) でインターフェースから束縛します(あるいは `rontolisp:wasm-import` で手書きします)。
- world がエクスポートできるのは、素の関数か、**同じファイル内で定義されたインターフェース**(上記)です。ファイルが定義しないインターフェース — 素の `wasi:*` 参照 — を指すエクスポートはエラーであり、`rontolisp:http-handler` のプログラムは world をまったく使えません(serve モードのコンポーネントの唯一のエクスポートは `wasi:http/handler@0.3.0` です)。
- `:s-expr` に対応する WIT の綴りはないため、任意の S 式を境界で受け渡すエクスポートには引き続き手書きの `rontolisp:wasm-export` が必要です。
- インタプリタではディレクティブは順に評価され、それまでに定義された関数しか見えません。ファイルの末尾に置いてください。

## 実装のスケルトン生成(`--scaffold-wit`)

`--scaffold-wit` は「`.wit` を渡された、さてどうする」への答えです: コンパイルする代わりに、実装のスケルトンを生成します。

```bash
rontolisp --scaffold-wit wit/greeter.wit -o greet.lisp   # no -o: print to stdout
```

```console
;;;; Implementation of the WIT world 'greeter' (wit/greeter.wit).
;;;;
;;;; The world is the contract: the compiler checks every defun below against
;;;; it, so a renamed export, a changed arity or a changed type is a compile
;;;; error rather than a runtime surprise. Fill in the bodies; each one signals
;;;; until you do.

;;; Greet someone by name.
;;; WIT: greet: func(who: string) -> string
(defun greet (who)
  (error "greet is not implemented yet"))

(rontolisp:wit-export "wit/greeter.wit" :world greeter)
```

引数は WIT の名前のまま命名され、各エクスポートの WIT シグネチャは満たすべき契約としてスタブの上に書き出され、`///` ドキュメントコメントは `;;;` コメントになります。スタブはコンパイル時ではなく**実行**時にシグナルするため、生成されたファイルはそのままコンパイルでき、エクスポートを 1 つずつ埋めていけます。インターフェースをエクスポートする world は、インターフェース関数ごとに 1 つのスタブを生成するので、上記の分離形も同じスケルトンになります。`.wit` が複数の world を宣言している場合は `--world NAME` を追加してください。

## WIT ワールドの出力(`--emit-wit`)

任意の `--component` ビルドに `--emit-wit` を追加すると、コンポーネントの WIT 記述も `.wasm` 出力の隣に書き出されます — `-o sumsq.wasm --emit-wit` は `sumsq.wit` を書きます:

```bash
rontolisp sumsq.lisp --component -o sumsq.wasm --emit-wit
```

```text
// sumsq.wit (the world; the file also carries the referenced package
// definitions, so it is self-contained and parseable on its own)
package root:component;

world root {
  import wasi:cli/types@0.3.0;
  import wasi:cli/stdout@0.3.0;
  // ... the WASI imports of the build's blob variant ...

  export wasi:cli/run@0.3.0;
  export sumsquared: func(p0: s32, p1: s32) -> s32;
}
```

このテキストは同じバイト列に対して `wasm-tools component wit sumsq.wasm` が印字するものと一致するため、まさにコンポーネントの実際の表面です — しかしもうバイナリを内省する必要はありません: `.wit` をそのままバインディングジェネレータに渡せます。例えば jco は `.wasm` に触れることなく、この `.wit` から TypeScript の型定義を生成します:

```bash
npx @bytecodealliance/jco types sumsq.wit -o types/
# types/sumsq.d.ts: export function sumsquared(p0: number, p1: number): number;
```

world のインポートはビルドのバリアントに従います(プレーン、`rontolisp:fetch`、`rontolisp:tcp-*`、`rontolisp:http-handler`。[`--no-gc --component`](wasm-nogc.md#compact-component-output---no-gc---component) では world はインポートなしになり、プログラムが印字するときは `wasi:cli/stdout@0.3.0` のインポート — と `async func` のエクスポート — を持ちます)。さらに [`--optimize`](../compiling/wasm.md#optimize-tree-shaking) を付けると、そのバリアントのうちプログラムが実際に到達できる部分だけになります: 上の world は本当に必要な 2 つの `wasi:cli` インポートまで縮みます。`:async t` エクスポートは `async func` として描画され、`rontolisp:http-handler` ビルドは `run` の代わりに `wasi:http/handler@0.3.0` をエクスポートします。`--component` なしの `--emit-wit` はコンパイルエラーです — コアモジュールには記述すべき WIT レベルの表面がありません。

### `--emit-wit` は何のためにあるか

エクスポート一覧がどこから来たかによって、答える問いが変わります。

**world を持たないプログラム** — `rontolisp:wasm-export` で手書きしたエクスポート、あるいは WIT の綴り自体が存在しない `:s-expr` エクスポート — には `.wit` がどこにもありません。上のとおり、`--emit-wit` がそれを得る唯一の手段です。

**world を持つプログラム**([`wit-export`](#implementing-a-wit-world-wit-export))は、エクスポートについてはすでに書き下しています。書き下していないのはコンポーネントの**インポート**であり、そしてそちらの方が大きな半分です: `wit-export` が読むのは world の `export` 項目だけです。コンポーネントの WASI 表面は world からではなく、ビルドから来るからです。[前節](#implementing-a-wit-world-wit-export)の 6 行の `wit/greeter.wit` は、実際の型が **149 行**あるコンポーネントにコンパイルされます — 宣言したただ 1 つの `greet` のまわりに、10 個の `wasi:*` インポートと `export wasi:cli/run@0.3.0` が付きます。([`--optimize`](../compiling/wasm.md#optimize-tree-shaking) を付けるとこの表面はプログラムが到達できる範囲まで狭まります。world を仮定するのではなく、生成されたものを読むべき理由がもう 1 つ増えるわけです。)その `greet` から `rontolisp:fetch` を呼べば、ビルドはさらに 2 つのインポート(`wasi:http/types`、`wasi:http/client`)を黙って追加し、**216 行**になります。`rontolisp:tcp-*` も同様に `wasi:sockets` を引き込みます。`wasm-tools` を入れてバイナリを内省するのでない限り、自分が実際に何をビルドしたのかを見る手段は `--emit-wit` だけです — そしてそれこそが、ホストや `jco` がそれらのインポートを*供給する*ために必要とするものです。

一方、world を持つプログラムにとって `--emit-wit` が**そうではない**もの、それはそのプログラムの乖離チェックです。エクスポート行は構成上の不動点です: world が `rontolisp:wasm-export` ディレクティブを生み、それがコンポーネントの関数型を生み、それがそのまま印字されて戻ってくる — 双方向に 1 対 1 で対応する境界型の集合(固定幅整数すべてと `f64`、`bool`、`string`)の上での話です。渡した world と食い違って出てくることはありえません。したがって `.wit` を再出力して CI で差分を取るのは、*rontolisp 側の*型マッピングに対するリグレッションテストであって(安価であり、続ける価値もあります)、あなたのソースに対するチェックではありません。乖離したプログラムを捕まえるのは `wit-export` 自身であり、それはすでにすべてのバックエンド(素のインタプリタ実行を含む)で走っています。これは過渡的な状態です: world がプログラムの束縛するインポートも宣言できるようになれば、出力される WIT は真に双方向の契約になります。

## WIT インターフェースのインポート(`wit-import`)

`wit-export` が WIT 契約のエクスポート側だとすれば、**`rontolisp:wit-import`** はインポート側です。プログラムが WIT インターフェースを**呼び出す**ことを宣言し、そのインターフェースが宣言するすべての関数を通常の Lisp 関数として束縛します — 名前もラムダリストも型も、すべて `.wit` から取られます。これは既存のフォームへローワリングされるコンパイル時ディレクティブであり、*何に*ローワリングされるかはバックエンドごとに異なります。それこそが要点です: **1 つの WIT、バックエンドごとに異なる実装、ソース変更はゼロ**。

```console
// wit/host.wit
package example:host@0.1.0;

interface math {
  /// Add two integers on the host.
  add-ints: func(a: s32, b: s32) -> s32;
}
```

```console
;;; main.lisp -- the directive comes FIRST: it defines the functions the rest of
;;; the file calls.
(rontolisp:wit-import "wit/host.wit" :interface "example:host/math@0.1.0")

(defun add10 (n) (add-ints n 10))
(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
```

Preview 1 WASM では、各 WIT 関数が [`rontolisp:wasm-import`](wasm-host-boundary.md#importing-host-functions) になります。インポート**モジュール**はインターフェースの素の名前 (`math`。`:from` で変更可)、インポート**フィールド**は WIT ラベルの camelCase 表記 (`addInts` — JavaScript の慣習であり、`jco` が生成するものでもあります。`:field-style :kebab` でラベルのままにできます) です。したがってホスト側の満たし方は従来どおりです。ここではそのフィールド名で関数をエクスポートする、もう 1 つの Lisp モジュールが担います:

```console
;;; host.lisp
(defun host-add (a b) (+ a b))
(rontolisp:wasm-export 'host-add :as "addInts" :params '(:int :int) :returns :int)
```

```bash
rontolisp host.lisp -o host.wasm --no-wasi
rontolisp main.lisp -o main.wasm --no-wasi
wasmtime run -W gc --preload math=host.wasm --invoke add10 main.wasm 32
# 42
```

生成されるモジュールは、手書きの
`(rontolisp:wasm-import 'add-ints :from "math" :as "addInts" :params '(:int :int) :returns :int)`
が生成するものと**バイト単位で同一**です — このディレクティブは第 2 のインポート経路ではなく、その機構への型付きフロントエンドです。また [`--optimize`](../compiling/wasm.md#optimize-tree-shaking) はプログラムが呼び出さないインポートを従来どおり削ぎ落とすため、29 関数のインターフェースを束縛して 3 つだけ使ってもコストはかかりません。

### プロバイダ: インタプリタと JVM でも同じソース

インタプリタと JVM には WASM ホストが存在しないため、そこでは各 WIT 関数がインターフェースの**プロバイダ**へディスパッチする通常の `defun` になります。プロバイダとは、束縛された関数の Lisp メンバー名 (文字列) に続けてその関数の引数を受け取る Lisp 呼び出し可能オブジェクトです。[`rontolisp:wit-provide`](../reference/functions/rontolisp-wit-provide.md) がそれを束縛します — そして rontolisp は**どのインターフェースについてもプロバイダを同梱していません**。同梱しているのはプロバイダの仕組みであって、`wasi:keyvalue` が何であるかを rontolisp は知りません。WIT インターフェースの実装は通常の Lisp コードです:

```console
;;; counter.lisp -- wasi:keyvalue, against a store written in Lisp.
(rontolisp:wit-import "wit/store.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)

(defvar *rows* (make-hash-table :test #'equal))

(defun my-store (member &rest args)
  (cond ((string= member "open") 1)              ; the bucket handle: any integer
        ((string= member "bucket-set")
         (setf (gethash (nth 1 args) *rows*) (nth 2 args))
         nil)
        ((string= member "bucket-get") (gethash (nth 1 args) *rows*))
        (t (error 'rontolisp:wit-error :payload (list :other member)))))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)

(defvar *bucket* (kv:open "counts"))

(kv:bucket-set *bucket* "visits" "41")
(print (kv:bucket-get *bucket* "visits"))   ; "41"
```

`:package kv` は束縛をエクスポートする `defpackage` を合成します。WIT の `resource` のメソッドはハンドルを第 1 引数として取り (`bucket.get` は `(kv:bucket-get b "visits")` になります)、各束縛は通常の関数なので `#'kv:bucket-get`、`funcall`、`mapcar` がそのまま使えます。プロバイダが束縛されていない状態で呼ぶと、何らかの既定値に到達するのではなく `rontolisp:wit-error` がシグナルされます (`No provider is bound for the WIT interface wasi:keyvalue/store@0.2.0 -- bind one with rontolisp:wit-provide`)。

要点は、プロバイダが*ただの関数*だということです: 上のハッシュテーブルを本物のストア — Redis、ファイル、JDBC 接続 — に差し替えても、`(kv:bucket-set b "visits" "41")` を呼ぶコードは変わりません。[`wit/keyvalue` の例](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)は、1 つのページビューカウンタを 3 つのストア (可搬な Lisp ストア、JVM では `java.util.LinkedHashMap` のストア、そしてコンポーネントとしては wasmtime 自身の `wasi:keyvalue` 実装) の上で動かし、出力は同一です。同じソースを WASM にコンパイルすれば、代わりに**ホスト**がインターフェースを実装します。その場合トップレベルの `rontolisp:wit-provide` はエラーにならず**捨てられます** (ホストがプロバイダだからです)。まさに 1 つのソースがどこでも動くようにするためです。

WIT の `result<T, E>` は値ではありません。ok アームが戻り値で、error アームはマップされた `E` を運ぶ `rontolisp:wit-error` コンディションをシグナルします。これは `handler-case` で捕捉でき、`rontolisp:wit-error-payload` でペイロードを取り出せます。

### コンポーネント: ホストがプロバイダになる(`--component`)

まったく同じソースを `--component` でコンパイルすると、インターフェースはコンポーネントモデルの本物の**インポート**になります。コンポーネントは自身の型でそれを宣言し、束縛された各関数はコアモジュールへ `canon lower` されるので、呼び出しは canonical ABI を通って外へ出ます。コンポーネントの中にプロバイダは一切ありません — **ホストがプロバイダ**であり、そのインターフェースをエクスポートするホスト(あるいは他のコンポーネント)なら何でもそれを満たせます。wasmtime は `wasi:keyvalue` を実装しているので、それに対して書かれたプログラムはアダプタも書き換えもなしに動きます:

```bash
rontolisp counter.lisp -o counter.wasm --component
wasmtime run -W gc=y -W exceptions=y \
    -S keyvalue=y counter.wasm
```

豊かな型をマーシャリングするのは canonical ABI なので、コンポーネントの境界は Preview 1 の境界よりはるかに多くを運びます: `result` (その error アームは `rontolisp:wit-error` コンディションとして到着し、`handler-case` で捕捉できます)、`option`、`record` (キーワード plist)、`variant`、`enum`、`tuple`、`list<T>`、`list<u8>`、`string`、`bool`、そして `resource` ハンドル。

`list<T>` を除くすべてが**両方向で**渡ります。しかも引数は、同じ型が戻り値として取るのとまったく同じ形を取ります — ある呼び出しが返した値を、そのまま次の呼び出しに渡せます:

```console
;;; wasi:http/types, imported and called: a variant argument, whose `other` case
;;; carries a string
(http:outgoing-request-set-method req :post)
(http:outgoing-request-set-method req '(:other . "PATCH"))
(http:outgoing-request-method req)                 ; => (:other . "PATCH")

;;; wasi:sockets/types: an enum argument, then a variant whose case payload is a
;;; record (a keyword plist) carrying a tuple (a positional list)
(let ((s (sock:tcp-socket-create :ipv4)))
  (sock:tcp-socket-bind s '(:ipv4 :port 0 :address (127 0 0 1))))
```

いまだにローワリングできない唯一の形は **`list<T>` の引数**です (`list<u8>` はバイト文字列として渡ります)。引数はフラット化されますが、リストは代わりに canonical な配列としてリニアメモリに書き込む必要があるためです。これは WIT の行を示すコンパイルエラーになります。`flags` は今のところどちらの向きでも渡りません。

コンポーネントが束縛**できない**インターフェースが 1 つあります: そのコンポーネントが自身の WASI 表面としてすでにインポートしているものです。しかもその表面はプログラムが使う機能に応じて増えます (`rontolisp:fetch` は `wasi:http/types` と `wasi:http/client` を、`rontolisp:tcp-*` 組み込みは `wasi:sockets/types` を引き込みます)。コンポーネントは同じインターフェースを 2 回インポートできないため、これもコンパイルエラーです: 組み込みと併用するのではなく、組み込みの*代わりに* WIT 束縛経由で使ってください。

コンポーネントが**インポートするのはプログラムが実際に呼ぶ関数だけ**です (この経路にはコアのツリーシェイカーがないため、使われないインターフェースメンバーはインポート自体から落とされます。`--no-prune` ですべて残せます)。[`--emit-wit`](#emitting-the-wit-world---emit-wit) はその刈り込まれたインターフェースをコンポーネントの world に書き出し、`wasm-tools component wit` の出力とバイト単位で一致します。何もインポートしないコンポーネントは、この機能が存在しなかった頃のビルドとバイト単位で同一です。

コンポーネントの**合成**もこの仕組みです: `wasi:keyvalue/store` をインポートするコンポーネントは、それをエクスポートする任意の言語のコンポーネントへ [`wac`](https://github.com/bytecodealliance/wac) で差し込めます。ホストがランタイム組み込みである必要はありません。

### 本物のストアを持つ serve ハンドラ

**serve される**コンポーネント ([`rontolisp:http-handler`](http-handler.md) + `--component`) も同じようにユーザーインターフェースをインポートします。そのインポートは、エクスポート先である固定の `wasi:http` 表面だけではありません。これこそがハンドラが状態を持てる唯一の道です — `wasi:http` ホストは**リクエストごとにコンポーネントを新しくインスタンス化する**ので、グローバルなハッシュテーブルは毎回空で読み戻されます。ストアはその外側に生きています:

```bash
rontolisp page-hits-server.lisp -o server.wasm --component
wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y server.wasm
curl http://127.0.0.1:8080/index
```

そのカウントが実際に*残る*かどうかはコンポーネントではなくホストの都合です: wasmtime 組み込みのキーバリュープロバイダはインスタンスごとに作り直されるインメモリストアなので (`wasmtime serve` の下ではリクエストごと)、カウントは残りません。プロセス外のプロバイダをリンクするホストなら残ります — wasmCloud (`wash dev`) では同じコンポーネントが 1、2、3 と数えます。serve されるコンポーネントが束縛**できない**インターフェースは、自身の表面がすでにインポートしているものです: `wasi:http/types`、`wasi:http/client`、`wasi:cli/types`、`wasi:cli/stdout`、`wasi:cli/stderr`、`wasi:clocks/*`、`wasi:random/random`。

完全な例は [`examples/wit/keyvalue`](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue) にあります。

### リソースを解放する(`<resource>-drop`)

ハンドルは返さなければなりません。そして **WIT はそれを返すための関数を宣言しません**: リソースの解放はインターフェースのメンバーではなく、コンポーネントモデルの canonical な組み込み機能だからです。そこで rontolisp が名前を与えます — **`<resource>-drop`**、引数はハンドル 1 つ。コンストラクタが束縛する `<resource>-new` と対になる名前です:

```console
(let ((bucket (kv:open "")))
  (kv:bucket-set bucket "visits" "41")
  (print (kv:bucket-get bucket "visits"))
  (kv:bucket-drop bucket))
```

これが束縛されるのは、**プログラムがその名前を書いたときだけ**です (`--no-prune` と `--dynamic` はすべてのリソースの drop を束縛します)。だからこそ、drop が存在しなかった頃にコンパイルされたコンポーネントはバイト単位で同一のまま出てきます — 呼ぶかどうかに関わらず束縛される WIT の*関数*とは対照的です。インタプリタと JVM では、drop はメンバー名 `"bucket-drop"` としてインターフェースのプロバイダに届きます。したがってそれが何を*意味するか*はプロバイダが決めます: ハンドルを忘れる、接続を閉じる、あるいは解放するものが無いので `nil` を返す。Preview 1 では **no-op** です — そこでのハンドルはホストが手渡した不透明な整数にすぎず、WIT が宣言していない解放関数のインポートを rontolisp が勝手に作り出すことはありません。`--component` では `canon resource.drop` になり、ハンドルはホスト自身のテーブルへ返されます。

これはリークだけの話ではありません。インターフェースは drop を**義務**にできます: `wasi:http` は `outgoing-body` の子である `output-stream` を body を finish する前に drop するよう要求し、そうしなければトラップします。そして drop が解放するのは*参照*であって、その先にあるものではありません — ストアはそのまま残り、次の `kv:open` はすべてのキーをそのまま見ます。

現在の制限事項:

- `--no-gc` はこのディレクティブを明確なエラーで拒否します。その契約は、何もインポートしない素の MVP モジュールだからです。
- Preview 1 の境界を渡れるのは `rontolisp:wasm-import` が運べる型だけです — 32 ビットまでの整数スカラー、浮動小数点スカラー、`bool`、`string`、`list<u8>`、リソースハンドル。`record`、`option`、`result`、`s64` は、`--component`・インタプリタ・JVM のいずれもが束縛できるとしても、WIT ファイル名と行番号を示すコンパイルエラーになります (上の `wasi:keyvalue` プログラムが Preview 1 向けではなく、コンポーネントまたはインタプリタ／JVM 向けなのはそのためです: その `result` アームが Preview 1 の境界から遠ざけています)。コアインポートは素のホスト関数であり、より豊かな形を記述するためのコンポーネント型を持たないからです。`stream` と `future` はすべてのバックエンドで拒否されます。
- `--component` では、**`list<T>` の引数** (`list<u8>` を除く)、および位置を問わず `flags` はコンパイルエラーになります。`list<T>` は戻り値としては渡ります。
- 束縛できるのは**インターフェース**です。world の `import` 項目は依然として読まれません。
- ディレクティブはトップレベルで、インターフェースを呼ぶコードより**前**に置かなければなりません (パッケージと束縛を定義するのがこれだからです)。`wit-export` とは逆です。

オプションの一覧、名前マッピングの規則、WIT 型の表は
[wit-import](../reference/functions/rontolisp-wit-import.md) と
[wit-provide](../reference/functions/rontolisp-wit-provide.md)
のリファレンスページにあります。
