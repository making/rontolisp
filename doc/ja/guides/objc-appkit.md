# macOS GUI (objc / appkit)

2 つの組み込みパッケージで、何もインストールせずに rontolisp の REPL から本物の Cocoa ウィンドウを開けます。`objc` は JVM の Foreign Function API を通じて Objective-C ランタイムと AppKit をバインドし (JNI なし、同梱ネイティブライブラリなし、リフレクションなし)、`appkit` はその上に rontolisp で書かれた小さなウィジェット層です — ウィンドウ、ラベル、Lisp クロージャをアクションに持つボタン、色付きパネル、クリック、繰り返しタイマー、メニューバー項目。

> **macOS 専用。インタプリタ、JVM クラス、ネイティブ実行ファイルで動作。** 両パッケージは `java -jar rontolisp.jar`、`rontolisp` ネイティブバイナリ (バインディングはリフレクションを必要としないためで、これが `java:` 連携にはできないことです)、バインディングを持ち運ぶ `.class` / `.jar` にコンパイルしたプログラム、そしてランナー自身がバインディングである Apple シリコン向けの `--native` 実行ファイルで動作します。`.wasm` には foreign function API がないので、そうしたプログラムを `.wasm` にコンパイルすると `Cannot compile: appkit:window ...` エラーになります。Linux 上、またはネイティブアクセスを拒否する JVM (`--illegal-native-access=deny`) では、すべての `objc:` 関数が関数名で始まり理由を述べるメッセージの通常の `error` をシグナルします。

## REPL からウィンドウを

```console
CL-USER> (defvar *win* (appkit:window "counter" :width 420 :height 200))
CL-USER> (defvar *label* (appkit:label *win* "no clicks yet" :x 20 :y 120 :width 380))
CL-USER> (defvar *n* 0)
CL-USER> (appkit:button *win* "Click me" :x 20 :y 40
    :on-click (lambda ()
                (setq *n* (+ *n* 1))
                (appkit:set-text *label* (format nil "clicked ~a time(s)" *n*))))
```

ウィンドウが中央に前面表示され、ボタンをクリックするとクロージャが実行されてラベルが更新されます。その間も REPL は使えます。ウィンドウはプロセスの最初のスレッド上にあり、入力を読むスレッドとは別だからです。ウィンドウを閉じても REPL は終了しません。`examples/macos/counter.lisp` は同じプログラムをスクリプトにしたもので、末尾の `(appkit:wait *win*)` がウィンドウが閉じられるまでブロックします。スクリプトのプロセスは最後のフォームが返ると終了するためです。

もっと大きなものも同じように Lisp で組み立てます。`examples/browser/minesweeper/minesweeper-macos.lisp` は Cocoa ウィンドウで完全なマインスイーパを遊べますし、`examples/macos/life-macos.lisp` はその中でライフゲームを走らせます。どちらも以下のウィジェットだけでできています。2 つが共有しているのはその上のボード、つまり両者がたまたま欲しがったクリック可能なタイルのグリッドを持つ小さな `board` パッケージ `examples/macos/board.lisp` です。これはボードゲームのポリシーであり、だからこそサンプルのままです。

`examples/macos/listener.lisp` は言語そのものをウィンドウに載せます。`NSTextView` のトランスクリプト、Return キーが Lisp のクロージャである編集可能な `NSTextField`、そして読み取った式への `eval` — 印字された出力も取り込み、エラーはプロセスを終わらせずに一行として表示されます。ウィンドウと評価器は同じイメージなので、そこに打ち込んだ式が次のウィンドウを開けます。

ウィンドウがまったくなくても構いません。`appkit:status-item` はシステムのメニューバーにタイトルを置き、`appkit:menu` は項目が Lisp のクロージャであるメニューをそこにぶら下げます。`:dock nil` を付けるとプロセスには Dock アイコンもアプリケーションスイッチャの項目もなくなります。これがメニューバープログラムの姿で、そのときの出口が `appkit:quit` です。引数なしの `appkit:wait` はそれが起きるまでブロックします。

```console
CL-USER> (defvar *n* 0)
CL-USER> (defvar *item*
    (appkit:status-item "λ" :dock nil
                        :menu (appkit:menu
                               (list (list "Count" (lambda ()
                                                     (setq *n* (+ *n* 1))
                                                     (appkit:set-text *item*
                                                                      (format nil "λ ~a" *n*))))
                                     :separator
                                     (list "Quit" #'appkit:quit "q")))))
```

`examples/macos/menubar.lisp` はそこに時計を入れたものです。`appkit:timer` が 1 秒ごとにタイトルを書き替え、メニュー項目の 1 つはウィンドウを開きます。`listener.lisp` と同じ証明を、メニューバーから行うわけです。

| 関数 | 用途 |
|------|------|
| `appkit:window` | `(appkit:window title &key (width 480) (height 300) background dark)` — 表示済み・中央配置の `NSWindow` |
| `appkit:label` | `(appkit:label window text &key x y width height (size 13) color (align :left) bold)` — 矩形内で文字列を中央寄せした `NSTextField` ラベル |
| `appkit:button` | `(appkit:button window title &key x y (width 120) (height 32) on-click)` — `NSButton`。`on-click` は引数なしの関数 |
| `appkit:panel` | `(appkit:panel window &key x y width height fill (radius 0) (border 0) border-color)` — 塗りつぶした角丸の `NSBox` |
| `appkit:color` | `(appkit:color r g b &optional (alpha 1.0))` — 0-255 の成分から作る `NSColor` |
| `appkit:font` | `(appkit:font size &key bold)` — そのサイズのシステムフォント |
| `appkit:set-text` | `(appkit:set-text view text)` — ボタンならタイトル、それ以外のコントロールなら string value |
| `appkit:set-color` | `(appkit:set-color view color)` — パネルなら塗りつぶし色、それ以外のコントロールなら文字色 |
| `appkit:text` | `(appkit:text view)` — タイトルまたは string value を Lisp 文字列で |
| `appkit:on-click` | `(appkit:on-click view handler)` — ハンドラはボタン番号を取る。1 が左、3 が右 |
| `appkit:click` | `(appkit:click button)` — クリックと同じようにアクションを実行 |
| `appkit:timer` | `(appkit:timer seconds fn)` — 繰り返す `NSTimer`。`fn` が `nil` を返すと止まる |
| `appkit:menu` | `(appkit:menu items)` — `NSMenu`。項目は `(title handler)` と省略可能なキー同値、`:separator` は区切り線 |
| `appkit:status-item` | `(appkit:status-item title &key menu (dock t))` — システムのメニューバーの `NSStatusItem`。`:dock nil` はアクセサリポリシー |
| `appkit:quit` | `(appkit:quit)` — Cmd-Q と同じようにアプリケーションを終了する |
| `appkit:close` | `(appkit:close window)` — ウィンドウを閉じる (隠す)。値は有効なまま |
| `appkit:visible-p` | `(appkit:visible-p window)` — 画面上にあるかどうか |
| `appkit:wait` | `(appkit:wait &optional window)` — ウィンドウが閉じられるまで、あるいはアプリケーションが終了するまで呼び出し側スレッドをブロック |

座標系は AppKit のもので、原点はウィンドウの左下です。ラベルは与えられた矩形の中で垂直方向に中央寄せされ、それがタイルの中央に数字を置いてくれます。パネルはそのタイルそのもので、どちらもクリックに応えます:

```console
CL-USER> (defvar *board* (appkit:window "tiles" :width 200 :height 200
                                 :background (appkit:color 26 29 38) :dark t))
CL-USER> (defvar *tile* (appkit:panel *board* :x 20 :y 20 :width 34 :height 34
                               :fill (appkit:color 104 116 146) :radius 7))
CL-USER> (defvar *digit* (appkit:label *board* "3" :x 20 :y 20 :width 34 :height 34
                                :size 19 :align :center :bold t))
CL-USER> (appkit:on-click *tile*
    (lambda (button) (appkit:set-color *tile* (appkit:color 230 233 241))))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x00000008E34E7600>
CL-USER> (appkit:timer 1 (lambda () (appkit:set-text *digit* "4") nil))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x00000008E346ED00>
```

すべてのウィジェットはただの Objective-C オブジェクトなので、この層にないものは `objc:invoke` を 1 回呼べば手に入ります:

```console
CL-USER> (objc:invoke *win* "setBackgroundColor:"
    (objc:invoke "NSColor" "colorWithRed:green:blue:alpha:" 0.9 0.95 1.0 1.0))
NIL
CL-USER> (objc:invoke *win* "frame")
#(690.0 676.0 420.0 228.0)
```

## objc パッケージ

`objc` は LispWorks 8.1 の Objective-C インターフェースです。`objc:invoke`、`objc:invoke-bool`、`objc:invoke-into`、`objc:retain` / `objc:release` / `objc:autorelease`、自動解放プール、クラスとセレクタの変換、クラスを定義するマクロを LispWorks の名前とラムダリストのまま持ち、その Foundation 構造体は `cocoa` パッケージが持ちます。LispWorks のマニュアルに沿って書いたコードは、`objc` を use するパッケージの中で、インタプリタ、コンパイル済みクラス、`--native` 実行ファイルのどれでもそのまま動きます。マニュアルの名前に加えて、このパッケージはブロック、Objective-C 自身が報告するものを表すコンディション、そして独自の 4 つの関数 `objc:on-main`、`objc:data`、`objc:bytes`、`objc:objectp` を持ちます。すべての名前は[関数リファレンス](../reference/functions/objc.md)にあります。

```console
CL-USER> (defpackage :my-app (:use :cl :objc))
:MY-APP
CL-USER> (in-package :my-app)
:MY-APP
MY-APP> (defvar *s* (invoke "NSString" "stringWithUTF8String:" "hello world"))
*S*
MY-APP> (invoke *s* "rangeOfString:" "world")
(6 . 5)
MY-APP> (invoke-bool *s* "hasPrefix:" "hello")
T
MY-APP> (invoke-into 'string *s* "uppercaseString")
"HELLO WORLD"
MY-APP> (invoke-into 'string "NSString"
                     '("stringWithFormat:" (objc-object-pointer :int)
                       :result-type objc-object-pointer :variadic-num-of-fixed 1)
                     "The integer %d" 42)
"The integer 42"
```

`invoke` のレシーバは、オブジェクトポインタ、クラスポインタ、またはクラス名の文字列です。クラス名の文字列に送るとクラスメソッドの呼び出しになります。文字列が `NSString` のレシーバになることはないので、文字列のインスタンスメソッドは `objc:string-to-ns-string` が返す値に送ります。

### ランタイムは問い合わせられる対象

Objective-C が実行のその瞬間に決めることは、その瞬間に読み出せます。レシーバがある名前に
応えるか、実際のクラスは何か、メソッドがどんな型を宣言しているか、あるキーの下に何がある
か。

```console
MY-APP> (invoke-bool *s* "respondsToSelector:" "uppercaseString")
T
MY-APP> (objc-class-name (invoke (string-to-ns-string "hi") "class"))
"NSTaggedPointerString"
MY-APP> (objc-class-method-signature "NSString" "hasPrefix:")
(OBJC-OBJECT-POINTER SEL OBJC-OBJECT-POINTER)
OBJC-C++-BOOL
"B24@0:8@16"
MY-APP> (invoke (invoke *s* "valueForKey:" "length") "doubleValue")
11.0
```

2 行目はクラスクラスタを現行犯で捉えたものです。`string-to-ns-string` は `NSString` を
求め、値に応じて選ばれた非公開のサブクラスが返っています。3 行目は、メソッドの引数の型
(先頭はレシーバとセレクタ)、結果の型、ランタイムが保持するエンコーディングを返します。
`examples/macos/objc-runtime.lisp` は、この側面のパッケージ全体を 1 つの実行可能なファイル
にまとめたものです。文字列として持ち回り `respondsToSelector:` で守るセレクタ、辿るクラス
階層、読み出すメソッド自身の型エンコーディング、キー値コーディングと文字列キーによるソート、
`containsObject:` が呼び出す `isEqual:` を Lisp で定義したクラス、そして
`NSNotificationCenter` のオブザーバ。ウィンドウは開きません。

### 境界は AppKit ではない

このマシン上のあらゆるフレームワークが Objective-C ランタイムを話します。プロセスにリンク
されていないフレームワークもメッセージ 1 つで使えるようになります。`NSBundle` がそれをマップ
してクラスを登録するので、次のフォームからはそのクラス名が解決します。

```console
MY-APP> (invoke-bool (invoke "NSBundle" "bundleWithPath:"
                             "/System/Library/Frameworks/NaturalLanguage.framework")
                     "load")
T
MY-APP> (invoke-into 'string "NLLanguageRecognizer" "dominantLanguageForString:"
                     "これは日本語の文章です")
"ja"
```

ここでの依存管理はこれで全部です。マニフェストもクラスパスもダウンロードもありません。
`:modules` を付けた `objc:ensure-objc-initialized` も、フレームワークのバイナリや dylib
のパスから同じことをします。`examples/macos/system-frameworks.lisp` はそうして開かれる面を
1 つの実行可能なファイルにしたものです。Vision、NaturalLanguage、Core Image、そして音声合成
— どれも誰かが先に Lisp 向けにラップしたものではありません。中心にあるのは往復です。Lisp
の文字列を Core Image が画像に描き、それを Vision が読み戻し、機械が与えられたとおりに読んだ
かどうかを `equal` が判定します。こちらもウィンドウを開かず、そして無音です。音声はスピー
カーではなく AIFF ファイルに合成されるためです。

### 宣言型による変換

`objc:invoke` はシグネチャを推測しません。ランタイムはすべてのメソッドを完全に記述しており (`initWithContentRect:styleMask:backing:defer:` なら `@68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64`)、`invoke` はそのエンコーディングを読んで (リスト形式のメソッドは型を自分で述べます)、それに従って各引数と結果を変換します。引数として渡した文字列やベクタは呼び出しの間だけ存在します。

| 宣言型 | 引数として渡せるもの | 結果 |
|--------|----------------------|------|
| `id` | オブジェクトポインタ、`nil`、文字列 (`NSString` になる)、ベクタ (要素も同様に変換した `NSArray` になる) | `objc:objc-object-pointer` または `nil` |
| `Class` | クラスポインタまたはクラス名 | `objc:objc-class` |
| `SEL` | セレクタまたはその名前 | `objc:sel` |
| `char *` | 文字列、外部ポインタ、アドレス | 文字列 |
| `BOOL` / `_Bool` | `t`、`nil`、整数 | `1` か `0` |
| 整数 | 整数 (符号なし 64 ビットは 2^64-1 まで) | 整数 |
| `float` / `double` | 実数 | 倍精度浮動小数点数 |
| `NSRect` / `NSPoint` / `NSSize` | `#(x y width height)` / `#(x y)` / `#(width height)`、またはそれを指す外部ポインタ | 倍精度のベクタ |
| `NSRange` | `(location . length)`、またはそれを指す外部ポインタ | コンス |
| その他の構造体 | メモリ順に並べたフィールドのベクタ | フィールドのベクタ |
| ポインタ | 外部ポインタ、アドレス、`nil`、オブジェクトポインタ | 宣言された型を指す外部ポインタ |

結果の `BOOL` は LispWorks と同じく `1` か `0` なので、条件判定には `objc:invoke-bool` を使います。結果の `NSString` はほかのオブジェクトと同じくポインタです。`objc:invoke-into` はさらに変換します。`'string` はそれを Lisp 文字列に、`'array` と `'(array string)` は `NSArray` をベクタにします。第一引数に渡したベクタやコンスには、構造体や配列の要素が格納されます。外部オブジェクトも同様です ([外部オブジェクト](#foreign-objects-the-fli-package))。

```console
MY-APP> (invoke *s* "hasPrefix:" "hello")
1
MY-APP> (invoke-into 'string *s* "substringWithRange:" (cons 0 5))
"hello"
MY-APP> (invoke (invoke "NSValue" "valueWithRect:" #(10 20 300 200)) "rectValue")
#(10.0 20.0 300.0 200.0)
MY-APP> (invoke-into '(array string) "NSArray" "arrayWithArray:" #("x" "y"))
#("x" "y")
```

レシーバにないメソッド、引数の個数違い、宣言型に合わない引数は、何かを送る前に `error` になり、クラッシュにはなりません。メソッドがない場合のメッセージには、ランタイムがレシーバについて答えるクラスが入ります:

```console
MY-APP> (invoke *s* "frobnicate")
Error: No method "frobnicate" for object #<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000000A9ACE43C0>, class "__NSCFString".
MY-APP> (invoke *s* "length" 3)
Error: objc:invoke: length takes 0 argument(s), got 1
```

`performSelector...` メッセージの答えは捨てられます (その型はターゲットメソッドのもので、エンコーディングには現れません)。ブロックの引数にはブロックを渡します。素の関数は受け付けません (後述の[ブロック](#blocks))。共用体やビットフィールドの引数は、名前を挙げて拒否されます。

### 宣言が呼び出しのすべてではない唯一のケース

ランタイムが印を付けてくれないのが可変長引数 (variadic) のメソッドです。
`+[NSArray arrayWithObjects:]` と `+[NSArray arrayWithObject:]` はどちらも `@@:@` とバイト単位で同一に宣言されており、両者を区別する情報はどこにもありません。しかし Apple シリコンではこの違いが呼び出しそのものです。可変長引数はスタックで渡され、固定引数はレジスタで渡されるからです。

そこでこの一群は名前で識別します。nil 終端のコンストラクタ (`arrayWithObjects:`、`initWithObjects:`、`setWithObjects:`、`orderedSetWithObjects:`、`dictionaryWithObjectsAndKeys:`、`initWithObjectsAndKeys:`) と、書式文字列の一族 (`stringWithFormat:`、`initWithFormat:`、`localizedStringWithFormat:`、`stringByAppendingFormat:`、`appendFormat:`、`predicateWithFormat:`、`raise:format:`) です。名前で呼ぶと、いずれも宣言された引数の個数を超えていくつでも引数を取り、それぞれの型は値 (オブジェクト、文字列、整数、浮動小数点数) から決まります。`nil` 終端子はバインディングが付けます。呼び出し側が書くものではありません。

```console
MY-APP> (invoke (invoke "NSArray" "arrayWithObjects:" "a" "b" "c") "count")
3
MY-APP> (invoke-into 'string "NSString" "stringWithFormat:"
                     "%@ has %ld items, %.1f%% full" "cache" 3 62.5)
"cache has 3 items, 62.5% full"
```

それ以外の可変長引数メソッドは、プログラム自身が宣言したものも含めて、上の最初の例の `stringWithFormat:` のようにリスト形式と `:variadic-num-of-fixed` で呼びます。`arrayWithObjects:count:` はそもそも可変長引数ではありません。本物の配列と個数を取る、任意サイズのコレクションを作る固定引数の方法です。

### 値

オブジェクトは `objc:objc-object-pointer`、クラスは `objc:objc-class` (オブジェクトポインタでもある)、セレクタは `objc:sel` として返ります。どれも `structure-object` ではありません。一つのオブジェクトに対する二つの答えは `eq`、`eql`、`equal`、`equalp` のいずれでも等しく、どのハッシュテーブルでも互いに見つかります。ポインタは LispWorks と同じく `#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600000C04010>` と表示します。表示するのはアドレスだけで、オブジェクトからは何も読みません。`objc:objectp` はクラスを含むすべてのオブジェクトポインタについて真で、セレクタを含むそれ以外のものについては偽です:

```console
MY-APP> (eq (invoke *s* "self") *s*)
T
MY-APP> (objc:objectp *s*)
T
MY-APP> (objc:objectp (coerce-to-objc-class "NSString"))
T
MY-APP> (objc:objectp (coerce-to-selector "length"))
NIL
MY-APP> (objc:objectp "hello")
NIL
```

### 外部オブジェクト: `fli` パッケージ

呼び出し元が所有するメモリを埋めるメソッド (`invoke-into` の構造体、書き込み先の `int *` や `BOOL *`) には外部オブジェクトを渡します。
`fli:with-dynamic-foreign-objects` は本体の実行中だけ有効な、指定した型の外部オブジェクトを確保します。
`fli:dereference` はその値を読み、`setf` で書きます。`fli:foreign-slot-value` は構造体のスロットを読み書きします
(名前のリストで入れ子の構造体の中を指せます)。次はマニュアルそのままの形です。

```console
MY-APP> (fli:with-dynamic-foreign-objects ((rect cocoa:ns-rect))
          (invoke-into rect (invoke (invoke "NSView" "alloc") "initWithFrame:" #(0 0 640 480))
                       "frame")
          (fli:foreign-slot-value rect '(:size :width)))
640.0
MY-APP> (fli:with-dynamic-foreign-objects ((result-value :int))
          (invoke (invoke "NSScanner" "scannerWithString:" "42 apples") "scanInt:" result-value)
          (fli:dereference result-value))
42
MY-APP> (fli:size-of 'cocoa:ns-rect)
32
```

外部オブジェクトはその型を取るところならどこにでも渡せます。ポインタとして渡すほか、構造体としても渡せ、その場合は内容がコピーされます。
Objective-C が返すポインタ (`void *` の結果、コールバックの `BOOL *stop`) はすべて、宣言された型を指す外部ポインタです。
そのためブロックは `(setf (fli:dereference stop) t)` で列挙を止められます。`fli:allocate-foreign-object` と
`fli:free-foreign-object` は明示的に確保と解放をします。メモリは `--native` を含むどのターゲットでもプロセスのヒープです。
集成体を指すポインタは Lisp の値になりません。参照するとシグナルします。`:copy-foreign-object` に `nil` を渡すとそれを指すポインタを、
`t` を渡すとコピーを返します。外部ポインタは `ffi:` のポインタではありません。`fli:pointer-address` が両者の受け取るアドレスを返します。

### 所有権: ポインタは保持している参照だけを解放する

すべての送信はメインスレッド上で専用の自動解放プールの中で行われるため、送信が autorelease したオブジェクトは、ポインタ値が先に参照を取らない限り送信から戻った時点で消えています。ポインタ値はオブジェクトの結果すべてについて参照を取り、`alloc` / `new` / `copy` / `mutableCopy` / `init` のメソッドが返す参照はそのまま引き取ります。ポインタ値は、コレクタに回収されるときにまだ保持している参照をメインスレッド上で解放します。つまり保持しているウィンドウや文字列は保持している限り有効で、手で解放するものはありません。`objc:retain` はプログラムが解放すべき参照を加えます。`objc:release` と `objc:autorelease` はポインタが保持する参照を一つ手放し、保持していなければシグナルします。そのため、マニュアルの規則どおり所有するものを解放するコードが、コレクタの解放と二重に解放することはありません。`(objc:invoke p "release")` も同じ計数を通ります。

```console
MY-APP> (defvar *o* (alloc-init-object "NSObject"))
*O*
MY-APP> (retain-count (retain *o*))
2
MY-APP> (release *o*)
NIL
MY-APP> (release *o*)
NIL
MY-APP> (release *o*)
Error: objc:release: #<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600000C04010> holds no reference this program can give up
MY-APP> (with-autorelease-pool ()
          (invoke-into 'string (autorelease (string-to-ns-string "pooled")) "description"))
"pooled"
```

プールは Lisp 側で管理します。各送信がメインスレッドで自分のプールを積んで降ろすため、本物の `NSAutoreleasePool` は二つの送信にまたがれません。

ウィンドウには規則が一つあります。`objc:invoke` で直接作ったウィンドウには、`appkit:window` がしているように `setReleasedWhenClosed:` に `nil` を送っておく必要があります。さもないと、閉じたときにポインタがまだ保持している参照が解放されます。

### クラスの定義

`objc:define-objc-class` は Objective-C クラスを実装する CLOS クラスを定義し、
`objc:define-objc-method` / `objc:define-objc-class-method` は本体を Lisp で書いたメソッドを与えます。
マニュアルの 1.4 節の例はそのまま動きます。`(:unsigned :int)` を二つ取るメソッド、
`(current-super)` に送るメソッドを持つサブクラス、`objc:define-objc-struct` で宣言した構造体を返すメソッドです。

```console
MY-APP> (define-objc-class my-object ()
          ((slot1 :initarg :slot1 :initform nil))
          (:objc-class-name "MyObject"))
MY-OBJECT
MY-APP> (define-objc-method ("areaOfWidth:height:" (:unsigned :int))
            ((self my-object)
             (width (:unsigned :int))
             (height (:unsigned :int)))
          (* width height))
"areaOfWidth:height:"
MY-APP> (define-objc-class my-special-object (my-object)
          ()
          (:objc-class-name "MySpecialObject"))
MY-SPECIAL-OBJECT
MY-APP> (define-objc-method ("areaOfWidth:height:" (:unsigned :int))
            ((self my-special-object)
             (width (:unsigned :int))
             (height (:unsigned :int)))
          (* 4 (invoke (current-super) "areaOfWidth:height:" width height)))
"areaOfWidth:height:"
MY-APP> (invoke (alloc-init-object "MySpecialObject") "areaOfWidth:height:" 6 7)
168
MY-APP> (define-objc-struct (pair (:foreign-name "_Pair"))
          (:first :float)
          (:second :float))
PAIR
MY-APP> (define-objc-method ("pair" (:struct pair) result-pair) ((this my-object))
          (setf (fli:foreign-slot-value result-pair :first) 1f0
                (fli:foreign-slot-value result-pair :second) 2f0))
"pair"
MY-APP> (invoke (alloc-init-object "MyObject") "pair")
#(1.0 2.0)
```

メソッドの引数と結果には、宣言で書けるどの型でも使えます。あらゆる幅の整数、`:float` / `:double`、
構造体 (`invoke` がそれについて使う Lisp の値)、`t` / `nil` になる `objc:objc-bool` / `:boolean`、
オブジェクト、クラス、セレクタ、ポインタ (宣言された型を指す外部ポインタ) です。構造体は Lisp の値で返すか、
それを指す外部ポインタで返します (内容がコピーされます)。上の `result-pair` のようにキーワードでない結果スタイルを
書くと、その変数に結果の型の外部オブジェクトが束縛され、本体がそれを埋めます。オブジェクト引数は受け取るときに変換でき
(`(arg objc-object-pointer string)`、`array`、`(array string)`)、オブジェクトとして返した文字列やベクタは
`NSString` / `NSArray` になります。Objective-C クラスを名付けず継承もしないクラスはミックスインで、
そのメソッドは名付けるサブクラスそれぞれに入ります。メソッド本体のエラーは `objc: error in a callback: ...` と
表示され、メソッドはゼロを返します。呼び出した Objective-C のフレームまで巻き戻ることはありません。
その上にシグナル先となる Lisp のフレームがないためです。`define-objc-method` を評価し直すとメソッドが
束縛し直されるので、REPL でハンドラを書き直しながら試せます。

インスタンスは `objc:standard-objc-object` です。`make-instance` は Objective-C オブジェクトを確保して初期化し
(`init`、または渡した `:init-function`)、Objective-C 側が確保したオブジェクトにも Lisp オブジェクトが作られます。
そのためどちらも `objc:objc-object-from-pointer` で逆にたどれます。`objc:objc-object-var-value` は
`(:objc-instance-vars ...)` で宣言したインスタンス変数を読みます。Lisp オブジェクトは Objective-C オブジェクトの
参照カウントが 0 になるまで生き続けます (`make-instance` が取った参照はプログラムが `objc:release` するものです)。
0 になると `dealloc` の中で `objc:objc-object-destroyed` が実行され、`copy` で作った複製には
`objc:objc-object-copied` が呼ばれます。

Cocoa から Lisp を呼び出すのは、こうしたクラスです。ボタンはアクションをターゲットに送ります。次の例では、ターゲットがインスタンスで、アクションが Lisp のメソッドです:

```console
MY-APP> (define-objc-class my-target ()
          ((clicks :initform 0 :accessor clicks))
          (:objc-class-name "MyTarget"))
MY-TARGET
MY-APP> (define-objc-method ("clicked:" :void) ((self my-target) (sender objc-object-pointer))
          (incf (clicks self))
          (format t "clicked ~a~%" (invoke-into 'string sender "title")))
"clicked:"
MY-APP> (defvar *target* (make-instance 'my-target))
*TARGET*
MY-APP> (defvar *window* (appkit:window "target" :width 200 :height 80))
*WINDOW*
MY-APP> (defvar *go* (appkit:button *window* "Go"))
*GO*
MY-APP> (invoke *go* "setTarget:" *target*)
NIL
MY-APP> (invoke *go* "setAction:" "clicked:")
NIL
MY-APP> (appkit:click *go*)
clicked Go
NIL
MY-APP> (clicks *target*)
1
```

AppKit はターゲットを弱参照で保持するので、ターゲットを生かしておくのは `*target*` です。`appkit` のウィジェットはまさにこの方法で作られており、`metal` のプログラムや `scene` の描画面も同じです。描画面は `NSView` のサブクラスで、その `mouseDown:` / `mouseDragged:` / `scrollWheel:` が Lisp のメソッドです。通知の監視もメソッドで行います:

```console
MY-APP> (define-objc-class watcher ()
          ((seen :initform nil :accessor seen))
          (:objc-class-name "Watcher"))
WATCHER
MY-APP> (define-objc-method ("noticed:" :void) ((self watcher) (note objc-object-pointer))
          (push (invoke-into 'string note "name") (seen self)))
"noticed:"
MY-APP> (defvar *w* (make-instance 'watcher))
*W*
MY-APP> (cocoa:add-observer *w* "noticed:" :name "Ping")
NIL
MY-APP> (invoke (invoke "NSNotificationCenter" "defaultCenter")
                "postNotificationName:object:" "Ping" nil)
NIL
MY-APP> (seen *w*)
("Ping")
```

### ブロック

ブロックは Cocoa がクロージャを受け取る形です。比較関数、列挙の関数、完了ハンドラがそうです。`objc:make-objc-block` は Lisp の関数とシグネチャからブロックを作ります。シグネチャは呼ぶ側が示します。メソッドのエンコーディングはブロックを取ることしか表さず、そのブロックが何を取るかは表さないためです。ブロックを渡す場所に素の関数を渡すと、推測せずにシグナルします。シグネチャはリスト形式のメソッドと同じ型で書いた `(result-type (argument-type*))`、または `objc:define-objc-block-type` で付けた名前です。引数はメソッドの本体に渡る引数と同じように変換されて関数に渡り、関数の値は逆向きに変換されます。`objc:with-objc-block` は本体の間だけ有効なブロックを作り、どの脱出でも解放します。非同期の処理にもこれで足ります。ブロックを保持する呼び出し先はコピーを持ち、そのコピーが関数を生かすからです。`objc:call-objc-block` は、誰が作ったブロックでも呼べます。

```console
MY-APP> (defvar *words* (invoke "NSArray" "arrayWithObjects:" "pear" "fig" "apple"))
*WORDS*
MY-APP> (with-objc-block (compare '(:long-long (objc-object-pointer objc-object-pointer))
                                  (lambda (a b)
                                    (let ((x (ns-string-to-string a))
                                          (y (ns-string-to-string b)))
                                      (cond ((string< x y) -1) ((string> x y) 1) (t 0)))))
          (invoke-into '(array string) *words* "sortedArrayUsingComparator:" compare))
#("apple" "fig" "pear")
MY-APP> (with-objc-block (each '(:void (objc-object-pointer (:unsigned :long-long)
                                        (:pointer objc-c++-bool)))
                               (lambda (word index stop)
                                 (format t "~a ~a~%" index (ns-string-to-string word))
                                 (when (= index 1) (setf (fli:dereference stop) t))))
          (invoke *words* "enumerateObjectsUsingBlock:" each))
0 pear
1 fig
NIL
MY-APP> (defvar *add* (make-objc-block '(:int (:int :int)) (lambda (a b) (+ a b))))
*ADD*
MY-APP> (call-objc-block '(:int (:int :int)) *add* 3 4)
7
MY-APP> (free-objc-block *add*)
NIL
```

C 関数もブロックを取ります (libdispatch の関数など)。このパッケージが持つ LispWorks の外部言語インターフェースの一部、`fli:define-foreign-function` で同じ型を使って宣言します:

```console
MY-APP> (fli:define-foreign-function (dispatch-queue-create "dispatch_queue_create")
            ((label objc-c-string) (attributes :pointer))
          :result-type objc-object-pointer)
DISPATCH-QUEUE-CREATE
MY-APP> (fli:define-foreign-function (dispatch-async "dispatch_async")
            ((queue objc-object-pointer) (work objc-at-question-mark))
          :result-type :void)
DISPATCH-ASYNC
MY-APP> (defvar *queue* (dispatch-queue-create "com.example.work" nil))
*QUEUE*
MY-APP> (defvar *done* nil)
*DONE*
MY-APP> (with-objc-block (work '(:void ()) (lambda () (setq *done* t)))
          (dispatch-async *queue* work))
NIL
MY-APP> (sleep 0.1)
NIL
MY-APP> *done*
T
```

ブロックは呼んだスレッドで実行されます。Foundation は比較関数や列挙の関数を、送信したスレッドで呼びます。送信はすべてメインスレッドで実行されるため、それはメインスレッドです。シリアルキューは処理を、`NSURLSession` は完了ハンドラを、libdispatch のワーカーでプログラムと並行して実行します。そこでの関数は、スペシャル変数についてプログラムのスレッドの束縛ではなく大域値を見ます (クロージャ自身が捕捉したものは別です)。`rontolisp:make-thread` で始めたスレッドと同じです。`--native` 実行ファイルは別のスレッドで Lisp を実行できません。別のスレッドから呼ばれた `void` のブロックは、プログラムの次の `sleep` を待ってメインスレッドで実行されます。値を返すブロックは拒否され、そのことが表示されて 0 を返します。そのため、ブロックを待つプログラムは次のように `sleep` で待てば、どのターゲットでも同じように動きます:

```console
MY-APP> (defvar *status* nil)
*STATUS*
MY-APP> (with-objc-block (handler '(:void (objc-object-pointer objc-object-pointer
                                           objc-object-pointer))
                                  (lambda (data response error)
                                    (declare (ignore data error))
                                    (setq *status* (invoke response "statusCode"))))
          (invoke (invoke (invoke "NSURLSession" "sharedSession")
                          "dataTaskWithURL:completionHandler:"
                          (invoke "NSURL" "URLWithString:" "https://example.com/")
                          handler)
                  "resume"))
NIL
MY-APP> (loop until *status* do (sleep 0.05))
NIL
MY-APP> *status*
200
```

### 例外と NSError

呼び出しの中で送出された Objective-C の例外 (範囲外のインデックス、オブジェクトが必要な位置の `nil`、`raise` を送られた `NSException` など) は、そのスレッドで実行中の最も内側の `objc:invoke` (または C 関数、ブロックの呼び出し) から `objc:objc-exception` としてシグナルされ、プログラムは続行します。`objc:objc-exception-name`、`objc:objc-exception-reason`、`objc:objc-exception-object` は、例外の名前、理由 (なければ `nil`)、送出されたオブジェクトを返します。このオブジェクトの参照はコンディションが保持します:

```console
MY-APP> (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" 5)
          (objc-exception (e) (list (objc-exception-name e) (objc-exception-reason e))))
("NSRangeException" "*** -[__NSArray0 objectAtIndex:]: index 5 beyond bounds for empty array")
```

送出から呼び出しまでの間にある Objective-C のフレームは、Objective-C 自身の `@catch` と同じく後始末を実行しながら巻き戻されます。Cocoa が自分で捕捉する例外は Lisp に届きません。Lisp で定義したメソッドやブロックの中で送出され、そこで処理されなかった例外はコールバック内のエラーになり、表示されてメソッドは 0 を返します。

最後の引数 `NSError **` で失敗を報告するメソッドは `objc:invoke-with-error` で呼びます。この引数は `objc:invoke-with-error` が渡します。結果が失敗 (`nil`、`NO` または 0) を示し、メソッドがエラーを書き込んだ場合は `objc:ns-error` をシグナルします。そのリーダー `objc:ns-error-domain`、`objc:ns-error-code`、`objc:ns-error-description`、`objc:ns-error-object` は、ドメイン、コード、ローカライズされた説明、`NSError` を返します。それ以外の場合は `objc:invoke` と同じ値を返します:

```console
MY-APP> (handler-case
            (invoke-with-error (invoke "NSFileManager" "defaultManager")
                               "attributesOfItemAtPath:error:" "/no/such/file")
          (ns-error (e) (list (ns-error-domain e) (ns-error-code e))))
("NSCocoaErrorDomain" 260)
```

### バイト列: `objc:data` と `objc:bytes`

メモリブロックは Cocoa ではありふれたものですが、それに対応する Lisp の値はありません。そこでこのパッケージはメモリブロックを `NSData` にします。`objc:data` は、パックバッファ (任意ランクのパック float 配列、パックされた `(unsigned-byte 8|16|32)` ベクタ) のバイト列、または文字列の UTF-8 を持つ `NSMutableData` を返します。並びは `write-sequence` が書くものとまったく同じで、リトルエンディアンの行優先です。それ以外の値を渡すとシグナルします。あとは `[data bytes]` が `void *` 引数の求めるポインタになり、`[data mutableBytes]` は呼び出し先に渡せる書き込み領域になります。`objc:bytes` は `NSData` の内容を新しい `(unsigned-byte 8)` ベクタとして読み戻します。

```console
MY-APP> (objc:bytes (objc:data (make-array 2 :element-type 'single-float
                                            :initial-contents '(1.0 2.0))))
#(0 0 128 63 0 0 0 64)
MY-APP> (objc:bytes (invoke *s* "dataUsingEncoding:" 4))
#(104 101 108 108 111 32 119 111 114 108 100)
MY-APP> (handler-case
            (invoke-with-error "NSJSONSerialization" "JSONObjectWithData:options:error:"
                               (objc:data "nope") 0)
          (ns-error (e) (ns-error-description e)))
"The data couldn’t be read because it isn’t in the correct format."
```

バイト列と `invoke-with-error` がそろうと GPU に手が届きます。Metal はほぼ全面が Objective-C の API なので、`objc:invoke` だけで何も足さずに駆動できます。

### `metal` パッケージ

どの Metal プログラムも同じように書く定型 — ウィンドウのコンテンツビュー上の
`CAMetalLayer`、デバイス、コマンドキュー、レンダーパス、ドローアブル、present と commit、
そしてシェーダ・パイプライン・バッファのヘルパ — が組み込みの **`metal`** パッケージです。
`appkit` と同じくインタプリタに同梱され、初回使用時に読み込まれます。意図的に含めていない
のはシェーダのソース、形状、描画コールです。それらはプログラム側のものです。

```console
CL-USER> (defvar *win* (appkit:window "metal" :width 640 :height 400 :dark t))
CL-USER> (defvar *ctx* (metal:attach *win* :clear '(0.05 0.06 0.09 1.0) :depth t))
CL-USER> (defvar *pipe* (metal:pipeline *ctx* (metal:library *ctx* *shaders*) "vertex_main" "fragment_main"))
CL-USER> (metal:run *ctx*
    (lambda (encoder)
      (objc:invoke encoder "setRenderPipelineState:" *pipe*)
      (objc:invoke encoder "drawPrimitives:vertexStart:vertexCount:" metal:+triangle+ 0 3)))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000000BBF47A7C0>
```

Metal が必要とするように見える唯一の C 関数 `MTLCreateSystemDefaultDevice()` は回避できま
す。`CAMetalLayer` の `preferredDevice` はプロパティで同じデバイスを返すからで、パッケージ
全体はその事実の上に立っています。シェーダは Lisp の文字列から実行時にコンパイルされ、コン
パイルに失敗したシェーダは `objc:ns-error` をシグナルします。その説明は Metal コンパイラ自身の
診断 (キャレット付き) です。`metal:buffer` は数値を一度だけ GPU にコピーし、毎フレーム書き直す
形状には `metal:shared-buffer` と `metal:upload`、Metal がインラインで受け取りたがる小さな値には
`metal:uniform` を使います。パックド単精度配列はバッファのバイト列そのものなので、`linalg`
の行列も `geom:mesh` も一切変換なしに GPU へ届きます。全体は
[関数リファレンス](../reference/functions/metal.md)にあります。

`metal` は単独で成立します。`examples/macos/metal-triangle.lisp` は WebGL の hello world
を、`examples/macos/metal-cube.lisp` は陰影付きの回転する立方体を、
`examples/macos/metal-robot-arm.lisp` は自分で逆運動学を解いてクリックした先へ手を伸ばすロ
ボットアームを、`examples/macos/metal-pagoda-garden.lisp` はボクセルの庭 — 鯉の池の上に立つ
五重塔、舞い散る桜、クリックで訪れる夜 — を描きます。その 1 万 3 千個のボクセルは 1 個の立方
体を 1 万 3 千回描いたもので、頂点関数が `vertex_id` を 36 で割ってどのボクセルの上にいるかを
求めます。4 本とも `metal` を直接使っており、`geom` も `scene` も使っていません (OpenGL は逆で、
射程外のままです。`glClear` などは素の C 関数であり、`objc_msgSend` は届きません)。

`metal` の 1 つ上の段が **`scene`** パッケージです。`geom` ソリッドの 3D ビューアで、カメラ・
グリッド・フレームループがすでに書かれているので、モデル化した機械はウィンドウまで 3 行です。
[ソリッドモデリングガイド](solid-modeling.md#seeing-it-the-scene-viewer)を参照してください。

### スレッド: すべてはメインスレッドで起きる

AppKit はプロセスの最初のスレッドのものであり、すべての `objc:invoke` は自分でそこへ移動します。移動は同期的なので、値は呼び出し側に返ってきます。複数の送信から成るウィジェットは `objc:on-main` で包むと移動が 1 回で済み、`appkit` の関数はそうしています。`objc:on-main` は関数をメインスレッドで呼んでその値を返し、関数がシグナルしたエラーは呼び出し側で改めてシグナルします。すでにメインスレッド上で動いている関数 (ボタンのハンドラ) は送信をインラインで実行するので、コールバックから自由に GUI を呼び戻せます。

```console
MY-APP> (objc:on-main (lambda () (+ 1 2)))
3
```

最初の `appkit:` 呼び出しは、スレッド 0 を AppKit 自身のイベントループ (`-[NSApplication run]`。誰もブロックせずにそこで開始します) に渡します。ウィンドウがそもそもクリックに応答するのはこれによるもので、プロセスがフォーカスを取りアプリケーションスイッチャに現れるのもこのためです。開始するのは `appkit` 層であり、汎用バインディングである `objc` ではありません。したがって `appkit:` 関数を一度も呼ばないプログラムが生の `objc:invoke` だけで作ったウィンドウは、描画はされても何にも反応しません。ウィンドウは `appkit:window` で作ってください。

Lisp で定義したメソッドやブロックがメインスレッドで呼ばれるとき、それはインタプリタの *グローバル* な動的束縛で動きます。REPL スレッドでの special 変数の `let` 束縛はそこから見えません。

### LispWorks との違い

`invoke` は構造体を外部メモリに書かず、Lisp の値 (ベクタかコンス) で返します。マニュアルのやり方は外部オブジェクトへの `objc:invoke-into` で、どちらも使えます。`fli` は LispWorks の外部言語インターフェースのうちマニュアルの例が使う部分 (`define-foreign-function`、外部オブジェクト、ポインタ) で、外部オブジェクトはスタックではなく `calloc` のメモリです。LispWorks はブロックを `objc` ではなく外部言語インターフェースで作ります。`objc:make-objc-block` などの名前はこのパッケージ独自のものです。どの関数も最初の使用時にランタイムを開くため、`objc:ensure-objc-initialized` を先に呼ぶ必要はありません。上の表にある可変長引数メソッド (`stringWithFormat:`、`arrayWithObjects:` など) は文字列形式でも呼べます。追加の引数はそれぞれ値から型を決め、末尾に `nil` を加えます。LispWorks は Objective-C の例外でプロセスを終了させ、`NSError` の補助もありません。`objc:objc-exception`、`objc:ns-error`、`objc:invoke-with-error` はこのパッケージ独自のもので、`objc:on-main`、`objc:data`、`objc:bytes`、`objc:objectp` も同様です。

## ネイティブバイナリ

`rontolisp` バイナリはビルド時に登録された `objc_msgSend` の形の固定テーブルを提供します — `appkit`、`metal`、`scene` の各層が送るすべての形に加え、AppKit と Foundation の中核クラスで最も多い 60 の形で、それらが宣言するメソッドの 10 のうち 9 に届きます。テーブルにないメソッドは追加すべきエントリをそのまま示してシグナルします:

```text
objc: someRareSelector:: the shape void(void*,void*,jshort) has no foreign-call stub
in this binary; register it under foreign.downcalls in reachability-metadata.json and rebuild
```

JVM は事前に何も登録せずどんな形でもバインドするので、バイナリを作る前にプログラムが何を送るかを知る場所は `java -jar` です。

`objc:define-objc-method` で定義したメソッドはそれぞれの形のアップコールで、同じように登録します。バイナリは上のクラスの例の形、`appkit`、`metal`、`scene` の各層が定義するメソッドの形、Lisp で定義したどのクラスにも入る三つのメソッドの形を扱い、それ以外の形の定義は `foreign.upcalls` に追加すべきエントリを示して拒否します。ブロックも同じくアップコールです。バイナリは上のブロックの形 (と、比較関数、処理の単位、三つのオブジェクトを取る完了ハンドラの形) を扱い、それ以外の形のブロックは作る時点で拒否します。`java -jar` と `--native` 実行ファイルはどんな形でも受け付けます。

可変長引数の呼び出しは別個の登録になるため、バイナリはその有界なグリッドも提供します。宣言された引数を超えて 11 個まで (バインディングが付ける `nil` 終端子を含めて 12 個)、うち先頭 3 個までは数、残りはオブジェクトです。これより長い、あるいは数がこれより多いリストは同じようにシグナルします。

## JVM クラスへのコンパイル

同じプログラムは `.class` や `.jar` にコンパイルでき、素の `java` ランチャで動きます。ランチャはプロセスの最初のスレッドを自分でイベントループに留めます:

```console
$ rontolisp examples/macos/counter.lisp -o Counter.class --class-name Counter
$ java Counter
$ rontolisp examples/macos/counter.lisp -o counter.jar
$ java -jar counter.jar
```

クラスは `objc` パッケージと使用する `appkit` ウィジェットを抱え、バインディング全体 (`am.ik.objc`、クラス名に合わせてリネーム済み) は `Counter$Objc*.class` ファイルとしてクラスの隣 (または jar の中) に書き出されます。それらのファイルがあれば、`java.lang.foreign` を持つ JVM (コンパイラが動いたものか、それより新しいもの) 以外には何も必要ありません。素の `.class` を `--enable-native-access=ALL-UNNAMED` なしで実行すると JDK の restricted-method 警告が一度出ますが動作します。`.jar` はマニフェストでネイティブアクセスを有効にします。`rontolisp` バイナリもそうしたプログラムをコンパイルできます。`.wasm` 出力は拒否され (`Cannot compile: appkit:window ...`)、今後もそうです: そちら側には foreign function API も AppKit もありません。

jar は設定なしで GraalVM ネイティブイメージにもビルドできます (`native-image -jar counter.jar`)。バインディングが必要とするネイティブイメージ用メタデータ、つまり `rontolisp` バイナリが扱うのと同じメッセージ形状の表を jar 自身が持っています (前述の[ネイティブバイナリ](#the-native-binary))。イメージではプログラムの `main` がプロセスの最初のスレッドで始まるので、`rontolisp` バイナリと同じく、`main` 自身がそのスレッドをイベントループに渡し、プログラムを別のスレッドで実行します。

## ネイティブ実行ファイル

`--native` は同じプログラムを JVM を必要としない約 2.4 MB の実行ファイル 1 つにコンパイルします (Apple シリコン。そうした Mac では既定のターゲット、または `--native-target macos-aarch64`):

```console
$ rontolisp examples/macos/counter.lisp --native -o counter
$ ./counter
```

実行ファイルのランナー自身がバインディングです。ランナーが Objective-C ランタイムを直接呼ぶので、ランタイムが記述するセレクタはどれでも送れます — `rontolisp` バイナリのような固定の形の表はありません — し、送信 1 回はおよそ 1 マイクロ秒です。プログラムは AppKit が求めるプロセスの最初のスレッドで動くので、`objc:on-main` はただの呼び出しで、スレッドの移動はありません。プログラムが `sleep` で待つ間 (`appkit:wait` がそうします) ウィンドウはイベントを処理してタイマーを動かし、ボタンのクロージャはその待ちの中で実行されます。標準入力を読むプログラムでは、読み込みが返るまでウィンドウが応答しません。

値の規則はほかと同じです。ポインタは自分の参照を保持し、ガベージになるとそれを解放します。一つのオブジェクトに対する二つの答えは、アドレスで比較されて `eq` になります。実行ファイルでは `objc:data` は `bfloat16` 配列と量子化行列を受け付けません。

## 制限

- macOS のみ: インタプリタ (`java -jar`、または `rontolisp` バイナリ)、コンパイル済み `.class` / `.jar`、Apple シリコン向けの `--native` 実行ファイル。`.wasm` は不可で、`objc:` / `appkit:` の参照はそれ以外のすべての WASM 出力でコンパイルエラーです。
- アプリケーションバンドルのないプロセスには Dock アイコンもメニューバーもありません。Cmd-Q はなく、最後のウィンドウを閉じても終了しません — REPL がプロセスです。
- `rontolisp` バイナリでは、メッセージ、Lisp で定義したメソッド、ブロックのいずれも、バイナリが登録した形しか受け付けません。`java -jar` と `--native` 実行ファイルはどんな形でも受け付けます。
- `--native` 実行ファイルが別のスレッドから呼ばれたブロックを実行するのは、そのブロックが何も返さない場合だけで、実行はプログラムの次の `sleep` の時点です。
- 上の表にない可変長引数メソッドはリスト形式で呼びます。ランタイムには、それを固定引数の同形のメソッドと区別する手段がありません。
- Apple シリコン向け。Intel Mac では 2 レジスタより広い構造体は `objc_msgSend_stret` で返され、バインディングはそれを選びますが動作確認はしていません。また、呼び出しの中の Objective-C の例外は今もプロセスを終了させます。
