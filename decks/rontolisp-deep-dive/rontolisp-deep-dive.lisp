;;;; rontolisp-deep-dive.lisp -- how rontolisp works inside: one pipeline of core forms,
;;;; the interpreter and the compile paths, the JVM and WASM backends, the native runner,
;;;; the Scheme and Clojure front ends, and the cross-backend testing that holds them
;;;; together. Every fact comes from the repository; each slide's source line names where.

(deck :title "rontolisp deep dive"
      :author "rontolisp"
      :date "2026-10-23"
      :url "https://github.com/making/rontolisp"
      :description "rontolisp の内部: コアフォームのリストという一つの IR から、インタプリタ、JVM、WASM、ネイティブ実行ファイルへ"
      :page-url "https://making.github.io/rontolisp/decks/rontolisp-deep-dive/rontolisp-deep-dive.html"
      :page-image "rontolisp-deep-dive.png"
      :page-image-font ("fonts/BIZUDPGothic-Regular.ttf" "fonts/BIZUDPGothic-Bold.ttf")
      :theme (theme :base :light :code :dark)
      :aspect :16x9
      :runtime :hosted
      :kicker :section
      :agenda "話の流れ"
      :progress t
      :page-numbers t)

;;; --- the front end -----------------------------------------------------------------

(section "ソースからコアフォームへ")

(slide :title "一つのパイプライン、四つのバックエンド"
  (flow "ソース" "コアフォーム" "マクロ展開" "評価 / コンパイル")
  (ul (li (tt "SourceLanguage") " が言語ごとにソースを読み、コアフォームに下げる")
      (li "インタプリタ (" (tt "LispEvaluator") ") が評価し、JVM と WASM のコンパイラはバイト列を出力する")
      "WASM には Preview 1 とコンポーネントの二通りがあり、バックエンドは合わせて四つ")
  (source ".kb/architecture.md (Pipeline), .kb/running-backends.md"))

(slide :title "IR はコアフォームのリスト"
  (say "先頭の名前で分岐する" :at "if"
    (cells '(if (< x 0) neg pos)))
  (ul (li (tt "SourceLanguage") " が返す " (tt "List<LispVal>") " が、そのまま IR になる")
      "これより低いレベルの中間表現はない")
  (source ".kb/source-language.md, .kb/scheme-frontend.md"))

(slide :title "ソースからフォームへの入口は一つ"
  (p (tt "SourceLanguage") " が、ユーザーのソースをフォームにする唯一の入口である。")
  (ul (li "言語はファイルごとに拡張子で決まる ("
          (tt ".lisp") "、" (tt ".scm") "、" (tt ".clj") "、" (tt ".cljc") ")")
      (li (tt "(load ...)") " したファイルは、そのファイルの拡張子の言語で読む")
      (li "入口のファイルだけは " (tt "--source-language") " で言語を指定できる")
      (li "入口の外で " (tt "LispReader") " を呼ぶクラスがないことを、"
          (tt "SourceLanguageSeamTest") " が確かめる"))
  (source ".kb/source-language.md"))

(slide :title "組み込みマクロはコアフォームに下がる"
  (p (tt "cond") "、" (tt "and") "、" (tt "or") "、" (tt "setf") " などは、"
     (tt "if") "、" (tt "let") "、" (tt "progn") "、" (tt "setq") " などに展開される。")
  (code :lang "lisp" :run nil "
    (defun sign (x)
      (cond ((< x 0) 'neg)
            ((= x 0) 'zero)
            (t 'pos)))")
  (source ".kb/architecture.md (Pipeline)"))

(slide :title "組み込みマクロはコアフォームに下がる" :morph t
  (p (tt "--dump-ir") " はバックエンドに渡る最終形を出力する。")
  (code :lang "lisp" :run nil "
    (defun sign (x)
      (if (< x 0) 'neg
          (if (= x 0) 'zero 'pos)))")
  (source (tt "rontolisp sign.lisp --dump-ir") " の出力を小文字にして改行した (2026-10-23)"))

(slide :title "マクロ展開をその場で見る"
  (code :lang "lisp" "
    (defmacro swap (a b)
      `(let ((tmp ,a)) (setq ,a ,b) (setq ,b tmp)))
    (print (macroexpand-1 '(swap x y)))
    (macroexpand '(cond ((< x 0) 'neg) (t 'pos)))")
  (ul "バッククォートは、リーダーが読み込み時に list、append、cons、quote の呼び出しに展開する"
      (li "コンパイルパスでは " (tt "UserMacroExpander") " が " (tt "defmacro")
          " をマクロ用のインタプリタで評価し、呼び出しを展開してから定義を取り除く"))
  (source ".kb/defmacro-backquote.md"))

;;; --- the compile path's front end --------------------------------------------------

(section "CompileFrontend.expand")

(slide :title "コンパイルパスの前段は一か所にある"
  (flow "読み込み" (tt "(load ...)" " の展開") "ライブラリの接合" "WIT の lowering" "刈り込み")
  (ul (li "すべてのバックエンドと埋め込み側が " (tt "CompileFrontend") " を通る")
      (li "パスの並びは " (tt "CompileFrontend.expand") " にだけ書き、ほかの場所で書き直さない")
      (li "テストも " (tt "CompileFrontendAccess") " を通して前段を呼ぶ"))
  (source ".kb/architecture.md (Package rules)"))

(slide :title "接合は入れ子の呼び出しで書く"
  (say "使わない定義を落とす" :at "prune(program);"
    (code "
      languages = InflateLibrary.process(
        ClojureLibrary.process(
          SchemeLibrary.process(
            UserMacroExpander.expand(...), ...), ...), ...);
      ...
      program = LibraryDefunPruner.prune(program);"))
  (p "呼び出しの順序には意味がある。Scheme の実行時ライブラリはプレリュードの文字列比較を使うので、プレリュードの内側に接合する。")
  (source "src/main/java/am/ik/rontolisp/cli/CompileFrontend.java からの抜粋, .kb/scheme-frontend.md"))

(slide :title "書き直しを禁じた経緯"
  (lead "二つのコーパス検査がパスの並びの写しを持ち、本体からずれていった")
  (ul (li (tt "tokenizer:") " のケースが " (tt "ci-spec.yaml") " に入った時点で 8 パス、直した時点で 10 パス遅れていた")
      (li "パスの名前を数えるだけでは見つからない、位置のずれもあった (" (tt "VecLibrary") ")")
      (li (tt "JvmOsrBackedgeCorpusTest") " は 2026-09-19 まで写しを持ち、接合 11 個分遅れて、成功するたびに警告を 10 行出していた"))
  (source ".kb/architecture.md (Package rules)"))

;;; --- the interpreter and the JVM ---------------------------------------------------

(section "インタプリタと JVM")

(slide :title "既定の実行経路はインタプリタ"
  (p (tt "-o") " のない " (tt "rontolisp app.lisp") " は、コンパイルせずに " (tt "LispEvaluator") " で実行する。")
  (table :header ("測ったもの" "値")
    ("インタプリタのループ (SBCL 比)" "20〜240 倍遅い")
    ((cell (tt "java -jar") " のコンパイルの固定費") "約 0.6 秒")
    ((cell "前段 (温まった後)") "約 4 ms")
    ((cell "JVM のコード生成 (温まった後)") "約 140 ms")
    ("ネイティブバイナリでのコンパイル全体" "約 0.16 秒"))
  (source ".kb/default-run-path.md"))

(slide :title "実行エンジンを自動で切り替えない理由"
  (ul (li "固定費の大半は、コンパイラ自身のクラスロードと JIT のウォームアップ")
      (li "メモリ上でコンパイルして実行すると、" (tt "java -jar") " で "
          (tt "(print 'hi)") " が 2.2 倍遅くなる")
      "rontolisp のネイティブバイナリは、実行時にクラスを定義できない"
      "実行のたびにエンジンが変わると、インタプリタとコンパイル経路の食い違いが、そのまま気付きにくい挙動の変化になる")
  (source ".kb/default-run-path.md"))

(slide :title "自由変数の解析が一つ抜けると"
  (p (tt "FreeVarAnalyzer") " がフォームの一部を読み飛ばしても、コンパイルエラーにはならない。結果は三通りに分かれる。")
  (table :header ("経路" "結果")
    ("インタプリタ" "正しい (環境ごと捕捉する)")
    ("JVM" "変数の新しいコピーを読み、エラーなしに誤った結果を出す")
    ("WASM" "コンパイル時に拒否する"))
  (source ".kb/core-representation.md (Three-pass compilation)"))

(slide :title "JVM: クラスファイルを JDK の API で書く"
  (lead "ASM などのライブラリは使わず、" (tt "java.lang.classfile") " で書く")
  (table :header ("形式の上限" "超えたとき")
    ("分岐オフセット (16 ビット)" (cell (tt "goto_w") " で書く"))
    ("メソッドのコード (65,535 B)" "メソッド名を挙げて拒否する")
    ("定数プール (65,534 項目)" (cell (tt "$PartN") " のクラスに分ける"))
    ((cell (tt "CONSTANT_Utf8") " (65,535 B)") "分割して連結する"))
  (source ".kb/jvm-method-size-limits.md, doc/ja/compiling/jvm.md"))

(slide :title "末尾呼び出しの実装は経路ごとに違う"
  (table :header ("経路" "仕組み")
         :caption "どれも定数スタックで動き、出力は変わらない"
    ("インタプリタ" (cell (tt "eval") " のループで、末尾のフォームがフレームを置き換える"))
    ("JVM" (cell "自分への末尾呼び出しはメソッドの先頭への " (tt "goto")
                 "。関数値を通す末尾呼び出しは、64 回を超えて続くとトランポリンに切り替える"))
    ("WASM" (cell "末尾位置の呼び出しをすべて " (tt "return_call") " で出す")))
  (source ".kb/interpreter-tail-calls.md, .kb/jvm-self-tail-calls.md, .kb/jvm-tail-bounce.md, .kb/wasm-tail-calls.md"))

(slide :title "定数スタックで動く末尾再帰"
  (p "ブラウザで動くインタプリタでも、末尾呼び出しはフレームを積まない。")
  (say "Run で DONE が返る" :at "(count-down 100000)"
    (code :lang "lisp" "
      (defun count-down (n)
        (if (= n 0) 'done (count-down (- n 1))))
      (count-down 100000)"))
  (source ".kb/interpreter-tail-calls.md"))

;;; --- WASM and the native runner ----------------------------------------------------

(section "WASM とネイティブ実行ファイル")

(slide :title "WASM: 値は GC ヒープに置く"
  (ul (li "整数は " (tt "i31ref") "。fixnum の範囲を超えると 64 ビットの構造体、さらに超えると多倍長整数")
      "浮動小数点数は構造体にボックス化する"
      (li "文字列はバイトの配列 (" (tt "$str_bytes") ")")
      "モジュールはアロケータを持たない"
      (li (tt "--no-gc") " は、純粋な計算に限ったプログラムを、スカラーとリニアメモリの文字列に下げる"))
  (source "doc/ja/compiling/wasm.md, .kb/wasm-gc-strings.md, size-report/results/wasm-flags.md"))

(slide :title "例外処理はプログラムが使うときだけ"
  (ul (li (tt "handler-case") "、" (tt "ignore-errors") "、" (tt "unwind-protect")
          " を含むプログラムだけを EH モードでコンパイルする")
      (li "EH モードではタグ " (tt "$lisp-cond") " を一つ置き、"
          (tt "try_table") " と " (tt "throw") " で捕まえる")
      "それ以外のプログラムは、EH 対応のないコンパイラの出力とバイト単位で同じになる"
      (li (tt "ref.cast") " の失敗のような trap は捕まえられない"))
  (source ".kb/error-handling.md (WASM EH-mode specifics)"))

(slide :title "着地パッドで局所変数を読み直す"
  (lead "コーパス全体が、コードの大きさによって " (tt "cast failure") " で落ちたり通ったりした")
  (ul "Cranelift は局所変数を、呼び出しの前に評価した引数として着地パッドに渡していた"
      "呼び出しの間にコピー GC がオブジェクトを動かすと、パッドは古い参照を受け取る"
      "対策として、パッドの先頭で、その後に使う局所変数を読み直す")
  (notes "3,590 行を出力する実行の 3,587 行目で trap した。落ちるか通るかは、その呼び出しの間に GC が起きるかどうかだけで決まる。GC ヒープの事前拡張はコードのバイト数に比例するので、ケースを一つ足すだけで GC の時期がずれ、結果が変わった。")
  (source ".kb/wasm-landing-pad-refresh.md, .kb/wasi-component.md"))

(slide :title "コンポーネントは Preview 1 のコアを包む"
  (flow "コアモジュール (Preview 1)" "アダプタ" "WASI 0.3 のコンポーネント")
  (ul "コアモジュールは Preview 1 の出力と同じものを使う"
      (li "アダプタが " (tt "wasi_snapshot_preview1") " の 15 関数を、WASI 0.3 の stream と future で実装する")
      (li (tt "--optimize") " は使うインタフェースだけを残す。" (tt "(print \"Hello World!\")")
          " では、インタフェースが 11 から 3 に、インポートのブロックが 7,690 B から 2,138 B になる"))
  (source ".kb/wasi-component.md"))

(slide :title "刈り込みで変わる .wasm の大きさ"
  (lead "chipz で gunzip するプログラムの " (tt ".wasm"))
  (say "既定で刈り込む" :at "--optimize (既定)"
    (bars :unit " B" ("--optimize=off" "550,484") ("--optimize (既定)" "133,689")
          ("--optimize=size" "102,171") ("--component --optimize=size" "106,355")))
  (p "Hello, World! だけを出すプログラムは、" (tt "--optimize=off") " の 271,101 B から既定の 480 B になる。")
  (notes "「大きさ」は一つの量ではない。バイト列を削らずに別の場所へ移すと、生のサイズは減っても gzip 後のサイズは増えることがある (.kb/size-measurement.md)。")
  (source "size-report/results/wasm-flags.md (2026-10-15 測定)"))

(slide :title "ネイティブ実行ファイル"
  (flow (tt ".wasm") "wasmtime でプリコンパイル" "ランナーの後ろに付ける" "実行ファイル")
  (ul (li "Linux では " (tt "stub ++ module ++ 長さ ++ \"RLNATIVE\"")
          "、macOS では署名した Mach-O の中にモジュールを置く")
      "プリコンパイラとランナーは同じ wasmtime の設定で作り、指紋が違えば書き出す前に拒否する"
      (li "Rust のワークスペース (" (tt "rontolisp-native/") ") は Maven の外にあり、コアは外部依存を持たないままになる"))
  (source ".kb/native-output.md"))

(slide :title "ランナーの大きさ"
  (bars :unit " MB" ("Linux x86_64" "2.1") ("Linux aarch64" "1.9") ("macOS" "1.7"))
  (ul (li "実行ファイルは、ランナーに " (tt ".wasm") " の約 11 倍を足した大きさ")
      (li "起動は約 10 ms。同じモジュールを " (tt "wasmtime run") " で動かすのとほぼ同じ速度で動く"))
  (source "doc/ja/compiling/native.md (サイズと速度)"))

;;; --- the Scheme and Clojure front ends ---------------------------------------------

(section "三つの言語、一つの IR")

(slide :title "Scheme と Clojure もコアフォームに下げる"
  (ul "Scheme や Clojure のために、バックエンドへ名前を足すことはない"
      (li "バックエンドは演算子の名前で分岐し、" (tt "FreeVarAnalyzer")
          " も同じ名前を見る手書きの走査")
      "走査が扱わない先頭のフォームは、エラーにならずに三通りの結果に分かれる"
      "実行時の補助関数は Common Lisp で書き、使わない定義は刈り込む")
  (source ".kb/scheme-frontend.md, .kb/clojure-frontend.md"))

(slide :title "Scheme の末尾再帰"
  (code :lang "scheme" :run nil "
    (define (sum n acc)
      (if (= n 0)
          acc
          (sum (- n 1) (+ acc n))))")
  (source ".kb/scheme-frontend.md"))

(slide :title "Scheme の末尾再帰" :morph t
  (code :lang "lisp" :run nil "
    (defun |sum| (%scm-c1 %scm-c2)
      (let ((|n| %scm-c1) (|acc| %scm-c2) (%scm-r4 nil))
        (tagbody
         %scm-l3
           (if (= |n| 0)
               (setq %scm-r4 |acc|)
               (progn
                 (let ((|__psetq_0| (- |n| 1))
                       (|__psetq_1| (+ |acc| |n|)))
                   (setq |n| |__psetq_0|)
                   (setq |acc| |__psetq_1|)
                   nil)
                 (go %scm-l3))))
        %scm-r4))")
  (source (tt "rontolisp sum.scm --dump-ir") " の出力を小文字にして改行した (2026-10-23)"))

(slide :title "同じインタプリタで動かす"
  (column
    (code :lang "scheme" "
      (define (sum n acc)
        (if (= n 0)
            acc
            (sum (- n 1) (+ acc n))))
      (display (sum 100000 0))"))
  (column
    (code :lang "clojure" "
      (loop [i 0 acc 0]
        (if (= i 100000)
          acc
          (recur (inc i) (+ acc i))))"))
  (p "このページのランタイムは、三つのフロントエンドを持つインタプリタ一つ。Run で 5000050000 と 4999950000 が返る。")
  (source "lispresen/DESIGN.md (Runtime)"))

(slide :title "Clojure を clj と突き合わせる"
  (lead "117 の式を clj 1.12.6.1673 と各バックエンドで評価し、印字結果を比べる。2026-10-21 の初回の実行では、四つのバックエンドすべてで差がなかった")
  (bars ("clojure.core" 89) ("clojure.string" 16) ("clojure.set" 7)
        ("clojure.walk" 2) ("clojure.pprint" 2) ("clojure.data" 1))
  (source ".kb/clojure-frontend.md (Differential parity harness)"))

;;; --- verification and dependencies -------------------------------------------------

(section "検証と依存")

(slide :title "「検証した」は四つで動かしたこと"
  (code :lang "console" "
    $ java -jar $JAR test.lisp
    $ java -jar $JAR test.lisp -o Prog.class && java Prog
    $ java -jar $JAR test.lisp -o test.wasm && wasmtime run test.wasm
    $ java -jar $JAR test.lisp -o c.wasm --component && wasmtime run c.wasm")
  (p "コンポーネントは別の I/O アダプタを使うので、Preview 1 と食い違うことがある。")
  (source ".kb/running-backends.md, CLAUDE.md"))

(slide :title "ci-spec.yaml は一つのプログラムとして流す"
  (ul "ケースをつなげて一つのプログラムにし、バックエンドごとに一度だけ実行して、出力をケースに切り分ける"
      (li "各バックエンドで、既定と " (tt "--simd") " の二回流す")
      (li (tt "./mvnw test") " は " (tt "CiSpecE2eTest") " を飛ばす。CI のネイティブジョブで動く")
      "2026-10-17 に、コーパスと重複する 373 のテストメソッドを消し、JVM と WASM で対になった 298 組のテストをケースに移した")
  (source "src/test/resources/ci-spec.yaml (1,354 ケース、2026-10-23 に数えた), .kb/test-execution.md"))

(slide :title "出力はプログラムだけで決まる"
  (ul "同じソースと同じビルドなら、何度コンパイルしても同じバイト列になる"
      (li (tt "Map.of") " や " (tt "Set.of") " の反復の順序は、JVM を起動するたびに変わる")
      (li "6 行のプログラムを 8 回コンパイルしたら、" (tt ".class") " も " (tt ".wasm") " も 3 通りのバイト列になったことがある")
      (li "順序を持つ引数は " (tt "SequencedSet") " で型付けする"))
  (source ".kb/emitted-output-determinism.md"))

(slide :title "コアは外部依存を持たない"
  (column
    (code "
      cli -> eval, compiler, codegen.*, ...
      codegen.jvm -> compiler, runtime, ...
      codegen.wasm -> compiler, am.ik.wasm, ...
      eval -> macro, compiler, reader, ...
      macro -> reader
      runtime -> (nothing)"))
  (column
    (ul (li "reader、eval、codegen、" (tt "am.ik.*") " は外部ライブラリを使わない")
        (li (tt "runtime") " は出力にコピーされるので何も import しない")
        (li "循環がないことを " (tt "PackageCycleTest") " が確かめる")))
  (source ".kb/architecture.md (Package dependency direction, Requirements)"))

(slide :title "ブラウザで動く rontolisp"
  (ul (li "GraalVM Native Image の Web Image で、rontolisp 自体を WebAssembly にする")
      "インタプリタも、JVM と WASM のコンパイラも、ブラウザの中で動く"
      (li "ファイルシステムがないので、ファイルを読むコンパイル時のパスは " (tt "SourceLoader") " を通す")
      "このデッキの Run も、このブラウザ版 (約 35 MB) で動いている")
  (source "web/README.md, .kb/architecture.md, lispresen/README.md"))

(slide "どの経路を通っても同じ答えを出すことを、一つのコーパスで確かめ続ける。")
