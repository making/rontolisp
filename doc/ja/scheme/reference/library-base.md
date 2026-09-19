# (scheme base)

`(scheme base)` の手続きです。構文キーワードは[構文](syntax.md)にあります。

## 等価性

| 名前 | 例 | 結果 |
|---|---|---|
| `eq?` | `(eq? 'a 'a)` | `#t` |
| `eqv?` | `(eqv? 1.5 1.5)` | `#t` |
| `equal?` | `(equal? '(1 (2 #(3))) '(1 (2 #(3))))` | `#t` |

## 数値

| 名前 | 例 | 結果 |
|---|---|---|
| `+` | `(+ 1 2 3)` | `6` |
| `-` | `(- 10 3 2)` | `5` |
| `*` | `(* 2 3 4)` | `24` |
| `/` | `(/ 1 3)` | `1/3` |
| `=` | `(= 1 1.0)` | `#t` |
| `<` | `(< 1 2 3)` | `#t` |
| `>` | `(> 3 2 1)` | `#t` |
| `<=` | `(<= 1 1 2)` | `#t` |
| `>=` | `(>= 2 2 1)` | `#t` |
| `quotient` | `(quotient 17 5)` | `3` |
| `remainder` | `(remainder 17 5)` | `2` |
| `modulo` | `(modulo -17 5)` | `3` |
| `truncate-quotient` | `(truncate-quotient -7 2)` | `-3` |
| `truncate-remainder` | `(truncate-remainder -7 2)` | `-1` |
| `floor-quotient` | `(floor-quotient -7 2)` | `-4` |
| `floor-remainder` | `(floor-remainder -7 2)` | `1` |
| `abs` | `(abs -5)` | `5` |
| `min` | `(min 3 1 2)` | `1` |
| `max` | `(max 1 5 3)` | `5` |
| `gcd` | `(gcd 12 18)` | `6` |
| `lcm` | `(lcm 4 6)` | `12` |
| `expt` | `(expt 2 10)` | `1024` |
| `square` | `(square 5)` | `25` |
| `floor` | `(floor 2.5)` | `2.0` |
| `ceiling` | `(ceiling 2.1)` | `3.0` |
| `round` | `(round 2.5)` | `2.0` |
| `truncate` | `(truncate -2.7)` | `-2.0` |
| `zero?` | `(zero? 0)` | `#t` |
| `positive?` | `(positive? 3)` | `#t` |
| `negative?` | `(negative? -0.5)` | `#t` |
| `odd?` | `(odd? 3)` | `#t` |
| `even?` | `(even? 0)` | `#t` |
| `number?` | `(number? 1/2)` | `#t` |
| `real?` | `(real? 1.5)` | `#t` |
| `rational?` | `(rational? 1/3)` | `#t` |
| `integer?` | `(integer? 2.0)` | `#t` |
| `exact?` | `(exact? 1/2)` | `#t` |
| `inexact?` | `(inexact? 1.0)` | `#t` |
| `exact-integer?` | `(exact-integer? 5)` | `#t` |
| `exact` | `(exact 2.5)` | `5/2` |
| `inexact` | `(inexact 1/3)` | `0.3333333333333333` |
| `number->string` | `(number->string 255 16)` | `"ff"` |
| `string->number` | `(string->number "ff" 16)` | `255` |
| `exact-integer-sqrt` | `(exact-integer-sqrt 17)` | `4, 1` |

## 真偽値

| 名前 | 例 | 結果 |
|---|---|---|
| `not` | `(not #f)` | `#t` |
| `boolean?` | `(boolean? #f)` | `#t` |

## ペアとリスト

| 名前 | 例 | 結果 |
|---|---|---|
| `cons` | `(cons 1 2)` | `(1 . 2)` |
| `car` | `(car '(1 2 3))` | `1` |
| `cdr` | `(cdr '(1 2 3))` | `(2 3)` |
| `set-car!` | `(let ((p (list 1 2))) (set-car! p 9) p)` | `(9 2)` |
| `set-cdr!` | `(let ((p (list 1 2))) (set-cdr! p '(3 4)) p)` | `(1 3 4)` |
| `caar` | `(caar '((1 2) 3))` | `1` |
| `cadr` | `(cadr '(1 2 3))` | `2` |
| `cdar` | `(cdar '((1 2) 3))` | `(2)` |
| `cddr` | `(cddr '(1 2 3))` | `(3)` |
| `list` | `(list 1 2 3)` | `(1 2 3)` |
| `length` | `(length '(1 2 3))` | `3` |
| `append` | `(append '(1 2) '(3) '(4 5))` | `(1 2 3 4 5)` |
| `reverse` | `(reverse '(1 2 3))` | `(3 2 1)` |
| `list-tail` | `(list-tail '(a b c d) 2)` | `(c d)` |
| `list-ref` | `(list-ref '(a b c d) 2)` | `c` |
| `list-copy` | `(list-copy '(1 2 3))` | `(1 2 3)` |
| `memq` | `(memq 'c '(a b c d))` | `(c d)` |
| `memv` | `(memv 101 '(100 101 102))` | `(101 102)` |
| `member` | `(member (list 'a) '(b (a) c))` | `((a) c)` |
| `assq` | `(assq 'b '((a 1) (b 2)))` | `(b 2)` |
| `assv` | `(assv 5 '((2 3) (5 7) (11 13)))` | `(5 7)` |
| `assoc` | `(assoc "b" '(("a" . 1) ("b" . 2)))` | `("b" . 2)` |
| `null?` | `(null? '())` | `#t` |
| `pair?` | `(pair? '(a . b))` | `#t` |
| `list?` | `(list? '(a b c))` | `#t` |

## シンボル

| 名前 | 例 | 結果 |
|---|---|---|
| `symbol?` | `(symbol? 'foo)` | `#t` |
| `symbol->string` | `(symbol->string 'flying-fish)` | `"flying-fish"` |
| `string->symbol` | `(string->symbol "abc")` | `abc` |

## 文字

| 名前 | 例 | 結果 |
|---|---|---|
| `char?` | `(char? #\a)` | `#t` |
| `char->integer` | `(char->integer #\A)` | `65` |
| `integer->char` | `(integer->char 97)` | `#\a` |
| `char=?` | `(char=? #\a #\a #\a)` | `#t` |
| `char<?` | `(char<? #\a #\b #\c)` | `#t` |
| `char>?` | `(char>? #\b #\a)` | `#t` |
| `char<=?` | `(char<=? #\a #\a #\b)` | `#t` |
| `char>=?` | `(char>=? #\c #\b #\b)` | `#t` |

## 文字列

| 名前 | 例 | 結果 |
|---|---|---|
| `string?` | `(string? "abc")` | `#t` |
| `make-string` | `(make-string 3 #\x)` | `"xxx"` |
| `string` | `(string #\a #\b)` | `"ab"` |
| `string-length` | `(string-length "hello")` | `5` |
| `string-ref` | `(string-ref "hello" 1)` | `#\e` |
| `string-set!` | `(let ((s (make-string 3 #\a))) (string-set! s 1 #\b) s)` | `"aba"` |
| `string=?` | `(string=? "abc" "abc" "abc")` | `#t` |
| `string<?` | `(string<? "abc" "abd")` | `#t` |
| `string>?` | `(string>? "b" "a")` | `#t` |
| `string<=?` | `(string<=? "a" "a" "b")` | `#t` |
| `string>=?` | `(string>=? "b" "a")` | `#t` |
| `substring` | `(substring "hello" 1 3)` | `"el"` |
| `string-append` | `(string-append "foo" "bar" "baz")` | `"foobarbaz"` |
| `string-copy` | `(string-copy "hello" 1)` | `"ello"` |
| `string->list` | `(string->list "abc")` | `(#\a #\b #\c)` |
| `list->string` | `(list->string '(#\a #\b))` | `"ab"` |

## ベクタ

| 名前 | 例 | 結果 |
|---|---|---|
| `vector?` | `(vector? #(1 2))` | `#t` |
| `make-vector` | `(make-vector 3 'x)` | `#(x x x)` |
| `vector` | `(vector 1 "a" #\b)` | `#(1 "a" #\b)` |
| `vector-length` | `(vector-length #(1 2 3))` | `3` |
| `vector-ref` | `(vector-ref #(a b c) 1)` | `b` |
| `vector-set!` | `(let ((v (vector 1 2 3))) (vector-set! v 0 'x) v)` | `#(x 2 3)` |
| `vector->list` | `(vector->list #(1 2 3))` | `(1 2 3)` |
| `list->vector` | `(list->vector '(1 2 3))` | `#(1 2 3)` |
| `vector-fill!` | `(let ((v (make-vector 3 0))) (vector-fill! v 7) v)` | `#(7 7 7)` |

## バイトベクタ

| 名前 | 例 | 結果 |
|---|---|---|
| `bytevector?` | `(bytevector? #u8(1 2))` | `#t` |
| `make-bytevector` | `(make-bytevector 3 7)` | `#u8(7 7 7)` |
| `bytevector` | `(bytevector 1 2 255)` | `#u8(1 2 255)` |
| `bytevector-length` | `(bytevector-length #u8(1 2 3))` | `3` |
| `bytevector-u8-ref` | `(bytevector-u8-ref #u8(10 20 30) 1)` | `20` |
| `bytevector-u8-set!` | `(let ((b (bytevector 1 2 3))) (bytevector-u8-set! b 0 255) b)` | `#u8(255 2 3)` |
| `bytevector-copy` | `(bytevector-copy #u8(1 2 3 4 5) 1 3)` | `#u8(2 3)` |
| `bytevector-copy!` | `(let ((b (bytevector 1 2 3 4 5))) (bytevector-copy! b 1 #u8(9 8 7) 0 2) b)` | `#u8(1 9 8 4 5)` |
| `bytevector-append` | `(bytevector-append #u8(1) #u8() #u8(2 3))` | `#u8(1 2 3)` |
| `utf8->string` | `(utf8->string #u8(65 66 67 227 129 130))` | `"ABCあ"` |
| `string->utf8` | `(string->utf8 "λx")` | `#u8(206 187 120)` |

## 制御

| 名前 | 例 | 結果 |
|---|---|---|
| `procedure?` | `(procedure? car)` | `#t` |
| `apply` | `(apply + 1 2 '(3 4))` | `10` |
| `map` | `(map (lambda (x) (* x x)) '(1 2 3))` | `(1 4 9)` |
| `for-each` | `(for-each (lambda (x y) (display (+ x y)) (newline)) '(1 2) '(10 20))` | `11` と `22` を 1 行ずつ出力 |
| `call/cc` | `(call/cc (lambda (k) (+ 1 (k 42))))` | `42` |
| `call-with-current-continuation` | `(call-with-current-continuation (lambda (return) (for-each (lambda (x) (if (negative? x) (return x))) '(1 -2 3)) 'none))` | `-2` |
| `dynamic-wind` | `(dynamic-wind (lambda () (display "before ")) (lambda () (display "during ")) (lambda () (display "after")))` | `before during after` を出力 |
| `values` | `(values 1 2)` | `1, 2` |
| `call-with-values` | `(call-with-values (lambda () (values 1 2)) cons)` | `(1 . 2)` |
| `make-parameter` | `((make-parameter 5 (lambda (x) (* x 2))))` | `10` |

## 例外

| 名前 | 例 | 結果 |
|---|---|---|
| `error` | `(guard (e (#t (error-object-message e))) (error "division by zero:" 1))` | `"division by zero:"` |
| `raise` | `(guard (e (#t (list 'caught e))) (raise 42))` | `(caught 42)` |
| `raise-continuable` | `(with-exception-handler (lambda (e) 10) (lambda () (+ 1 (raise-continuable 'oops))))` | `11` |
| `with-exception-handler` | `(call/cc (lambda (k) (with-exception-handler (lambda (e) (k (list 'handled e))) (lambda () (raise 'boom)))))` | `(handled boom)` |
| `error-object?` | `(guard (e (#t (error-object? e))) (error "bad"))` | `#t` |
| `error-object-message` | `(guard (e (#t (error-object-message e))) (error "bad thing:" 1 2))` | `"bad thing:"` |
| `error-object-irritants` | `(guard (e (#t (error-object-irritants e))) (error "bad thing:" 1 2))` | `(1 2)` |
| `read-error?` | `(guard (e (#t (read-error? e))) (error "not a read error"))` | `#f` |
| `file-error?` | `(guard (e (#t (file-error? e))) (raise 'oops))` | `#f` |

## ポート

| 名前 | 例 | 結果 |
|---|---|---|
| `current-input-port` | `(parameterize ((current-input-port (open-input-string "(1 2) x"))) (read))` | `(1 2)` |
| `current-output-port` | `(let ((p (open-output-string))) (parameterize ((current-output-port p)) (display "hi")) (get-output-string p))` | `"hi"` |
| `current-error-port` | `(output-port? (current-error-port))` | `#t` |
| `port?` | `(port? (open-input-string "x"))` | `#t` |
| `input-port?` | `(input-port? (open-output-string))` | `#f` |
| `output-port?` | `(output-port? (open-output-string))` | `#t` |
| `textual-port?` | `(textual-port? (open-input-string "x"))` | `#t` |
| `binary-port?` | `(binary-port? (open-input-bytevector #u8(1)))` | `#t` |
| `input-port-open?` | `(let ((p (open-input-string "x"))) (close-port p) (input-port-open? p))` | `#f` |
| `output-port-open?` | `(output-port-open? (open-output-string))` | `#t` |
| `close-port` | `(let ((p (open-output-string))) (close-port p) (output-port-open? p))` | `#f` |
| `close-input-port` | `(let ((p (open-input-string "x"))) (close-input-port p) (input-port-open? p))` | `#f` |
| `close-output-port` | `(let ((p (open-output-string))) (close-output-port p) (output-port-open? p))` | `#f` |
| `call-with-port` | `(call-with-port (open-input-string "(1 2)") read)` | `(1 2)` |
| `open-input-string` | `(read (open-input-string "(a . b) c"))` | `(a . b)` |
| `open-output-string` | `(let ((p (open-output-string))) (write 'x p) (write "y" p) (get-output-string p))` | `"x\"y\""` |
| `get-output-string` | `(let ((p (open-output-string))) (display "ab" p) (get-output-string p))` | `"ab"` |
| `open-input-bytevector` | `(read-u8 (open-input-bytevector #u8(7 8)))` | `7` |
| `open-output-bytevector` | `(let ((p (open-output-bytevector))) (write-u8 1 p) (write-u8 2 p) (get-output-bytevector p))` | `#u8(1 2)` |
| `get-output-bytevector` | `(let ((p (open-output-bytevector))) (write-bytevector #u8(5 6) p) (get-output-bytevector p))` | `#u8(5 6)` |

## 出力

| 名前 | 例 | 結果 |
|---|---|---|
| `newline` | `(newline)` | 現在の行を終える |
| `write-char` | `(write-char #\a)` | `a` を出力 |
| `write-string` | `(write-string "hello")` | `hello` を出力 |
| `write-u8` | `(let ((p (open-output-bytevector))) (write-u8 255 p) (get-output-bytevector p))` | `#u8(255)` |
| `write-bytevector` | `(let ((p (open-output-bytevector))) (write-bytevector #u8(1 2 3 4) p 1 3) (get-output-bytevector p))` | `#u8(2 3)` |
| `flush-output-port` | `(flush-output-port)` | 出力ポートが溜めているものを書き出す |

## 入力

| 名前 | 例 | 結果 |
|---|---|---|
| `read-char` | `(read-char)` | 入力 `ab` に対して `#\a`、次に `#\b` |
| `peek-char` | `(peek-char)` | 入力 `xy` に対して `#\x`（続けて呼んでも同じ） |
| `read-line` | `(read-line)` | 入力 `first line` に対して `"first line"` |
| `char-ready?` | `(char-ready?)` | `#t` |
| `read-string` | `(read-string 3 (open-input-string "abcdef"))` | `"abc"` |
| `read-u8` | `(read-u8 (open-input-bytevector #u8(1 2)))` | `1` |
| `peek-u8` | `(let ((p (open-input-bytevector #u8(9)))) (list (peek-u8 p) (read-u8 p)))` | `(9 9)` |
| `u8-ready?` | `(u8-ready? (open-input-bytevector #u8()))` | `#t` |
| `read-bytevector` | `(read-bytevector 2 (open-input-bytevector #u8(1 2 3)))` | `#u8(1 2)` |
| `read-bytevector!` | `(let ((b (make-bytevector 4 0))) (read-bytevector! b (open-input-bytevector #u8(7 8)) 1) b)` | `#u8(0 7 8 0)` |
| `eof-object` | `(write (eof-object))` | `#<eof>` を出力 |
| `eof-object?` | `(eof-object? (eof-object))` | `#t` |

## システムインターフェース

| 名前 | 例 | 結果 |
|---|---|---|
| `features` | `(features)` | `(r7rs exact-closed ieee-float full-unicode ratios rontolisp)` |
