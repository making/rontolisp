# make-array

`(make-array dimensions &key initial-element initial-contents element-type fill-pointer adjustable displaced-to displaced-index-offset)`

新しい配列を作成して返します。`dimensions` はランク 1 のベクタの場合は整数、任意のランクの配列の場合は整数のリストです。**空のリスト** (`nil`) も指定でき、その場合は**ランク 0 の配列**になります。これは Common Lisp における「スカラーを配列として見た箱」であり、要素を 1 つだけ保持し、`aref` は添字なしでその要素を読み書きします。印字形式は `#0A<datum>` で、リーダはこれをそのまま読み戻せます (`#0A5`、`#0A(1 2)` はリストを保持するランク 0 の配列)。`:initial-element` はすべてのセルを指定した値に設定します。デフォルトは nil です。要素は行優先で格納され、`aref` を介して O(1) でアクセスできます。配列は同一性 (`eq`) で比較されるため、異なる 2 つの配列が `equal` になることはありません。`make-array` と `aref` は第一級の関数値ではありません。`#'make-array` は利用できないため、直接呼び出してください。

`:fill-pointer` (ランク 1 のみ) はベクタに[フィルポインタ](fill-pointer.md)を与えます。整数はその位置に、`t` はベクタサイズに設定します。フィルポインタは実効長であり、`length` や印字はフィルポインタで止まります (`aref` はストレージ全体にアクセスできます)。[`vector-push`](vector-push.md)/[`vector-pop`](vector-pop.md)/[`vector-push-extend`](vector-push-extend.md) が操作するのもこのフィルポインタです。`:adjustable` は配列を可変長としてマークし、[`adjustable-array-p`](adjustable-array-p.md) がそのまま報告します。可変長配列は [`adjust-array`](adjust-array.md) でその場でリサイズされます。`:initial-contents` は (入れ子の場合もある) シーケンスから配列を充填します (row-major。すべてのバックエンドで、任意のランクに対応)。`:element-type 'double-float`/`'single-float` (フィルポインタ/可変長/displacement なし) はパックド浮動小数点表現を選択し、同じ条件の `:element-type 'character` は**文字列**を作ります (ランク 1 の文字配列は文字列そのものであり、[`make-string`](make-string.md) の結果と同じ形です)。`:fill-pointer`/`:adjustable` **付き**の `:element-type 'character` は、すべてのバックエンドでフィルポインタ付きの可変文字列を作ります: `vector-push-extend` で文字を追加でき、`replace` と `(setf (char ...))` はその場に書き込み、文字列として印字・比較 (`string=`/`equal`、`equal` ハッシュ表のキー) され、`stringp` を満たします。`:initial-contents` 付きの `:element-type 'character` は内容 (文字列、可変文字列、または文字のリスト) を新しい単純文字列にコピーします。これら 3 つの文字用の形はいずれもランク 1 限定です。文字列はランク 1 の文字配列であり、それ以外ではありえないからです: ランク 2 以上の `dimensions` に `:element-type 'character` を与えると通常の一般配列になり (`stringp` と `vectorp` は nil)、その未指定要素は依然として文字であり `#\Space` が既定値になります。**専用の表現を選ばない要素型も記憶されます**。ランク 2 以上、あるいは `:fill-pointer`/`:adjustable` との組み合わせでは配列は一般表現になりますが、[`array-element-type`](array-element-type.md) は `character` / `(unsigned-byte n)` / `single-float` / `double-float` を返し、[`type-of`](type-of.md) はそこから複合指定子を組み立て、値を与えられなかった要素は `nil` ではなくその型のゼロ (`#\Space`、`0`、`0.0`) になります。ランク 1 の配列に対する`:element-type '(unsigned-byte 8)`、`'(unsigned-byte 16)`、`'(unsigned-byte 32)` (同じくフィルポインタ/可変長/displacement なし) はパックド符号なし整数ベクタを選択します: 格納は値を要素幅にマスクし (2 の補数での切り詰め)、読み出しは符号なしに拡大して返し、整数以外の格納はエラーになります。[`array-element-type`](array-element-type.md) は実際の `(unsigned-byte n)` 指定子を報告します。パックドベクタの [`subseq`](subseq.md) と `copy-seq` は同じ幅のパックドベクタのままです。引数なしの [`deftype`](../macros/deftype.md) 名はこれらの判定より先に解決されるため、別名を書いても展開先を書いたときとまったく同じ表現が選ばれます。`:element-type` はリテラルで書く必要はありません。実行時に計算された指定子 (変数、`(stream-element-type s)` の呼び出し) は、その値をリテラルで書いた場合とまったく同じものを、すべてのバックエンドで選択します。例外は 1 つだけで、`deftype` の別名が実行時の値として渡された場合はインタプリタしか解決しません (別名はリテラルで書けばすべてのバックエンドが解決します)。それ以外の要素型 (`fixnum`、`integer`、`bit`、クラス) は受け付けられますが、アップグレード先の表現がないため配列は一般表現になり、[`array-element-type`](array-element-type.md) は `t` を返します。

`:displaced-to` は、ストレージを割り当てる代わりに別の配列のストレージへのビューを構築します。ビューの (行優先の) 要素 `i` はターゲットの要素 `i + offset` を読み書きします (`:displaced-index-offset` のデフォルトは 0)。変更は双方向に見えます。ビューは独自の次元を持ち (ターゲットとランクが異なってもよく、例えば行列の行に対するベクタビューが作れます)、ターゲット内に収まる必要があり、[`array-displacement`](array-displacement.md) で調べられます。displaced ビューは `:fill-pointer`/`:adjustable`/`:initial-element` と併用できず、それ自体を adjust することもできません。

**文字列**に対して displace すると文字列ビューになります。形を決めるのは要素型ではなくターゲットなので、結果は `stringp` を満たし、オフセット以降のターゲットの文字を持ち、文字列として印字・比較され、`subseq`/`char`/`length` はその範囲を見ます。コピーは発生しません。移植性のあるライブラリが部分文字列を共有するのはこの形です。ビュー経由の書き込みはターゲットに書き込まれます (ビューのビューも同じ文字に届きます)。実行中のプログラムが割り当てた文字列 ([`make-string`](make-string.md) バッファ、`copy-seq`/[`subseq`](subseq.md) の切り出し、`concatenate 'string` / [`string-upcase`](string-upcase.md) / `format nil` / [`with-output-to-string`](../macros/with-output-to-string.md) / `read-line` の結果) はすべてのバックエンドで可変であり、そのビュー経由の書き込みはターゲットに届きます。コンパイル済みバックエンドでは、文字列**リテラル** (および `princ-to-string` のような、そこでまだ不変値を返す少数のプロデューサの結果) は書き込みの届かない不変値です。ビューはそれをコピーせずに読み、ビュー経由の最初の書き込みでビューは可変コピーに移り、元の文字列はそのまま残ります。これはその文字列に対する `(setf (char s i) c)` の挙動とまったく同じです。

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (aref a 0)) ; => 0
(length (make-array 5 :fill-pointer 2 :initial-element 0)) ; => 2
(let* ((base (make-array 4 :initial-element 1))
       (view (make-array 2 :displaced-to base :displaced-index-offset 1)))
  (setf (aref view 0) 9)
  (aref base 1)) ; => 9
(let* ((s (make-string 3 :initial-element #\a))
       (view (make-array 2 :element-type 'character :displaced-to s
                           :displaced-index-offset 1)))
  (setf (char view 0) #\X)
  (list view s)) ; => ("Xa" "aXa")
(let ((z (make-array nil :initial-element 5)))
  (list (array-rank z) (array-dimensions z) (array-total-size z) (aref z))) ; => (0 NIL 1 5)
(let ((z (make-array nil)))
  (setf (aref z) 7)
  z) ; => #0A7
(let ((bytes (make-array 3 :element-type '(unsigned-byte 8))))
  (setf (aref bytes 0) 300) ; stores 300 mod 256
  (aref bytes 0)) ; => 44
(progn
  (deftype octet () '(unsigned-byte 8))
  (array-element-type (make-array 3 :element-type 'octet))) ; => (UNSIGNED-BYTE 8)
```
