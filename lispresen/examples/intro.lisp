;;;; intro.lisp -- a deck presenting lispresen itself, and the golden input of check.sh.

(deck :title "lispresen: S式で書くプレゼンテーション"
      :author "rontolisp"
      :date "2026-10-22"
      :theme :light
      :page-numbers t)

(section "なぜS式で書くのか")

(slide :title "スライドはデータ"
  (ul "差分をコードと同じようにレビューできる"
      (li "構造が明示される"
          (ul "インデントに意味を持たせない"
              "見えないスタイルがない"))
      "生成したデッキもただのリスト")
  (notes "デッキは read するだけで評価しないことに触れる。"))

(slide "読みやすさは、書き手ではなくツールが守る。")

(slide :title "文字の大きさは自動で決まる"
  (p "スライドごとに、収まる最大の段を " (b "段階表") " から選ぶ。")
  (ol "本文は 56px から 40px まで"
      "最小でも収まらなければエラーとして報告する"
      (li "日本語は " (mark "文節") " で改行する")))

(slide :title "デッキの読み方"
  (p "トップレベルのフォームを一つずつ " (tt "read-from-string") " で読む。")
  (code :lang "lisp" "
    (multiple-value-bind (form end)
        (read-from-string source t nil :start pos)
      (push (list form line) forms)
      (setf pos end))"))

(slide :title "バックエンドの比較"
  (table :header ("バックエンド" "起動" "成果物" "コマンド")
         :caption "どれも同じ HTML を出力する"
    ("インタプリタ" "速い" "なし" (tt "rontolisp cli.lisp"))
    ("JVM" "普通" (tt ".jar") (tt "java -jar"))
    ("WASM" "速い" (tt ".wasm") (tt "wasmtime run"))))

(slide :title "表の数値は右に揃う"
  (table :header ("項目" "前年" "今年" "増減")
         :row-header t
    ("スライド数" "24" "31" "+7")
    ("平均文字数" "1,204" "986" "-18.1%")
    ("警告" "12" "--" "-12")))
