# Functions

This page is the quick-reference table. **Each function name in the table links
to its own page**, which has a fuller description and a runnable example you can
evaluate in your browser. Cross-cutting topics have their own homes: the
`make-array`/`aref` and hash-table operators are described under
[Arrays](data-types.md#arrays) and [Hash tables](data-types.md#hash-tables) on
the Data Types page, and each function's deviations from Common Lisp are noted on
its own page.

| Function | Example | Result |
|----------|---------|--------|
| `+` | `(+ 1 2 3)`, `(+ 1.5 2.5)` | `6`, `4.0` |
| `-` | `(- 10 3)`, `(- 3.5 1.5)` | `7`, `2.0` |
| `*` | `(* 3 4)`, `(* 2.0 3.0)` | `12`, `6.0` |
| `/` | `(/ 1 2)`, `(/ 10 2)`, `(/ 7.0 2.0)` | `1/2` (exact ratio), `5`, `3.5` |
| `mod` | `(mod 10 3)`, `(mod -13 4)` | `1`, `3` (result takes the sign of the divisor) |
| `rem` | `(rem 13 4)`, `(rem -13 4)` | `1`, `-1` (result takes the sign of the dividend) |
| `=` | `(= 1 1)`, `(= 3 3 3)` | `t` (variadic) |
| `eq` | `(eq 'foo 'foo)`, `(eq 1.5 1.5)` | `t`, `nil` (object identity: symbols and small integers compare equal, but floats and ratios are distinct objects, so never `eq`; reference identity for cons cells) |
| `eql` | `(eql 1.5 1.5)`, `(eql 3 3.0)` | `t`, `nil` (like `eq`, but numbers of the same type and value are equal — e.g. floats and ratios) |
| `equal` | `(equal '(1 2 (3)) '(1 2 (3)))`, `(equal "abc" "abc")` | `t`, `t` (structural equality: cons cells compared recursively by car and cdr, otherwise like `eql`) |
| `<` | `(< 1 2)`, `(< 1 2 3)` | `t` (variadic; true when strictly increasing) |
| `>` | `(> 2 1)`, `(> 3 2 1)` | `t` (variadic) |
| `<=` | `(<= 1 1)` | `t` (variadic) |
| `>=` | `(>= 2 1)` | `t` (variadic) |
| `print` | `(print 42)` | Prints `42` with a newline |
| `prin1` | `(prin1 42)` | Like `print` but without newline |
| `princ` | `(princ "hello")` | Prints without quotes and without newline |
| `terpri` | `(terpri)` | Prints a newline only |
| `fresh-line` | `(fresh-line)` | Prints a newline only if standard output is not already at the start of a line. Returns nil |
| `princ-to-string` | `(princ-to-string '(1 "x"))` | `"(1 x)"` -- the string `princ` would print |
| `prin1-to-string` | `(prin1-to-string "abc")` | `"\"abc\""` -- the string `prin1` would print (readable form) |
| `concatenate` | `(concatenate 'string "foo" "bar")` | `"foobar"` (only the `'string` result type is supported; the compilers require the literal `'string`) |
| `string-upcase` | `(string-upcase "abc")` | `"ABC"` (case conversion is ASCII-only in the WASM backend) |
| `string-downcase` | `(string-downcase "ABC")` | `"abc"` |
| `string-capitalize` | `(string-capitalize "hello world")` | `"Hello World"` (first letter of each word) |
| `subseq` | `(subseq "hello" 1 3)` | `"el"` (works on strings and lists, e.g. `(subseq '(1 2 3 4) 1 3)` => `(2 3)`; the `end` argument is optional) |
| `string=` | `(string= "abc" "abc")` | `t` (case-sensitive string equality) |
| `string-equal` | `(string-equal "ABC" "abc")` | `t` (case-insensitive, ASCII) |
| `string-trim` | `(string-trim " " "  hi  ")` | `"hi"` (removes the bag's characters from both ends) |
| `string-left-trim` | `(string-left-trim "x" "xxhi")` | `"hi"` |
| `string-right-trim` | `(string-right-trim "x" "hixx")` | `"hi"` |
| `read-line` | `(read-line)`, `(read-line stream)` | Read one line from stdin (or from an input stream), return as string. `nil` on EOF |
| `open` | `(open "f.txt")`, `(open "f.txt" :output)`, `(open "f.bin" :input '(unsigned-byte 8))` | Open a file and return a stream. The direction must be the literal `:input` (default, read) or `:output` (create/truncate, write); the optional element type must be the literal `'character` (default, text) or `'(unsigned-byte 8)` (binary) |
| `close` | `(close stream)` | Close a stream opened by `open`. Returns `t` |
| `write-line` | `(write-line "hi" stream)`, `(write-line "hi")` | Write the string plus a newline to an output stream (or to standard output). Returns the string |
| `read-byte` | `(read-byte stream)`, `(read-byte stream nil -1)` | Read one byte (0-255) from a binary input stream. At EOF, signal an error, or return `eof-value` when `eof-error-p` is `nil` |
| `write-byte` | `(write-byte 255 stream)` | Write one raw byte (0-255) to a binary output stream. Returns the byte |
| `read-sequence` | `(read-sequence buf stream)`, `(read-sequence buf stream :start 2 :end 4)` | Fill a vector with bytes from a binary input stream. Returns the fill position. `:start`/`:end` must be literal keywords |
| `write-sequence` | `(write-sequence buf stream)`, `(write-sequence buf stream :start 1 :end 3)` | Write a vector of bytes (0-255) to a binary output stream. Returns the sequence. `:start`/`:end` must be literal keywords |
| `read` | `(read)`, `(read stream)` | Read one S-expression from stdin (or from an input stream opened by `open`/`with-open-file`) (all three backends). `nil` on EOF |
| `read-from-string` | `(read-from-string "(+ 1 2)")` | Parse one datum from a string (all three backends). The optional `eof-error-p`/`eof-value` and `:start`/`:end` arguments are not supported |
| `parse-integer` | `(parse-integer "42")`, `(parse-integer "ff" :radix 16)`, `(parse-integer "12x" :junk-allowed t)` | Parse an integer from a string. Supports `:radix` and `:junk-allowed` on all backends; `:start`/`:end` are interpreter-only. Without `:junk-allowed`, trailing non-whitespace is an error |
| `char` `schar` | `(char "hello" 1)` | `#\e` -- the character at a 0-based string index |
| `char-code` | `(char-code #\A)` | `65` -- the code point of a character |
| `code-char` | `(code-char 66)` | `#\B` -- the character with a given code point |
| `char=` `char<` `char<=` | `(char< #\a #\b #\c)` | `t` (variadic comparison by code point) |
| `char-upcase` `char-downcase` | `(char-upcase #\a)` | `#\A` (ASCII case folding in the WASM backend) |
| `characterp` | `(characterp #\a)` | `t` |
| `alpha-char-p` | `(alpha-char-p #\x)`, `(alpha-char-p #\5)` | `t`, `nil` (ASCII letters in the WASM backend) |
| `digit-char-p` | `(digit-char-p #\7)`, `(digit-char-p #\f 16)` | `7`, `15` -- the digit weight in the given radix (default 10), or nil |
| `eval` | `(eval '(+ 1 2))` | Evaluate an expression (all three backends). Returns the result |
| `load` | `(load "bar.lisp")` | Read and evaluate every top-level form in a file in the global environment (all three backends). Returns `t` |
| `require` | `(require :util)`, `(require :util "lib/util.lisp")` | Load a module's file (`<name>.lisp` next to the requiring file, or the explicit path) unless already `provide`d. Returns the module name. On the compile path it must be a literal, top-level form |
| `provide` | `(provide :util)` | Mark a module as loaded so a later `require` of it is a no-op. Returns the module name. On the compile path it must be a literal, top-level form |
| `gensym` | `(gensym)`, `(gensym "tmp")` | `#:g1`, `#:tmp2` -- a fresh symbol for macro temporaries (the counter is program-wide) |
| `macroexpand-1` | `(macroexpand-1 '(unless c x))` | `(if c nil x)` -- expand the top-level form once (user and built-in macros) |
| `macroexpand` | `(macroexpand '(outer 41))` | The full expansion: `macroexpand-1` repeated to a fixpoint |
| `null` | `(null nil)` | `t` |
| `not` | `(not nil)` | `t` (identical to `null`) |
| `atom` | `(atom 1)` | `t` |
| `numberp` | `(numberp 42)` | `t` |
| `integerp` | `(integerp 42)` | `t` |
| `floatp` | `(floatp 3.14)` | `t` |
| `rationalp` | `(rationalp 1/2)` | `t` (integers and ratios) |
| `numerator` | `(numerator 3/4)` | `3` (an integer is its own numerator) |
| `denominator` | `(denominator 3/4)` | `4` (`1` for integers) |
| `symbolp` | `(symbolp 'foo)` | `t` |
| `stringp` | `(stringp "hello")` | `t` |
| `listp` | `(listp '(1 2))` | `t` |
| `consp` | `(consp '(1 2))` | `t` |
| `keywordp` | `(keywordp :foo)` | `t` |
| `cons` | `(cons 1 2)` | `(1 . 2)` |
| `car` | `(car (cons 1 2))` | `1` (`(car nil)` is `nil`) |
| `cdr` | `(cdr (cons 1 2))` | `2` (`(cdr nil)` is `nil`) |
| `caar`..`cddddr` | `(cadr '(1 2 3))` | `2` (compositions of `car`/`cdr`, 2-4 levels) |
| `first` | `(first '(1 2 3))` | `1` (same as `car`) |
| `rest` | `(rest '(1 2 3))` | `(2 3)` (same as `cdr`) |
| `nth` | `(nth 1 '(1 2 3))` | `2` (0-based indexing) |
| `second` `third` `fourth` | `(second '(1 2 3))` | `2` |
| `list` | `(list 1 2 3)` | `(1 2 3)` |
| `nthcdr` | `(nthcdr 2 '(1 2 3))` | `(3)` (skip first n elements) |
| `length` | `(length '(1 2 3))`, `(length "abc")`, `(length #(1 2 3))` | `3`, `3`, `3` (lists, strings and vectors; `0` for nil) |
| `reverse` | `(reverse '(1 2 3))` | `(3 2 1)` |
| `member` | `(member 2 '(1 2 3))` | `(2 3)` (tail whose car is `eql` to the item, or nil; optional `:test`/`:key` keywords, e.g. `(member '(a d) '((a b) (a d)) :test 'equal)` -> `((a d))`) |
| `find` | `(find 2 '(1 2 3))` | `2` (first element `eql` to the item, or nil; optional `:test`/`:key` keywords) |
| `find-if` | `(find-if #'evenp '(1 3 6 7))` | `6` (first element satisfying the predicate, or nil) |
| `find-if-not` | `(find-if-not #'evenp '(2 4 5 6))` | `5` (first element failing the predicate, or nil) |
| `member-if` | `(member-if #'oddp '(2 4 5 6))` | `(5 6)` (tail starting at the first element satisfying the predicate, or nil) |
| `position` | `(position 3 '(1 2 3))` | `2` (0-based index of the first element `eql` to the item, or nil; optional `:test`/`:key` keywords) |
| `position-if` | `(position-if #'evenp '(1 3 6 7))` | `2` (0-based index of the first element satisfying the predicate, or nil) |
| `count` | `(count 2 '(1 2 3 2 2))` | `3` (number of elements `eql` to the item; optional `:test`/`:key` keywords) |
| `count-if` | `(count-if #'evenp '(1 2 3 4))` | `2` (number of elements satisfying the predicate) |
| `assoc` | `(assoc 'b '((a . 1) (b . 2)))` | `(b . 2)` (first pair whose car matches the key, or nil; `eql` compare by default, optional `:test`/`:key` keywords, e.g. `(assoc "b" '(("a" . 1) ("b" . 2)) :test #'equal)`) |
| `assoc-if` | `(assoc-if #'oddp '((2 a) (3 b)))` | `(3 b)` (first pair whose car satisfies the predicate, or nil) |
| `getf` | `(getf '(:a 1 :b 2) :b)` | `2` (value following the indicator in a property list, or nil; the partner of `remf`. Two arguments only: no `&optional default`) |
| `last` | `(last '(1 2 3))` | `(3)` (last cons cell, nil for an empty list) |
| `butlast` | `(butlast '(1 2 3))` | `(1 2)` (copy without the last element; nil for an empty or single-element list) |
| `remove` | `(remove 2 '(1 2 3 2))` | `(1 3)` (new list without items `eql` to the given one; optional `:test`/`:key` keywords) |
| `remove-if` | `(remove-if #'evenp '(1 2 3 4))` | `(1 3)` (new list without items satisfying the predicate) |
| `remove-if-not` | `(remove-if-not #'evenp '(1 2 3 4))` | `(2 4)` (new list keeping only items satisfying the predicate) |
| `remove-duplicates` | `(remove-duplicates '(1 2 1 3))` | `(2 1 3)` (copy with duplicate elements removed, keeping the last occurrence; `eql` compare by default, optional `:test`/`:key` keywords) |
| `delete` | `(delete 2 '(1 2 3 2))` | `(1 3)` (destructive `remove`: splices out matching cells in place; optional `:test`/`:key` keywords; use the return value since the head may change) |
| `delete-if` | `(delete-if #'evenp '(1 2 3 4))` | `(1 3)` (destructive `remove-if`) |
| `delete-if-not` | `(delete-if-not #'evenp '(1 2 3 4))` | `(2 4)` (destructive `remove-if-not`) |
| `substitute` | `(substitute 0 2 '(1 2 3 2))` | `(1 0 3 0)` (copy with every element `eql` to the old item replaced by the new one; optional `:test`/`:key` keywords) |
| `nsubstitute` | `(nsubstitute 0 2 '(1 2 3 2))` | `(1 0 3 0)` (destructive `substitute`: rewrites matching cars in place; optional `:test`/`:key` keywords) |
| `nconc` | `(nconc (list 1 2) (list 3 4))` | `(1 2 3 4)` (destructively concatenate two lists; two arguments only) |
| `copy-list` | `(copy-list '(1 2 3))` | `(1 2 3)` (shallow copy of a list) |
| `nreverse` | `(nreverse '(1 2 3))` | `(3 2 1)` (destructively reverse a list by rewiring each `cdr`; use the return value) |
| `make-list` | `(make-list 3)` | `(nil nil nil)` (list of n nil elements; no `:initial-element`) |
| `union` | `(union '(1 2 3) '(2 3 4))` | `(4 1 2 3)` (set union, `eql` compare by default, optional `:test`/`:key` keywords; result order unspecified) |
| `intersection` | `(intersection '(1 2 3) '(2 3 4))` | `(3 2)` (set intersection, `eql` compare by default, optional `:test`/`:key` keywords; result order unspecified) |
| `set-difference` | `(set-difference '(1 2 3) '(2))` | `(3 1)` (elements of the first list not in the second, `eql` compare by default, optional `:test`/`:key` keywords; result order unspecified) |
| `adjoin` | `(adjoin 1 '(2 3))` | `(1 2 3)` (prepend the item unless already a member; `eql` compare by default, optional `:test`/`:key` keywords) |
| `list*` | `(list* 1 2 '(3 4))`, `(list* 1 2 3)` | `(1 2 3 4)`, `(1 2 . 3)` (cons the leading arguments onto the last one as the tail) |
| `acons` | `(acons 'a 1 nil)` | `((a . 1))` (prepend a `(key . value)` pair to an alist) |
| `endp` | `(endp nil)`, `(endp '(1))` | `t`, `nil` (end-of-list test; a synonym for `null`, the improper-list error is relaxed) |
| `elt` | `(elt '(a b c) 1)` | `b` (0-based element access; lists only, no string indexing) |
| `rassoc` | `(rassoc 2 '((a . 1) (b . 2)))` | `(b . 2)` (first pair whose cdr matches the value, or nil; `eql` compare by default, optional `:test`/`:key` keywords) |
| `pairlis` | `(pairlis '(a b) '(1 2))` | `((a . 1) (b . 2))` (pair up a list of keys and a list of values into an alist; an optional third argument is appended as the tail) |
| `copy-alist` | `(copy-alist '((a . 1)))` | `((a . 1))` (copy an alist's spine and its pair cells; the keys and values themselves are shared) |
| `revappend` | `(revappend '(1 2 3) '(4 5))` | `(3 2 1 4 5)` (reverse the first list and append the second) |
| `nreconc` | `(nreconc '(1 2 3) '(4 5))` | `(3 2 1 4 5)` (destructive `revappend`: expands to `(nconc (nreverse x) y)`, reusing the cons cells of the first list) |
| `maplist` | `(maplist #'identity '(1 2 3))` | `((1 2 3) (2 3) (3))` (apply to successive tails, collect results; single-list form) |
| `mapcon` | `(mapcon (lambda (x) (list (car x))) '(1 2 3))` | `(1 2 3)` (apply to successive tails, concatenate the result lists; single-list form) |
| `sort` | `(sort '(3 1 2) #'<)` | `(1 2 3)` (destructively sort a list with a comparison predicate; not stable) |
| `rplaca` | `(rplaca x val)` | Destructively replace car of cons cell, return the cell |
| `rplacd` | `(rplacd x val)` | Destructively replace cdr of cons cell, return the cell |
| `1+` | `(1+ 41)` | `42` (same as `(+ x 1)`) |
| `1-` | `(1- 43)` | `42` (same as `(- x 1)`) |
| `zerop` | `(zerop 0)` | `t` |
| `plusp` | `(plusp 3)` | `t` |
| `minusp` | `(minusp -3)` | `t` |
| `evenp` | `(evenp 4)` | `t` |
| `oddp` | `(oddp 3)` | `t` |
| `abs` | `(abs -5)`, `(abs -3.14)` | `5`, `3.14` |
| `min` | `(min 3 5)`, `(min 5 2 8 1)` | `3`, `1` (variadic) |
| `max` | `(max 3 5)`, `(max 5 2 8 1)` | `5`, `8` (variadic) |
| `float` | `(float 42)` | `42.0` (convert to double) |
| `truncate` | `(truncate 3.7)`, `(truncate -7 2)` | `3`, `-3` (toward zero; with a divisor, the quotient of the division -- the remainder is observable through `multiple-value-bind`) |
| `floor` | `(floor 3.7)`, `(floor 7 2)` | `3`, `3` (toward negative infinity; with a divisor, the quotient of the division -- the remainder is observable through `multiple-value-bind`) |
| `ceiling` | `(ceiling 3.2)`, `(ceiling 7 2)` | `4`, `4` (toward positive infinity; with a divisor, the quotient of the division) |
| `round` | `(round 3.5)`, `(round 2.5)` | `4`, `2` (banker's rounding; an optional divisor rounds the quotient of the division) |
| `sqrt` | `(sqrt 16)`, `(sqrt 2)` | `4.0`, `1.4142135623730951` (always a float) |
| `isqrt` | `(isqrt 17)` | `4` (integer square root, floor of the real root) |
| `expt` | `(expt 2 10)`, `(expt 2.0 3)` | `1024`, `8.0` |
| `random` | `(random 100)`, `(random 1.0)` | a value in `[0, 100)` / `[0.0, 1.0)` (the result type follows the limit; `(random 1)` is always `0`). The interpreter and JVM draw from `Math.random`; WASM draws real entropy from the WASI `random_get` host function in Preview 1 mode and `wasi:random@0.3.0` in `--component` mode, so the sequence differs each run |
| `get-universal-time` | `(get-universal-time)` | seconds since 1900-01-01 GMT. The interpreter and JVM return an integer; WASM reads the clock (real host clock in Preview 1, `wasi:clocks@0.3.0` in `--component` mode) and returns a **float**, because its 31-bit integers cannot hold the value (so use it in comparisons/differences rather than printing the raw value) |
| `get-internal-real-time` | `(get-internal-real-time)` | elapsed real time in milliseconds (integer on the interpreter/JVM, float on WASM) |
| `get-internal-run-time` | `(get-internal-run-time)` | consumed run time in milliseconds (integer on the interpreter/JVM, float on WASM) |
| `getenv` | `(getenv "PATH")` | the value of an environment variable as a string, or `nil` if unset. All three backends; WASM reads the real host environment in Preview 1 and `wasi:cli/environment@0.3.0` in `--component` mode (pass `--env`/`-S inherit-env` to wasmtime) |
| `exp` | `(exp 0)` | `1.0` (interpreter/JVM use `Math.exp`; WASM uses a software approximation) |
| `log` | `(log 1)` | `0.0` (natural log; interpreter/JVM only) |
| `sin` `cos` `tan` | `(sin 0)`, `(cos 0)` | `0.0`, `1.0` (interpreter/JVM only) |
| `asin` `acos` `atan` | `(atan 0)` | `0.0` (interpreter/JVM only) |
| `sinh` `cosh` `tanh` | `(tanh 0)` | `0.0` (interpreter/JVM only) |
| `gcd` | `(gcd 12 18)`, `(gcd 24 36 60)` | `6`, `12` (variadic; greatest common divisor, `(gcd)` is `0`) |
| `lcm` | `(lcm 4 6)`, `(lcm 2 3 4)` | `12`, `12` (variadic; least common multiple; `0` if any argument is `0`, `(lcm)` is `1`) |
| `signum` | `(signum -5)`, `(signum 3.5)` | `-1`, `1.0` (sign, preserving integer/float type) |
| `logand` | `(logand 12 10)`, `(logand 12 10 6)` | `8`, `0` (variadic bitwise AND; `(logand)` is `-1`) |
| `logior` | `(logior 12 10)`, `(logior 1 2 4 8)` | `14`, `15` (variadic bitwise inclusive OR; `(logior)` is `0`) |
| `logxor` | `(logxor 12 10)` | `6` (variadic bitwise exclusive OR; `(logxor)` is `0`) |
| `lognot` | `(lognot 5)` | `-6` (bitwise NOT, i.e. ones' complement) |
| `ash` | `(ash 1 4)`, `(ash 255 -4)` | `16`, `15` (arithmetic shift: left for a non-negative count, right otherwise) |
| `funcall` | `(funcall #'+ 3 4)` | Apply a function to args. Accepts a function value (`#'f`, a lambda) or a symbol naming a function (`(funcall 'car ...)`) |
| `mapcar` | `(mapcar #'car '((1 2) (3 4)))` | Apply a function to each element, return new list |
| `map` | `(map 'list #'+ '(1 2 3) '(10 20 30))` | `(11 22 33)` (map over sequences -- list/string -- up to the shortest, building a `'list`/`'string` result, or nil for effect) |
| `mapc` | `(mapc #'print '(1 2 3))` | Apply a function to each element for effect, return the original list |
| `mapcan` | `(mapcan (lambda (x) (list x x)) '(1 2))` | `(1 1 2 2)` (apply a function and concatenate the result lists; uses non-destructive `append`) |
| `apply` | `(apply #'+ 1 2 '(3 4))` | `10` (apply a function to the leading args plus the spread final list) |
| `values` | `(values 1 2 3)`, `(multiple-value-list (values 1 2 3))` | `1`, `(1 2 3)` -- multiple values exist only syntactically: `multiple-value-bind`/`-list`/`-call`/`nth-value` receive all values of a literal `(values ...)` call or a two-value built-in (`floor` family, `gethash`); any other context keeps the primary value only |
| `reduce` | `(reduce #'+ '(1 2 3) :initial-value 0)` | Left fold: `(f (f (f init a) b) c)`. Plain form `(reduce f list)` uses the first element as init; the `:initial-value` keyword (literal) supplies an explicit seed |
| `every` | `(every #'evenp '(2 4 6))` | `t` if the predicate is non-nil for every element, else `nil` (single-list form) |
| `some` | `(some #'oddp '(2 4 5))` | The first non-nil predicate result, or `nil` if every element fails (single-list form) |
| `notany` | `(notany #'evenp '(1 3 5))` | `t` if the predicate is nil for every element, else `nil` (the complement of `some`) |
| `notevery` | `(notevery #'evenp '(2 4 5))` | `t` if the predicate is nil for some element, else `nil` (the complement of `every`) |
| `symbol-function` | `(symbol-function 'car)` | Return the function named by a symbol (compilers: the argument must be a quoted symbol literal) |
| `identity` | `(identity 42)` | `42` (return the argument unchanged) |
| `make-hash-table` | `(make-hash-table)`, `(make-hash-table :test 'equal)` | Create an empty hash table. `:test` is accepted but informational (see the note below); other keywords such as `:size` are ignored |
| `gethash` | `(gethash key table)`, `(gethash key table default)` | Return the value stored under `key`, or `default` (nil if omitted) when absent |
| `(setf (gethash key table) v)` | `(setf (gethash "a" h) 1)` | Store `v` under `key`; works with `incf`/`decf`/`push` on the place |
| `remhash` | `(remhash key table)` | Remove the entry for `key`; returns `t` if one was removed, else `nil` |
| `clrhash` | `(clrhash table)` | Remove all entries; returns the table |
| `hash-table-count` | `(hash-table-count table)` | The number of entries |
| `hash-table-p` | `(hash-table-p x)` | `t` if `x` is a hash table, else `nil` |
| `maphash` | `(maphash (lambda (k v) ...) table)` | Call the function on each key/value pair for effect; returns nil |
| `make-array` | `(make-array 5 :initial-element 0)`, `(make-array (list 2 3))` | Create an array of any rank; `:initial-element` sets every cell (nil if omitted) |
| `aref` | `(aref a i)`, `(aref a i j)` | Return the element at the given subscripts |
| `(setf (aref a i j) v)` | `(setf (aref a 0 0) 1)` | Store `v` at the subscripts; works with `incf`/`decf`/`push` on the place |
| `vector` | `(vector 1 2 3)` | `#(1 2 3)` (a fresh rank-1 array of the arguments) |
| `svref` | `(svref (vector 10 20 30) 1)` | `20` (vector element access; also a `setf` place) |
| `array-dimensions` | `(array-dimensions (make-array (list 2 3)))` | `(2 3)` (the dimension sizes as a list) |
| `array-dimension` | `(array-dimension (make-array (list 2 3)) 1)` | `3` (the size of one axis, 0-based) |
| `array-rank` | `(array-rank (vector 1 2))` | `1` (`2` for a rank-2 array, and so on) |
| `array-total-size` | `(array-total-size (make-array (list 2 3)))` | `6` (the total element count) |
| `row-major-aref` | `(row-major-aref (make-array (list 2 3)) 4)` | The element at a flat row-major index, independent of rank; also a `setf` place |
| `array-row-major-index` | `(array-row-major-index (make-array (list 2 3)) 1 1)` | `4` (the flat row-major index of the subscripts) |
| `coerce` | `(coerce '(1 2 3) 'vector)`, `(coerce "ab" 'list)` | `#(1 2 3)`, `(#\a #\b)` (only the literal `'list`/`'vector`/`'string` result types) |

## rontolisp Package Functions

The `rontolisp` package provides implementation-specific functions that are
**not part of Common Lisp**. Reference them with the `rontolisp:` qualifier (or
unqualified after `(in-package rontolisp)`); see [Packages](packages.md) for the
package system. Each name below links to its own page.

| Function | Example | Result |
|----------|---------|--------|
| `rontolisp:version` | `(rontolisp:version)` | a property list of build info (`:version`, `:build-timestamp`, `:git-commit`, `:git-branch`) |
| `rontolisp:list-functions` | `(rontolisp:list-functions :cl)` | the function symbols of a package, sorted (defaults to `:cl`) |
| `rontolisp:list-macros` | `(rontolisp:list-macros)` | the macro symbols of a package, sorted |
| `rontolisp:list-special-forms` | `(rontolisp:list-special-forms)` | the special-form symbols of a package, sorted |
| `rontolisp:fetch` | `(rontolisp:fetch "http://example.com/")` | start an HTTP request asynchronously; returns a promise |
| `rontolisp:await` | `(rontolisp:await p)` | resolve a promise (blocking); a non-promise passes through unchanged |
| `rontolisp:then` | `(rontolisp:then p (lambda (r) (getf r :status)))` | derive a new promise that applies a callback to the settled value |
| `rontolisp:promisep` | `(rontolisp:promisep p)` | `t` if the value is a promise |
| `rontolisp:http-handler` | `(rontolisp:http-handler 'handle 8080)` | serve HTTP requests with a handler function (a blocking server; a `wasi:http` component under `--component`) |
| `rontolisp:json-parse` | `(rontolisp:json-parse "{\"n\": 1}")` | parse a JSON string: objects become keyword plists, or hash tables with `:hash-table` |
| `rontolisp:json-stringify` | `(rontolisp:json-stringify (list :n 1))` | serialize a value (plists and hash tables become objects) to a JSON string |
| `rontolisp:tcp-connect` | `(rontolisp:tcp-connect "127.0.0.1" 7777)` | open a blocking TCP connection; returns a bidirectional stream handle |
| `rontolisp:tcp-listen` | `(rontolisp:tcp-listen 7777)`, `(rontolisp:tcp-listen 0 "127.0.0.1")` | bind a listening TCP socket and return a listener handle; port `0` picks a free ephemeral port |
| `rontolisp:tcp-accept` | `(rontolisp:tcp-accept listener)` | wait for a client connection (blocking); returns a bidirectional stream handle |
| `rontolisp:tcp-local-port` | `(rontolisp:tcp-local-port listener)` | the local port a listener or socket is actually bound to |
| `rontolisp:tls-connect` | `(rontolisp:tls-connect "example.com" 443)` | open an encrypted (TLS) client connection; returns the same kind of stream handle as `tcp-connect` |
| `rontolisp:tls-listen` | `(rontolisp:tls-listen "server.p12" "changeit" 8443)` | bind an encrypted listening socket from a PKCS12 keystore; accept with `tcp-accept` |
| `rontolisp:tls-listen-pem` | `(rontolisp:tls-listen-pem "cert.pem" "key.pem" 8443)` | bind an encrypted listening socket from PEM certificate/key files |
| `rontolisp:wasm-export` | `(rontolisp:wasm-export 'fact :params '(:int) :returns :int)` | mark a `defun` as host-callable when compiling to a WASM core module |
| `rontolisp:wasm-import` | `(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)` | declare a host function callable from Lisp when compiling to a WASM core module |

The introspection functions (`list-functions` / `list-macros` /
`list-special-forms`) are described in detail under
[Package introspection](packages.md#package-introspection). `rontolisp:fetch`
starts an outgoing HTTP request and returns a promise, resolved with the
generic promise operations `rontolisp:await` / `rontolisp:then` /
`rontolisp:promisep`; see the
[HTTP Requests guide](../guides/http-fetch.md) for a worked overview, and the
[fetch](functions/rontolisp-fetch.md),
[await](functions/rontolisp-await.md), [then](functions/rontolisp-then.md) and
[promisep](functions/rontolisp-promisep.md) reference pages for options, the
result plist, backend support, and limitations. `rontolisp:http-handler` is
the incoming counterpart of `fetch` -- it serves HTTP requests with a handler
function over the same request/response property lists; see the
[Serving HTTP guide](../guides/http-handler.md) for a worked example on every
backend, and the [http-handler](functions/rontolisp-http-handler.md) reference
page for backend support and limitations. `rontolisp:json-parse` and
`rontolisp:json-stringify` convert between JSON documents and Lisp values
(JavaScript `JSON.parse`/`JSON.stringify` style) -- for example to parse a
fetch response body; see the
[json-parse](functions/rontolisp-json-parse.md) and
[json-stringify](functions/rontolisp-json-stringify.md) reference pages for
the value mapping and limitations. The tcp functions
(`rontolisp:tcp-connect` / `tcp-listen` / `tcp-accept` / `tcp-local-port`)
open plain TCP sockets whose handles work with the standard stream functions
(`read-line` / `write-line` / `read-byte` / `write-byte` / `close`); see the
[TCP Sockets guide](../guides/tcp-sockets.md) for a worked echo server, and
the [tcp-connect](functions/rontolisp-tcp-connect.md),
[tcp-listen](functions/rontolisp-tcp-listen.md),
[tcp-accept](functions/rontolisp-tcp-accept.md) and
[tcp-local-port](functions/rontolisp-tcp-local-port.md) reference pages for
backend support and limitations. The TLS variants (`rontolisp:tls-connect` /
`tls-listen` / `tls-listen-pem`) wrap the same stream handles in TLS; see the
[tls-connect](functions/rontolisp-tls-connect.md),
[tls-listen](functions/rontolisp-tls-listen.md) and
[tls-listen-pem](functions/rontolisp-tls-listen-pem.md) reference pages.
`rontolisp:wasm-export` and
`rontolisp:wasm-import` are compile-time directives for the WASM backend; see
their [wasm-export](functions/rontolisp-wasm-export.md) and
[wasm-import](functions/rontolisp-wasm-import.md) reference pages and the
[Compiling to WebAssembly](../compiling/wasm.md) guide.

## linalg Package Functions

The `linalg` package provides numpy-style vector and matrix operations over
the built-in arrays (the elementwise operations and reductions work for any
rank). It is **not part of Common Lisp**;
reference its functions with the `linalg:` qualifier (the package does not use
`cl`, so most programs stay in `cl-user` and call the qualified names). The
package is implemented once in Lisp source and behaves identically on every
backend, and its arithmetic is exact for integer and rational inputs -- `det`,
`inv` and `solve` of an integer matrix return exact ratios. Each name below
links to its own page; the [Vectors & Matrices
guide](../guides/linear-algebra.md) gives an overview and worked examples.

| Function | Example | Result |
|----------|---------|--------|
| `linalg:zeros` | `(linalg:zeros 3)`, `(linalg:zeros '(2 2))` | `#(0 0 0)`, `#2A((0 0) (0 0))` (shape: integer or `(rows cols)` list) |
| `linalg:ones` | `(linalg:ones '(2 2))` | `#2A((1 1) (1 1))` |
| `linalg:full` | `(linalg:full '(2 2) 7)` | `#2A((7 7) (7 7))` |
| `linalg:eye` | `(linalg:eye 2)` | `#2A((1 0) (0 1))` (the identity matrix) |
| `linalg:arange` | `(linalg:arange 5)`, `(linalg:arange 2 10 2)` | `#(0 1 2 3 4)`, `#(2 4 6 8)` (stop exclusive; step may be negative) |
| `linalg:linspace` | `(linalg:linspace 0 1 5)` | `#(0 1/4 1/2 3/4 1)` (n evenly spaced values, inclusive) |
| `linalg:from-list` | `(linalg:from-list '((1 2) (3 4)))` | `#2A((1 2) (3 4))` (a flat list gives a vector) |
| `linalg:to-list` | `(linalg:to-list (linalg:eye 2))` | `((1 0) (0 1))` |
| `linalg:shape` | `(linalg:shape #2A((1 2 3) (4 5 6)))` | `(2 3)` |
| `linalg:size` | `(linalg:size (linalg:eye 3))` | `9` (the total element count) |
| `linalg:reshape` | `(linalg:reshape (linalg:arange 6) '(2 3))` | `#2A((0 1 2) (3 4 5))` (row-major; the sizes must match) |
| `linalg:flatten` | `(linalg:flatten (linalg:eye 2))` | `#(1 0 0 1)` |
| `linalg:transpose` | `(linalg:transpose #2A((1 2 3) (4 5 6)))` | `#2A((1 4) (2 5) (3 6))` (a vector is returned unchanged) |
| `linalg:add` | `(linalg:add #(1 2 3) 10)` | `#(11 12 13)` (elementwise; a scalar operand broadcasts) |
| `linalg:sub` | `(linalg:sub #(5 5) 1)` | `#(4 4)` |
| `linalg:mul` | `(linalg:mul m1 m2)` | The Hadamard (elementwise) product -- not the matrix product |
| `linalg:div` | `(linalg:div #(1 2 3) 2)` | `#(1/2 1 3/2)` (integer division gives exact ratios) |
| `linalg:emap` | `(linalg:emap (lambda (x) (* x x)) (linalg:arange 4))` | `#(0 1 4 9)` (apply a function to every element) |
| `linalg:dot` | `(linalg:dot v1 v2)` | numpy-style dispatch: vec.vec scalar, mat.vec / vec.mat vector, mat.mat matrix product |
| `linalg:matmul` | `(linalg:matmul #2A((1 2) (3 4)) #2A((5 6) (7 8)))` | `#2A((19 22) (43 50))` (the matrix product) |
| `linalg:outer` | `(linalg:outer #(1 2) #(3 4 5))` | `#2A((3 4 5) (6 8 10))` (the outer product) |
| `linalg:sum` | `(linalg:sum #2A((1 2) (3 4)))` | `10` |
| `linalg:mean` | `(linalg:mean #(1 2 3 4))` | `5/2` (exact for integer inputs) |
| `linalg:amax` | `(linalg:amax #2A((1 9) (3 4)))` | `9` (the largest element) |
| `linalg:amin` | `(linalg:amin #(5 2 8))` | `2` (the smallest element) |
| `linalg:argmax` | `(linalg:argmax #(1 9 3))` | `1` (vectors only; first index on ties) |
| `linalg:argmin` | `(linalg:argmin #(5 2 8))` | `1` |
| `linalg:norm` | `(linalg:norm #(3 4))` | `5.0` (the Euclidean / Frobenius norm) |
| `linalg:trace` | `(linalg:trace #2A((1 2) (3 4)))` | `5` (square matrices only) |
| `linalg:det` | `(linalg:det #2A((1 2) (3 4)))` | `-2` (exact for integer/rational inputs) |
| `linalg:inv` | `(linalg:inv #2A((1 2) (3 4)))` | `#2A((-2 1) (3/2 -1/2))` (signals an error for a singular matrix) |
| `linalg:solve` | `(linalg:solve a b)` | The solution of `a . x = b` (`b` a vector or matrix) |
| `linalg:array-equal` | `(linalg:array-equal (linalg:eye 2) #2A((1 0) (0 1)))` | `t` (same shape and numerically equal elements; arrays themselves are only `eq`-comparable) |

## java Package Functions

The `java` package drives arbitrary Java APIs by reflection. It is
**JVM-only** — it works on the interpreter (`java -jar rontolisp.jar`) and in
JVM-compiled classes (the compiler embeds a reflection bridge into the
generated `.class`), but not on the WASM backend, and the GraalVM native binary
carries no reflection metadata to interpret it — and **not part of Common
Lisp**; reference its functions with the `java:`
qualifier. Each name below links to its own page; the [Java interop
guide](../guides/java-interop.md) covers marshalling, overload resolution and
limitations.

| Function | Example | Result |
|----------|---------|--------|
| `java:new` | `(java:new "java.lang.StringBuilder" "ab")` | a host object (`#<java ...>`) |
| `java:call` | `(java:call obj "size")` | the marshalled instance-method result |
| `java:static` | `(java:static "java.lang.Math" "max" 3 7)` | the marshalled static-method result |
| `java:field` | `(java:field "java.lang.Integer" "MAX_VALUE")` | the marshalled field value |
| `java:proxy` | `(java:proxy "java.lang.Runnable" (lambda (m) ...))` | an interface instance backed by the callable |
