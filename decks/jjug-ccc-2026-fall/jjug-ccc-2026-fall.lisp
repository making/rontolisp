;;;; A lispresen deck. `lispresen talk.lisp` writes talk.html; open it in a browser.
;;;; Every form and option is described in the README:
;;;; https://github.com/making/rontolisp/blob/develop/lispresen/README.md

;; The header. :title adds a title slide.
(deck :title "Common Lisp処理系を50万行のJavaで作る"
      :subtitle "JVM, Wasmバイトコード生成、そしてJava to Wasmへ"
      :author "Toshiaki Maki"
      :date "2026-11-28"
      :description "Common Lisp処理系をJavaで実装し、JVM・Wasmバイトコード・Java to Wasmの各バックエンドまで広げた話"
      :agenda t
      :progress t
      :theme :light ; :light, :dark, :high-contrast or :midnight
      :page-url "https://making.github.io/rontolisp/decks/jjug-ccc-2026-fall/jjug-ccc-2026-fall.html"
      :page-image "jjug-ccc-2026-fall.png"
      :page-image-font ("fonts/BIZUDPGothic-Regular.ttf" "fonts/BIZUDPGothic-Bold.ttf")
      :page-numbers t
      :kicker :section)

;; Two columns: why rontolisp hit the limit, and how it got past it.
(define why-how (why how)
  (column :step t (p (b "なぜ引っかかったか")) why)
  (column :step t (p (b "どう解決したか")) how))

;; Two columns: the code before the fix, then the code after it.
(define before-after (before after)
  (column (p (b "Before")) before)
  (column :step t (p (b "After")) after))

(slide :title "自己紹介"
 (p (b "Toshiaki Maki") " " (link "https://x.com/making" (accent "@making")))
 (ul (li (link "https://ik.am"))
     (li "Springを作っている会社で、SpringのProfessional Serviceなどを提供")
     (li "普段はJava / " (mark "Spring Framework / Boot") "の人")
     (li "最近の趣味は" (mark "ローカルLLM"))))
(agenda)
(section "なんでLisp??")
(slide :title "私のプログラミング言語遍歴" (p "そもそもLisperだった。" (mark "S式が好き") "。")
       (say "黒歴史"
            :at "PHP"
            :step t
            (flow :step t (-> "PHP" (b "Common Lisp"))
                  (-> (b "Common Lisp") "C++")
                  (-> (b "Common Lisp") (b "Clojure")) (-> (b "Clojure") "Java")
                  (-> "C++" "Java")))
       (image "images/blog-2010.png"
              :alt "昔のブログ"
              :caption "2010年のブログはCommon Lisp実装!!"
              :step t))
(slide :title "いつかは自分の言語を実装したい"
 (ul (li "作るなら" (mark "Common Lisp処理系がいいな") "と思っていた")
     (li "生成AIのおかげで" (mark "誰でも言語処理系が作れる") "時代に!"))
 (image
  "images/jisaku-event.png"
  :alt "イベント「自作プログラミング言語を自慢する会」の告知画像"))

(slide :title "最初は\"電卓REPL\"程度だった"
       (ul :step t
           (li "電卓のために" (tt "python") "コマンドではなく" (mark "自分のLisp") "を使いたい")
           (ul (li (tt "(* (+ 10 20) 4) ; => 120")))
           (li "将来的にはCommon Lisp風に拡充したい")
           (li
            (say "rontoは10のマイナス27乗"
                 :step t "名前は控えめに\"" (b (accent "ronto") "lisp") "\"に"))
           (li "今年2月に着手し、飽きて中断") (li "6月に再開。Claude Opus 5の登場とともに飛躍的に成長")
           (li "6ヶ月強で実コード" (say "人間はコードを書いていません" :step t (mark "50万行"))
               "超えのプロジェクトに...")))

(slide "\"ronto\"の意味を変えよう...")
(slide
 (image "images/ronto-search.jpg"
        :alt "「ronto」のGoogle画像検索結果。スター・ウォーズに出てくる生き物の画像が並ぶ"))
(slide :title "というわけでこのロゴに"
       (say "ディズニーに怒られないようにデフォルメ"
            :step t
            (image "images/rontolisp-logo-original.png"
                   :alt "rontolispのロゴ。ラムダの形と、鞍を載せた生き物")))

(section "rontolispとは")

(slide :title "Java で実装した Common Lisp 処理系" (p "一つのソースを、三つの実行モードで動かす。")
       (ul (li (b "インタプリタ") " REPL とファイル実行。ツリーウォークで評価する")
           (li (b "JVM コンパイラ") " " (tt ".class") " や " (tt ".jar") " を出力する")
           (li (b "Wasm コンパイラ") " Wasm（WebAssembly）の " (tt ".wasm")
               " を、WasmGC を使って出力する"))
       (link "https://github.com/making/rontolisp")
       (p "コアライブラリは外部依存を持たない。JVM と Wasm のバイトコードは、ASM などの" (mark "ライブラリを使わずに")
          "生成する。"))
(slide "今となっては単なるCommon Lisp 処理系にはとどまらず、多言語システムに(後で話す)")

(slide :title "簡単な使い方"
       (p
        (link "https://github.com/making/rontolisp/releases/tag/0.1.0-SNAPSHOT")
        "からバイナリをダウンロード")
       (code :lang "bash"

             "
echo '(print (+ 1 2))' > hello.lisp
rontolisp # REPL
rontolisp hello.lisp # インタプリタ
rontolisp hello.lisp -o Hello.class # JVM
java Hello
rontolisp hello.lisp -o hello.wasm # Wasm
wasmtime run hello.wasm
rontolisp hello.lisp -o hello --native # native image
./hello
"))

(slide :title "まず動かしてみる"
       (p "このデッキのコードは、ブラウザ上の rontolisp で実行できる。クリックして編集し、Run で実行する。")
       (say "Run で 3628800 が出る"
            :at "(fact 10)"
            :step t
            (code :lang "lisp"
                  "
      (defun fact (n)
        (if (= n 0) 1 (* n (fact (- n 1)))))
      (print (fact 10))")))

(slide :title "一般的なプログラミング言語の場合"
 (flow (-> "ソースコード" "Lexer" "Parser" (b "AST")) (-> (b "AST") "インタプリタ")
       (-> (b "AST") "コンパイラ"))
 (code
  "
    ;; ソースコード
    (10 + 20) * 4
    ;; Lexer（字句解析）
    LPAREN INT(10) OP(+) INT(20) RPAREN OP(*) INT(4)
    ;; Parser（構文解析）
    Binary(*, Binary(+, 10, 20), 4)")
 (notes "コンパイラを作るとき、まずLexerとParserを書いてASTまで持っていくのが普通の設計。ここを経てからコード生成に入る。"))

(slide :title "S式はどうやってバイトコードになるか"
       (flow (-> "ソースコード（S式）" (b "コアフォーム（S式）")) (-> (b "コアフォーム（S式）") "インタプリタ")
             (-> (b "コアフォーム（S式）") "JVMコンパイラ" (tt ".class"))
             (-> (b "コアフォーム（S式）") "Wasmコンパイラ" (tt ".wasm")))
       (p "S式は抽象構文木（AST）そのもの。" (tt "(* (+ 10 20) 4)")
          "。読み取ったS式は、マクロ展開と正規化を経てコアフォームになり、そのままインタプリタ・コンパイラに渡せる。"
          (mark "コアフォームもS式") "。")
       (notes
        "リーダーがS式を作り、マクロ展開と正規化でコアフォームにする。--dump-ir を付けると、その中間表現をそのまま印刷できる。SchemeとClojureのソースは、先にコアフォームへ落としてから同じ道を通る。"))

(slide :title "loweringしてコアフォームに正規化"
       (p (tt "cond") "、" (tt "and") "、" (tt "or") "、" (tt "setf") " などは、"
          (tt "if") "、" (tt "let") "、" (tt "progn") "、" (tt "setq")
          " などに正規化して展開される。")
       (code :lang "lisp"
             :run nil
             "
    (defun sign (x)
      (cond ((< x 0) 'neg)
            ((= x 0) 'zero)
            (t 'pos)))"))

(slide :title "loweringしてコアフォームに正規化"
       :morph t (p (tt "rontolisp xxxx.lisp --dump-ir") " はバックエンドに渡る最終形を出力する。")
       (code :lang "lisp"
             :run nil
             "
    (defun sign (x)
      (if (< x 0) 'neg
          (if (= x 0) 'zero 'pos)))"))

(slide :title "rontolispが吐くJVMバイトコードの例"
       (code :lang "lisp"
             :run nil
             "
    ;; rontolisp add1.lisp -o Add1.class
    (defun add1 (x) (+ x 1))")
       (code
        "
    aload_0
    checkcast     java/lang/Long      ; 引数を取り出す
    invokevirtual java/lang/Long.longValue:()J
    lconst_1
    invokestatic  java/lang/Math.addExact:(JJ)J   ; 1 を足す
    invokestatic  java/lang/Long.valueOf:(J)Ljava/lang/Long;
    areturn") (p "Javaコンパイラが出すのと同じ命令を、S式から組み立てている。")
       (notes
        "掲載は省略しているが、実出力は先頭でinstanceofで型を調べる。続けてDoubleの経路と汎用の加算がある。addExact が投げる ArithmeticException は、捕まえて多倍長整数の経路へ落とす。"))

(slide :title "rontolispが吐くWasmバイトコードの例"
       (code :lang "lisp"
             :run nil
             "
    ;; rontolisp add1.lisp --no-gc -o add1.wasm
    (defun add1 (x) (+ x 1))")
       (code
        "
    local.get 0
    i64.const 1
    i64.add          ; 1 を足す") (p "Wasmも同様にS式からバイトコードを生成")
       (notes
        "--no-gcはエクスポート宣言がないと何も出力しない。この例では (rontolisp:wasm-export 'add1 :params '(:long) :returns :long) を付けている。境界を:longにするとi64のまま受け渡すので、変換も範囲検査も入らない。:intにするとi32との変換と範囲検査が付く。GCを使う既定の出力では整数をi31という参照として持つため、ref.castとi31.get_sが必要になる。"))

(slide :title "Javaに置き換えて見るWasm"
       (table :header ("Java" "Wasm") ("クラスファイル" (tt ".wasm"))
              ("JVM" "wasmtime、ブラウザ、Node.js")
              ("JVMのGC" "WasmGC（WasmにGCを足した拡張）")
              ((cell (tt "System.out") "、ファイル、ネットワークのAPI")
               "WASI（WebAssembly System Interface）。OSの機能を使う標準API。ネットワークは0.2から"))
       (p "Wasmはどこでも動くバイトコード。GCも入出力も、後から足された拡張になっている。")
       (notes
        "WASI Preview 1にも、ホストが渡したソケットを使う関数（sock_accept、sock_recv、sock_send）はあったが、自分でソケットを作ったり接続したりはできなかった。TCP/UDPのソケット（wasi:sockets）とHTTP（wasi:http）は0.2から。Wasmの最初の仕様には、GCも、ファイルや標準出力を使う手段もなかった。メモリはただのバイト配列で、言語処理系が自分で管理していた。WasmGCが入って、Javaと同じようにオブジェクトをエンジンのGCに任せられるようになった。"))

(slide :title "Reactor、WASI Preview 1、WASI 0.3"
       (ul (li (b "Reactor") " 最初からあるWasmの形。" (tt "main")
               "を持たず、ホストから関数を呼ばれる。MVPモジュールともいう")
           (li (b "WASI Preview 1") " 最初のWASI。" (tt "main") "にあたる" (tt "_start")
               "から実行し、" (tt "fd_write") "のような関数で入出力する")
           (li (b "WASI 0.3")
               " Component Model（モジュールを型付きのインターフェースでつなぐ仕組み）の上のWASI。非同期に対応した"))
       (notes
        "生まれた順。ホストがエクスポートされた関数を呼ぶ形は、2017年3月のWasmの最初の版からある。WASI Preview 1は2019年11月。当初のWASIはmainを持つ形（_start）だけを定めていて、mainを持たない形にReactor（_initialize）という名前が付いたのは2020年4月。WASI 0.2でComponent Modelの上に移り（2024年1月）、0.3でストリームと非同期が入った（2026年6月）。Preview 1では、文字列はメモリ上のポインタと長さで渡す。Component Modelでは、インターフェースをWITという言語で型付きで定義する。rontolispのReactorは --no-wasi で作り、WASIの関数を1つもインポートしない。"))

(slide :title "rontolispが出力するWasmの形"
       (table :header ("フラグ" "形式" "Javaで例えると" "動く場所")
        ("なし" "WASI Preview 1" (cell (tt "main") "を持つjar") (cell (tt "wasmtime run") "またはWASI対応のホスト"))
        ((tt "--component") "WASI 0.3" "Servletのwarのような部品" "同上")
        ((tt "--no-wasi") "Reactor" (cell (tt "main") "のないライブラリjar")
         "ブラウザ、Cloudflare Workers")
        ((tt "--no-gc") "Reactor（GCなし）" "数値と文字列だけのプログラム" "どのエンジンでも"))
       (notes
        "なしと --component はWASIで入出力する。--component はWASI 0.3のコンポーネントで、HTTPのハンドラのような型付きのインターフェースを実装する。--no-wasi はOSの機能を一切使わず、JavaScriptなどのホストが、エクスポートされた関数を呼ぶ。--no-gc はLispの全機能は使えない代わりに、WasmGCのない古いエンジンでも動き、出力は数百バイトになる。フラグは組み合わせられ、全部で6通りある。"))

(slide :title "rontolispで書くWebアプリケーション"
       (code :lang "lisp"
             :run nil
             "
    (ql:quickload \"clack\")                ; ライブラリを読み込む
    (defun app (env)                      ; リクエストを受け取る関数
      (list 200                           ; ステータス
            '(:content-type \"text/plain\") ; ヘッダ
            '(\"Hello World!\")))           ; ボディ
    (clack:clackup #'app :server :rontolisp 
                         :port 8080 
                         :use-thread nil) ; 起動")
       (ul (li "ClackはLispのWebアプリの抽象化レイヤ（JavaのServlet APIにあたる）")
           (li "ClackのJVM/Wasm向けサーバー実装をrontolispが内蔵。")))

(slide :title "出力形式を変えるだけでデプロイ先が変わる"
       (table :header ("デプロイ先" "ビルド" "実行")
              ("JDK HTTP Server" (tt "-o app.jar") (tt "java -jar app.jar"))
              ("Servlet" (tt "-o app.war") "Tomcatなどに置く")
              ("WASI HTTP" (tt "-o app.wasm --component")
               (tt "wasmtime serve -S cli app.wasm"))
              ("Cloudflare Workers" (tt "-o worker.wasm --no-wasi")
               (tt "wrangler deploy"))
              ("ビルドしたOS上" (tt "-o app --native") (tt "./app")))
       (link "https://github.com/making/rontolisp/blob/integration/examples/net/hello-clack.lisp"
             "ソース"))

(slide :title "モダンなJava APIで実装している"
       (table :header ("API" "使いどころ")
              ("パターンマッチング" "25種類のLispの値を、sealed interfaceとswitchで場合分けする")
              ("Class-File API" "JVMのバイトコードを生成する")
              ("Virtual Thread" "HTTPサーバで、1リクエストを1つの仮想スレッドで処理する")
              ("Flow API" "HTTPクライアントのレスポンスボディを、少しずつ読む")
              ("Vector API" (cell (tt "--simd") "オプションのカーネル"))
              ("FFM" "BLAS、GPU、Objective-Cの呼び出し")))


(slide :title "rontolispで作ったもの"
       (column (b
                (link "https://making.github.io/rontolisp/lispresen/intro.html"
                      "lispresen"))
               (p "S式でかけるプレゼン資料。スライドを自動レイアウト。" (mark "この資料はlispresenで作成。")))
       (column (b "ブログのカウンター")
               (p "ブログ記事のアクセスカウンターをLispで書いてWasmとして実行。Wasm実行基盤も自作(Java + Rust製)")
               (image "images/rontolisp-made.png"
                      :alt "counter")))

(section "rontolispのスコープ拡大")

(slide :title "この仕組み、入り口がCommon Lispである必要なくない？"
       (flow (-> "ソースコード（S式）" (b "コアフォーム（S式）")) (-> (b "コアフォーム（S式）") "インタプリタ")
             (-> (b "コアフォーム（S式）") "JVMコンパイラ" (tt ".class"))
             (-> (b "コアフォーム（S式）") "Wasmコンパイラ" (tt ".wasm")))
       (p "ASTとなるコアフォームにさえ変換できればコンパイラバックエンドが再利用できる。"))


(slide :title "ClojureとSchemeも同じコアフォームから"
 (flow (-> "Clojureソース" (b "コアフォーム（S式）")) (-> "Schemeソース" (b "コアフォーム（S式）"))
       (-> (b "コアフォーム（S式）") "JVMコンパイラ" (tt ".class"))
       (-> (b "コアフォーム（S式）") "Wasmコンパイラ" (tt ".wasm")))
 (p "違うのはフロントエンドだけ。ClojureとSchemeのソースも、それぞれの規則でコアフォームに落としてから、同じコンパイラに渡す。")
 (notes
  "ClojureとSchemeのフロントエンドは実験的。Common Lispのソースと同じく、インタプリタでもJVMでもWasmでも動く。"))

(slide :title "Common Lisp、Scheme、Clojure を読む"
       (column (p (b "Common Lisp"))
               (code :lang "lisp"
                     "
      ;; sq.lisp
      (defun sq (x) (* x x))
      (print (sq 12))"))
       (column (p (b "Scheme"))
               (code :lang "scheme"
                     "
      ;; sq.scm
      (define (sq x) (* x x))
      (display (sq 12))"))
       (column (p (b "Clojure"))
               (code :lang "clojure"
                     "
      ;; sq.clj
      (defn sq [x] (* x x))
      (println (sq 12))"))
       (p "どの言語 (Lisp方言) のソースも同じコアフォームに変換するので、インタプリタ、JVM、Wasm、ネイティブのどれでも動く。"))

(slide :title "コンパイルの流れ復習"
       (column :step t (lead "ここまで説明してきたrontolisp")
               (flow (-> "ソースコード（S式）" (b "コアフォーム（S式）") "JVM/Wasmコンパイラ")))
       (column :step t (lead "一般的な言語")
               (flow
                (-> "ソースコード (任意言語)" "Lexer" "Parser"
                    (say "S式=AST。ここがコアフォームになると?" :step t (b "AST")) "コンパイラ"))))

(slide :title "もしかして..."
 (image "images/nobita.png" :alt "のび太が閃く"))

(slide :title "rontolispの任意言語対応"
 (flow (-> "ソースコード (任意言語)" "Lexer" "Parser" (b "コアフォーム（S式）") "JVM/Wasmコンパイラ"))

 (say "任意の言語をJVM/Wasmにコンパイルできる!"
      :step t
      (p :step t "理論上は、任意の言語がParserでコアフォーム (S式)に変換できれば、rontolispの"
         (mark "JVM/Wasmコンパイラがそのまま利用") "できる!!")))

(slide "第一弾は Starlark（Pythonサブセット）")

(slide :title "Starlarkも同じコアフォームになる"
       (code :lang "python"
             "
      def square(x):
          return x * x

      print([square(i) for i in range(5)])")
       (p "Starlarkのソースは、専用の字句解析と構文解析でコアフォームに落としてから、Common Lispと同じコンパイラに渡す。"))

(slide "第2弾は Java")

(slide :title "JavaもWasmにコンパイル可能に!"
       (code :lang "java"
             "
void main() {
    IO.println(\"Hello World!\");
}"))

(slide :title "JavaもWasmにコンパイル可能に!"
       (code :lang "java"
             "
static int square(int x) {
    return x * x;
}
void main() {
    IO.println(IntStream.range(0, 5)
                        .mapToObj(x -> square(x))
                        .toList());
}"))

(slide :title "JavaもWasmにコンパイル可能に!"
       (code :lang "bash"
             "$ rontolisp --source-language java
java> var list = new ArrayList<String>()
list ==> []
java> list.add(\"a\")
$1 ==> true
java> list
$2 ==> [a]
")
(p "jshellのようなREPLも使える")
       )

(slide :title "JavaもWasmにコンパイル可能に!"
       (code :lang "java"
             "
record Point(int x, int y) {
}

void main() {
    Point p = new Point(10, 20);
    String description = switch (p) {
      case Point(int x, int y) when x == y -> \"y = x 上の点: \" + p;
      case Point(int x, int y) -> \"座標: \" + p;
      case null -> \"null のため座標なし\";
    };
    IO.println(description);
}

"))

(slide :title "JavaもWasmにコンパイル可能に!"
       (code :lang "java"
             "
import module java.net.http;

void main() throws IOException, InterruptedException {
    var client = HttpClient.newHttpClient();
    var uri = URI.create(\"https://httpbin.ik.am/get\");
    var request = HttpRequest.newBuilder(uri).build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    IO.println(response.statusCode() + \"\\n----\\n\" + response.body());
}

"))
(slide "JDKはフルスクラッチで書いています(本家ソースを見ずに)。")

(slide :title "ServletもWasm化"
       (code :lang "java" :run nil :file "demo/HelloServlet.java"))

(slide :title "ServletもWasm化"
       (code
        "
rontolisp \\
  HelloServlet.java \\
  --component -o hello-servlet.wasm \\
  --java-source-dep am.ik.servlet:servlet-httpserver:0.1.0-SNAPSHOT \\
  --java-snapshots
        ") (code " wasmtime serve hello-servlet.wasm")
       (p "Clackで実現していたデプロイ方式が全てJavaソースでも活用できる!!") (p "JDK HTTP Serverが抽象化境界")
(link "https://github.com/making/rontolisp/tree/integration/examples/java/hello-servlet" "ソース")
       )

(slide :title "現時点でできていること"
            (ul (li "Servletを動かす") (li "JDBCドライバ(自作)でPostgreSQLにアクセス")
                (li "jmustacheでテンプレート描画") (li "Spring MVC.fn(互換API)でWebアプリ作成")
                (li (link "https://github.com/making/yavi" "YAVI") "でバリデーション")
                (li "Maven PluginでWasmビルド (" (tt "mvn rontolisp:wasm") ")"))
       (p
        (link "https://github.com/making/rontolisp/tree/develop/examples/java"
              "サンプルコード")))

(slide (p "rontolispはもはや、" (mark "S式をコアの中間表現にした、多言語・多ターゲットのコンパイラツールチェイン")))

(slide :title "今はこんな感じ"
       (code
        "
 .lisp  Common Lisp --+                       +-- Interpreter (REPL)
 .scm   Scheme      --+                       +-- JVM: .class / .jar
 .clj   Clojure     --+--> S-expressions -----+-- WASM (WasmGC): WASI Preview 1,
 .star  Starlark    --+    (core Lisp forms)  |     WASI 0.3 component
 .java  Java        --+                       +-- WASM (--no-gc): MVP module
                                              +-- Native executable (--native)
    "))

(section "rontolispはこうやって開発している")

(slide :title "rontolispはAIで100%作成されている"
       (video "jjug-furnace.webm"
              :alt "トークンをかまどに注ぐとrontolispのコードが出てくるアニメーション"
              :autoplay t
              :muted t
              :loop t)
       (notes "トークンを燃やしてrontolispができる、という図。実際のモデルはClaude Opus 5とFableがメイン。"))

(slide :title "どのモデルがどの月にコミットしたか"
       (columns :series ("Opus" "Fable" "Sonnet" "その他")
                :stack t ("2月" 41 0 0 0) ("3-5月" 0 0 0 0) ("6月" 159 10 0 0)
                ("7月" 360 203 12 0) ("8月" 994 288 34 0) ("9月" 1592 84 157 333)
                ("10月" 1617 8 352 429) ("11月" 1367 227 123 52))
       (p "コミットの Co-Authored-By から数えたモデル別の月次件数。11月は25日までの集計。")
       (notes
        "dependabotとcopilotのコミットは除いた。3月の2コミットはbotのみ。9月からローカルLLM（muse、GLM、Qwen、DeepSeek）が入り、10月に増える。合計が10件未満のモデルは省いた。"))

; (slide :title "月ごとに使ったClaudeモデル"
;        (table :header ("月" "主なモデル" "コミット数")
;         :align (:left :left :right) ("2月" "Opus 4.6" "41")
;         ("6月" "Opus 4.8" "169") ("7月" "Opus 4.8、Fable 5、Opus 5" "575")
;         ("8月" "Opus 5、Fable 5" "1322")
;         ("9月" "Opus 5、Opus 5.5、Sonnet 5、Fable 5.1" "2168")
;         ("10月" "Opus 5.5、Sonnet 5.5" "2406")
;         ("11月" "Opus 5.5、Fable 5.2、Sonnet 5.5" "1769"))
;        (p "その月に50件以上あったモデルを多い順に。ほかのモデルは次のページ。")
;        (notes "1M contextの表記は同じ版にまとめた。合計が10件未満のモデルは省いた。"))

(slide :title "その他のモデルの内訳"
       (table :header ("モデル" "サービス" "9月" "10月" "11月")
              :align (:left :left :right :right :right)
              ("Muse Spark 1.3" "OpenCode Go" "229" "408" "")
              ("Qwen3.8-Flash-Next" "ローカルLLM" "47" "" "") 
              ("DeepSeek V4.1" "ローカルLLM"  "26" "" "52")
              ("GLM-5.3-Flash" "ローカルLLM"  "31" "21" ""))
       (notes
        "Qwen3.8-Flash-NextはunslothのUD-Q4_K_XL。Qwen3.8-27B（ggml、ddalcu）は合計8件なので省いた。DeepSeekの9月はvision-expも含む。"))

(slide :title "ローカルLLM"
       (p (mark "DGX Spark (互換機) を4台 (約450GBユニファイドメモリ) 並べて")
          "、大きめのモデルを自宅で運用。Claude Codeから利用。") (p "8月以降、ローカルでもに実用的なモデルが出始める")
       (column
        (ul (li "GLM 5.3 Flash (320B-A18B)")
            (li
             (say "こちらを使用中"
                  :step t "DeepSeek V4.1 Flash 1017 (552B-A8B/16B-E196B)"))))
       (column
        (image "images/local-llm.jpg"
               :alt "4x DGX Spark")))

; (slide :title "コードの規模"
;        (table :header ("対象" "ファイル" "実行行数")
;               :align (:left :right :right) ("Java 合計" "2,099" "564,835")
;               ("　フロントエンド（リーダー、マクロ展開、各言語のlowering）" "301" "154,661")
;               ("　JVMコンパイラ" "232" "83,067") ("　Wasmコンパイラ" "233" "107,917")
;               ("　評価器（インタプリタ）" "165" "70,710")
;               ("　JDK API実装（resources の .java）" "866" "84,376"))
;        (p "コメントと空行を除いた実行行数（" (tt "cloc") " で11/27計測）。Lispコードやテストコードは計測対象外。")
;        (notes
;         "フロントエンドは reader、macro、compiler と、Clojure・Scheme・Java・Starlark のフロントエンド。JDK API実装は resources/am/ik/rontolisp/java/library の .java で、rontolispのインタプリタが読む。LispとClojureで書いた標準ライブラリの実装（Lisp 63,527行、Clojure 5,482行）はこの表に含めていない。JSON（GraalVMの設定）、エクスポート一覧やタイムゾーン規則のようなテキストデータ、README、GPUカーネルも数えていない。テストと他モジュールも含まない。"))

(slide :title "依頼 (プロンプト) は2つのフェーズ"
       (code
        "
    ;; 第1フェーズ
    <やりたいこと>を実現するためのSpikeを実施し、
    実現するためのtodoをファイルに書き出して") (p "→ todoファイルがissueのように溜まる。")
       (code
        "
    ;; 第2フェーズ
    あなたはオーケストレータです。サブエージェントを立てて、
    Todo 001からNNNまでを消化して。各Todoはworktreeで作業し、
    完了する毎にコミット&pushとworktreeの削除。並列4件まで。
    各Todoへの指示は「Todo NNNに着手して」「終わったらコミット&push」
    くらいでよい。新しく発生したTodoは再帰的にキューへ回して。") (p "→ 寝ている間、仕事中、外出中にまとめて片付く。")
       (notes
        "第1フェーズで作るtodoファイルは、実装方針・制約・測った数字まで書かせる。第2フェーズはオーケストレータに投げるだけで、人間は寝ていてよい。並列数はサブエージェントの同時実行数の上限で、この環境では4。各Todoをworktreeで分離するのは、並列に走るエージェント同士が作業ツリーを壊さないようにするため。"))

(slide :title "なぜAIに開発を丸投げできるのか"
       (flow (-> "言語仕様（ANSI / JVM / Wasm）" "正解が機械的に分かる" (b "AIが自分で正解を見つけられる"))
             (-> "テストオラクル（正解を出す基準器=本家CLI）" "正解が機械的に分かる" (b "AIが自分で正解を見つけられる"))
             (-> "コーパス（書籍やライブラリ）" "正解が機械的に分かる" (b "AIが自分で正解を見つけられる")))
       (p "価値のあるE2Eをたくさん用意できる。人間が決めるのは、"
          (say "ただし、ライセンスに注意" :step t (mark "何を作るかのアイディア出しだけ") "になる。"))

       (notes
        "ANSIのテストスイート（ansi-test）を毎日回して章ごとの通過数を記録している。Practical Common Lispのサンプルコードは12システムをSBCL 2.2.9とバイト単位で突き合わせ、shimなしで一致した。Quicklispのmito、sxql、esrap、rove、cl-mustacheなども上流のソースをそのまま読ませて、SBCLと同じ出力になることを確認している。"))

(slide :title "AIにJavaを書かせる際のおすすめツール2選"
 (column (b "ArchUnit") (p "パッケージの循環参照は複雑さの元。" (mark "循環参照はテストでエラー") "にする。"))
 (column (b "Nullability Maven Plugin")
         (p "JSpecify + NullAwayを設定。" (mark "ヌルポが発生しうるコードをコンパイルでエラー") "にする。"))
 (p "こちらの "
    (link "https://github.com/making/claude-skills/blob/main/java-code-standards/skills/java-code-standards/SKILL.md#null-safety"
          "SKILL") "を参照"))

(slide :title "とはいえ並列で回すとビルドとテストが重い"
       (table :header ("困りごと" "どうした")
              :align (:left :left) ("ラップトップが熱すぎる" "ベアメタル（32コア、256GB）で回す")
              ("フルテストが1時間以上" "タスク単位では回さない") ("落ちたテスト" "数タスク分ためて、まとめてAIに直させる")
              ("CIに2時間以上" "CI対象のブランチへのpushは溜めてまとめて"))
       (p "端末の負荷はサーバーで解決できる。並列作業の選定、衝突のマージ、pushのバッチ化はAI任せ。並列数のボトルネックはサブスクのクオータ...")
       (notes
        "ビルドとテストが並列数だけ同時に走るので、ファンが回りっぱなしになる。だから開発機ではなくベアメタルサーバーで回している。フルテストを1時間以上かけて毎タスク回すのは無駄なので、失敗してもその場で直さず、数タスク分まとめて回して、落ちた分をまとめてAIに直させる。"))

;(slide :title "並列数のボトルネックはサブスクのクオータ..."
;       (ul (li "5並列で回し続けると、2-3日でClaude Code Ultraプラン（旧Max 20x、月額$220）の週間上限に届く")
;           (li "並列度は、rontolisp以外にどれだけClaude Codeを割くかで変わる")
;           (li "枠を気にせず回したいときは、コスパの良いMuse Spark 1.3（OpenCode Go経由、月額$10）or Local LLMに逃がす"))
;       (notes
;        "端末の熱と時間はサーバーとテストの回し方で解けるが、クオータは買うか待つかしかない。5並列でタスクを回し続けたときの体感で3日。並列度を上げるほど、他の仕事に使える枠が減る。"))

; (slide :title "週間クオータを前提に曜日で回す"
;        (table :header ("曜日" "その週の使い方")
;         :align (:left :left) ("土日月" "リセット直後。並列でぶん回す")
;         ("火水" (say "いまは代わりにローカルLLMを多用" :step t "様子見 (Muse Spark 1.3ぶん回し)"))
;         ("木金" "残りを使い切る")) (p "週間制限は土曜の朝にリセットされる。いまは開発が落ち着いてきたのでOpenCode Goは解約。")
;        (notes
;         "Claude Codeの週間制限に合わせて、リセット直後の土日月にまとめて回し、火水は他の仕事に枠を残して様子を見て、木金で使い切る。枠を消費しないローカルLLMを併用すると、並列を回す時間帯を増やせる。"))



;(slide :title "開発にかかる費用 💸💸💸"
;       (table :header ("項目" "費用" "補足")
;              :align (:left :right :left)
;              ("Claude Code Ultra" "月額$220" "旧Max 20x。開発の主力")
;              ("OpenCode Go" "月額$10" "Muse Spark 1.3。解約済み")
;              ("ベアメタルサーバー" "年額約$1,900" "32コア、256GB")
;              ("DGX Spark" "時価(笑)" "ローカルLLM")) (p (b "開発力 = 💰💰💰"))
;       (notes
;        "AIのサブスクとサーバー代が主な費用。Claude Codeのプランは並列度をそのまま買うことになる。サーバーは年額で、ローカルLLMはGPUの時価と電気代。"))

(section "まとめ")
(slide :title "まとめ"

       (ul (li "LLMぶん回して50万行のCommon Lisp処理系書いていたと持ったら、多言語システムができた")
         (li "個人開発でも数ヶ月でここまでできる!!")
           (li "Java -> Wasmできた。(面白そうな未来が垣間見えた)")))
(slide :title "アンケート回答よろしくお願いします"
(p (link "https://x.gd/8d2oD"))
(image "images/survey.png" :alt "セッションアンケート")
(p (say "rontolispもチェックしてね" (link "https://github.com/making/rontolisp")))
    )