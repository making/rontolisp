# (scheme base)

The procedures of `(scheme base)`. Its syntactic keywords are on [Syntax](syntax.md).

## Equivalence

| Name | Example | Result |
|---|---|---|
| `eq?` | `(eq? 'a 'a)` | `#t` |
| `eqv?` | `(eqv? 1.5 1.5)` | `#t` |
| `equal?` | `(equal? '(1 (2 #(3))) '(1 (2 #(3))))` | `#t` |

## Numbers

| Name | Example | Result |
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

## Booleans

| Name | Example | Result |
|---|---|---|
| `not` | `(not #f)` | `#t` |
| `boolean?` | `(boolean? #f)` | `#t` |

## Pairs and lists

| Name | Example | Result |
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

## Symbols

| Name | Example | Result |
|---|---|---|
| `symbol?` | `(symbol? 'foo)` | `#t` |
| `symbol->string` | `(symbol->string 'flying-fish)` | `"flying-fish"` |
| `string->symbol` | `(string->symbol "abc")` | `abc` |

## Characters

| Name | Example | Result |
|---|---|---|
| `char?` | `(char? #\a)` | `#t` |
| `char->integer` | `(char->integer #\A)` | `65` |
| `integer->char` | `(integer->char 97)` | `#\a` |
| `char=?` | `(char=? #\a #\a #\a)` | `#t` |
| `char<?` | `(char<? #\a #\b #\c)` | `#t` |
| `char>?` | `(char>? #\b #\a)` | `#t` |
| `char<=?` | `(char<=? #\a #\a #\b)` | `#t` |
| `char>=?` | `(char>=? #\c #\b #\b)` | `#t` |

## Strings

| Name | Example | Result |
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

## Vectors

| Name | Example | Result |
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

## Control

| Name | Example | Result |
|---|---|---|
| `procedure?` | `(procedure? car)` | `#t` |
| `apply` | `(apply + 1 2 '(3 4))` | `10` |
| `map` | `(map (lambda (x) (* x x)) '(1 2 3))` | `(1 4 9)` |
| `for-each` | `(for-each (lambda (x y) (display (+ x y)) (newline)) '(1 2) '(10 20))` | prints `11` and `22`, one per line |
| `call/cc` | `(call/cc (lambda (k) (+ 1 (k 42))))` | `42` |
| `call-with-current-continuation` | `(call-with-current-continuation (lambda (return) (for-each (lambda (x) (if (negative? x) (return x))) '(1 -2 3)) 'none))` | `-2` |
| `dynamic-wind` | `(dynamic-wind (lambda () (display "before ")) (lambda () (display "during ")) (lambda () (display "after")))` | prints `before during after` |
| `values` | `(values 1 2)` | `1, 2` |
| `call-with-values` | `(call-with-values (lambda () (values 1 2)) cons)` | `(1 . 2)` |
| `error` | `(error "division by zero:" a)` | ends the program, reporting `division by zero: 1` for `a` = 1 |

## Output

| Name | Example | Result |
|---|---|---|
| `newline` | `(newline)` | ends the current line |
| `write-char` | `(write-char #\a)` | prints `a` |
| `write-string` | `(write-string "hello")` | prints `hello` |

## Input

| Name | Example | Result |
|---|---|---|
| `read-char` | `(read-char)` | `#\a`, then `#\b`, on input `ab` |
| `peek-char` | `(peek-char)` | `#\x` on input `xy`, twice in a row |
| `read-line` | `(read-line)` | `"first line"` on input `first line` |
| `char-ready?` | `(char-ready?)` | `#t` |
| `eof-object` | `(write (eof-object))` | prints `#<eof>` |
| `eof-object?` | `(eof-object? (eof-object))` | `#t` |
