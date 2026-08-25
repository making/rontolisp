# macOS GUI (objc / appkit)

2 つの組み込みパッケージで、何もインストールせずに rontolisp の REPL から本物の Cocoa ウィンドウを開けます。`objc` は JVM の Foreign Function API を通じて Objective-C ランタイムと AppKit をバインドし (JNI なし、同梱ネイティブライブラリなし、リフレクションなし)、`appkit` はその上に rontolisp で書かれた小さなウィジェット層です — ウィンドウ、ラベル、Lisp クロージャをアクションに持つボタン。

> **macOS 専用。インタプリタと JVM クラスで動作。** 両パッケージは `java -jar rontolisp.jar`、`rontolisp` ネイティブバイナリ (バインディングはリフレクションを必要としないためで、これが `java:` 連携にはできないことです)、そしてバインディングを内部に抱えた `.class` / `.jar` にコンパイルしたプログラムで動作します。どちらの WASM バックエンドにも foreign function API はないので、そうしたプログラムを `.wasm` にコンパイルすると `Cannot compile: appkit:window ...` エラーになります。Linux 上、またはネイティブアクセスを拒否する JVM (`--illegal-native-access=deny`) では、すべての `objc:` 関数が関数名で始まり理由を述べるメッセージの通常の `error` をシグナルします。

## REPL からウィンドウを

```console
> (defvar *win* (appkit:window "counter" :width 420 :height 200))
> (defvar *label* (appkit:label *win* "no clicks yet" :x 20 :y 120 :width 380))
> (defvar *n* 0)
> (appkit:button *win* "Click me" :x 20 :y 40
    :on-click (lambda ()
                (setq *n* (+ *n* 1))
                (appkit:set-text *label* (format nil "clicked ~a time(s)" *n*))))
```

ウィンドウが中央に前面表示され、ボタンをクリックするとクロージャが実行されてラベルが更新されます。その間も REPL はあなたのものです — ウィンドウはプロセスの最初のスレッド上にあり、入力を読むスレッドとは別です — し、ウィンドウを閉じても REPL は終了しません。`examples/macos/counter.lisp` は同じプログラムをスクリプトにしたもので、末尾の `(appkit:wait *win*)` がウィンドウが閉じられるまでブロックします。スクリプトのプロセスは最後のフォームが返ると終了するためです。

もっと大きなものも同じように Lisp で組み立てます。`examples/browser/minesweeper/minesweeper-macos.lisp` は Cocoa ウィンドウで完全なマインスイーパを遊べますし、その描画層 — 角丸パネル、垂直中央寄せのラベル、クリック可能なグリッド、繰り返しタイマーを持つ再利用可能な `cocoa` パッケージ `examples/macos/cocoa.lisp` — はすべて以下の動詞だけで書かれています。

| 関数 | 用途 |
|------|------|
| `appkit:window` | `(appkit:window title &key (width 480) (height 300))` — 表示済み・中央配置の `NSWindow` |
| `appkit:label` | `(appkit:label window text &key (x 20) (y 20) (width 200) (height 24))` — `NSTextField` のラベル |
| `appkit:button` | `(appkit:button window title &key x y (width 120) (height 32) on-click)` — `NSButton`。`on-click` は引数なしの関数 |
| `appkit:set-text` | `(appkit:set-text view text)` — ボタンならタイトル、それ以外のコントロールなら string value |
| `appkit:text` | `(appkit:text view)` — タイトルまたは string value を Lisp 文字列で |
| `appkit:click` | `(appkit:click button)` — クリックと同じようにアクションを実行 |
| `appkit:close` | `(appkit:close window)` — ウィンドウを閉じる (隠す)。値は有効なまま |
| `appkit:visible-p` | `(appkit:visible-p window)` — 画面上にあるかどうか |
| `appkit:wait` | `(appkit:wait window)` — ウィンドウが閉じられるまで呼び出し側スレッドをブロック |

座標系は AppKit のもので、原点はウィンドウの左下です。すべてのウィジェットはただの Objective-C オブジェクトなので、この層にないものは `objc:send` 一つ分の距離にあります:

```console
> (objc:send *win* "setBackgroundColor:"
    (objc:send "NSColor" "colorWithRed:green:blue:alpha:" 0.9 0.95 1.0 1.0))
> (objc:send *win* "frame")
(690.0 676.0 420.0 228.0)
```

## objc パッケージ

`objc` は `java` とちょうど対になるもので、外部システムの名前を冠したパッケージに少数の汎用的な動詞があります。

| 関数 | 用途 |
|------|------|
| `objc:class` | `(objc:class "NSWindow")` — 名前でクラスを得る |
| `objc:send` | `(objc:send receiver "selector:with:" arg1 arg2)` — メッセージを送る。receiver はオブジェクト、クラス、またはクラス名の文字列 |
| `objc:define-class` | `(objc:define-class "Name" "NSObject" methods &optional protocols)` — メソッドが Lisp 関数であるクラス |
| `objc:on-main` | `(objc:on-main (lambda () ...))` — 関数をメインスレッドで実行してその値を返す |
| `objc:string` | `(objc:string "text")` — `NSString` |
| `objc:address` | `(objc:address object)` — オブジェクトのアドレス (整数) |
| `objc:objectp` | `(objc:objectp x)` — `x` が Objective-C オブジェクトかどうか |

```console
> (objc:send (objc:string "hello world") "length")
11
> (objc:send (objc:send (objc:string "hello") "uppercaseString") "UTF8String")
"HELLO"
> (objc:send (objc:string "hello world") "rangeOfString:" "world")
(6 5)
> (objc:send "NSNumber" "numberWithDouble:" 2.5)
#<objc __NSCFNumber>
```

### セレクタ自身のエンコーディングで型付け

`objc:send` はシグネチャを推測しません。Objective-C ランタイムはすべてのメソッドを完全に記述しており (`method_getTypeEncoding` は例えば `initWithContentRect:styleMask:backing:defer:` に対して `@68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64` を返します)、各引数と結果はその宣言に従ってマーシャリングされます:

| 宣言された型 | Lisp の引数 | Lisp の結果 |
|--------------|-------------|-------------|
| オブジェクト (`@`) | オブジェクト、`nil`、または文字列 (`NSString` として送られる) | オブジェクトまたは `nil` |
| クラス (`#`) | オブジェクトまたはクラス名 | オブジェクト |
| セレクタ (`:`) | セレクタ名の文字列 | 名前 |
| C 文字列 (`*`) | 文字列 | 文字列 |
| `BOOL` | `t` / `nil` | `t` / `nil` |
| 整数各種 | 整数 | 整数 |
| `float` / `double` | 数 | 浮動小数点数 |
| 構造体 (`{...}`) | 数のリスト (構造体のスカラーフィールドを順に。`NSRect` なら `(x y w h)`) | 数のリスト |
| その他のポインタ (`^`) | オブジェクト、整数アドレス、または `nil` | 整数アドレス |

receiver が応答しないセレクタ、引数の個数違い、宣言型に合わない引数はクラッシュではなく `error` になります。`performSelector...` メッセージの答えは捨てられます (その型はターゲットメソッドのもので、バインディングからは見えません)。ブロック、共用体、ビットフィールドはこの第一段階の範囲外で、それらを取るセレクタは名前を挙げて拒否されます。

### スレッド: すべてはメインスレッドで起きる

AppKit はプロセスの最初のスレッドのものであり、すべての `objc:send` は自分でそこへ移動します — 同期的に、なので値は呼び出し側に返ってきます。複数の send から成るウィジェットは `objc:on-main` で包むと移動を 1 回だけ払うことになり、`appkit` の関数はそうしています。すでにメインスレッド上で動いている関数 (ボタンのハンドラ) は send をインラインで実行するので、コールバックから自由に GUI を呼び戻せます。

コールバックはインタプリタの *グローバル* な動的束縛で動きます — REPL スレッドでの special 変数の `let` 束縛はそこから見えません — し、ハンドルされなかったエラーはシグナルではなく `objc: error in a callback: ...` として表示されます。AppKit のイベントの上にシグナル先となる Lisp のフレームは存在しないためです。

### 実行時に定義するクラス

`objc:define-class` はメソッドが Lisp 関数であるクラスを登録します。各メソッドは最初に receiver、続いて自身の引数を受け取ります:

```console
> (defvar *target-class*
    (objc:define-class "MyTarget" "NSObject"
      (list (list "invoke:" (lambda (self sender)
                              (format t "clicked ~a~%" sender))))))
> (defvar *target* (objc:send (objc:send *target-class* "alloc") "init"))
> (objc:send button "setTarget:" *target*)
> (objc:send button "setAction:" "invoke:")
```

メソッドの型は、スーパークラスがそのセレクタを宣言していればそこから、そうでなければ採用したプロトコルから取られ (`(objc:define-class "Delegate" "NSObject" methods '("NSWindowDelegate"))` は `windowShouldClose:` を `BOOL` として型付けします)、どちらにもなければ target/action の形 — 結果なし、コロンごとに 1 つのオブジェクト引数 — がデフォルトになります。メソッドが取れる形は閉じた集合です: 引数なし、オブジェクト引数 1 つまたは 2 つ、オブジェクト引数 1 つで `BOOL`・オブジェクト・整数のいずれかを返す。定義を再評価すると失敗せずクラスのメソッドが束縛し直されるので、REPL でハンドラを反復できます。

### 所有権

`objc:` の値はオブジェクトへの参照を 1 つ所有します — `alloc` / `new` / `copy` / `mutableCopy` / `retain` の結果からは引き継ぎ、それ以外は retain して — そして Lisp の値が回収されたときにメインスレッド上で解放します。つまり保持しているウィンドウや文字列は保持している限り有効で、手で解放するものはありません。唯一の規則: `objc:` で直接作るウィンドウには `appkit:window` がしているように `(objc:send win "setReleasedWhenClosed:" nil)` が必要です。さもないと閉じたときに Lisp の値がまだ持っている参照が解放されます。

## ネイティブバイナリ

`rontolisp` バイナリはビルド時に登録された `objc_msgSend` の形の固定テーブルを提供します — `appkit` 層が送るすべての形に加え、AppKit と Foundation の中核クラスで最も多い 60 の形で、それらが宣言するメソッドの 10 のうち 9 に届きます。テーブルにないセレクタは追加すべきエントリをそのまま示してシグナルします:

```text
objc:send: someRareSelector: the shape void(void*,void*,jshort) has no foreign-call stub
in this binary; register it under foreign.downcalls in reachability-metadata.json and rebuild
```

JVM は事前に何も登録せずどんな形でもバインドするので、バイナリを作る前にプログラムが何を送るかを知る場所は `java -jar` です。

## JVM クラスへのコンパイル

同じプログラムは `.class` や `.jar` にコンパイルでき、素の `java` ランチャで動きます。ランチャはプロセスの最初のスレッドを自分でイベントループに留めます:

```console
$ rontolisp examples/macos/counter.lisp -o Counter.class --class-name Counter
$ java Counter
$ rontolisp examples/macos/counter.lisp -o counter.jar --class-name Counter
$ java -jar counter.jar
```

クラスはバインディング全体 (`am.ik.objc`、自身のパッケージにリネーム済み) と使用する `appkit` ウィジェットを抱えているので、`java.lang.foreign` を持つ JVM (コンパイラが動いたものか、それより新しいもの) 以外には何も必要ありません。素の `.class` を `--enable-native-access=ALL-UNNAMED` なしで実行すると JDK の restricted-method 警告が一度出ますが動作します。`.jar` はマニフェストでネイティブアクセスを有効にします。`rontolisp` バイナリもそうしたプログラムをコンパイルできます。`.wasm` 出力は拒否され (`Cannot compile: appkit:window ...`)、今後もそうです: そちら側には foreign function API も AppKit もありません。

## 制限

- macOS のみ: インタプリタ (`java -jar`、または `rontolisp` バイナリ) とコンパイル済み `.class` / `.jar`。`.wasm` は不可で、`objc:` / `appkit:` の参照は両 WASM バックエンドでコンパイルエラーです。
- アプリケーションバンドルのないプロセスには Dock アイコンもメニューバーもありません。Cmd-Q はなく、最後のウィンドウを閉じても終了しません — REPL がプロセスです。
- コールバックの形は上の閉じた集合です。構造体や整数の引数を持つデリゲートメソッド、ブロックを取るセレクタは、この段階にはない段を必要とします。
- Apple シリコン向け。Intel Mac では 2 レジスタより広い構造体は `objc_msgSend_stret` で返され、バインディングはそれを選びますが動作確認はしていません。
