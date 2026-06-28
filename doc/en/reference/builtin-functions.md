# Built-in Functions

This page is the quick-reference table plus the conceptual notes (deviations from
Common Lisp, hash tables, arrays, `read`/`load`/streams). **Each function name in
the table links to its own page**, which has a fuller description and a runnable
example you can evaluate in your browser.

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
| `open` | `(open "f.txt")`, `(open "f.txt" :output)` | Open a file and return a stream. The direction must be the literal `:input` (default, read) or `:output` (create/truncate, write) |
| `close` | `(close stream)` | Close a stream opened by `open`. Returns `t` |
| `write-line` | `(write-line "hi" stream)`, `(write-line "hi")` | Write the string plus a newline to an output stream (or to standard output). Returns the string |
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
| `member` | `(member 2 '(1 2 3))` | `(2 3)` (tail whose car is `eql` to the item, or nil; an optional `:test` keyword takes a function designator, e.g. `(member '(a d) '((a b) (a d)) :test 'equal)` -> `((a d))`) |
| `find` | `(find 2 '(1 2 3))` | `2` (first element `eql` to the item, or nil) |
| `find-if` | `(find-if #'evenp '(1 3 6 7))` | `6` (first element satisfying the predicate, or nil) |
| `find-if-not` | `(find-if-not #'evenp '(2 4 5 6))` | `5` (first element failing the predicate, or nil) |
| `member-if` | `(member-if #'oddp '(2 4 5 6))` | `(5 6)` (tail starting at the first element satisfying the predicate, or nil) |
| `position` | `(position 3 '(1 2 3))` | `2` (0-based index of the first element `eql` to the item, or nil) |
| `position-if` | `(position-if #'evenp '(1 3 6 7))` | `2` (0-based index of the first element satisfying the predicate, or nil) |
| `count` | `(count 2 '(1 2 3 2 2))` | `3` (number of elements `eql` to the item) |
| `count-if` | `(count-if #'evenp '(1 2 3 4))` | `2` (number of elements satisfying the predicate) |
| `assoc` | `(assoc 'b '((a 1) (b 2)))` | `(b 2)` (first pair whose car is `eql` to the key, or nil) |
| `assoc-if` | `(assoc-if #'oddp '((2 a) (3 b)))` | `(3 b)` (first pair whose car satisfies the predicate, or nil) |
| `getf` | `(getf '(:a 1 :b 2) :b)` | `2` (value following the indicator in a property list, or nil; the partner of `remf`. Two arguments only: no `&optional default`) |
| `last` | `(last '(1 2 3))` | `(3)` (last cons cell, nil for an empty list) |
| `butlast` | `(butlast '(1 2 3))` | `(1 2)` (copy without the last element; nil for an empty or single-element list) |
| `remove` | `(remove 2 '(1 2 3 2))` | `(1 3)` (new list without items `eql` to the given one) |
| `remove-if` | `(remove-if #'evenp '(1 2 3 4))` | `(1 3)` (new list without items satisfying the predicate) |
| `remove-if-not` | `(remove-if-not #'evenp '(1 2 3 4))` | `(2 4)` (new list keeping only items satisfying the predicate) |
| `remove-duplicates` | `(remove-duplicates '(1 2 1 3))` | `(2 1 3)` (copy with duplicate elements removed, keeping the last occurrence; `eql` compare, no `:test`/`:key`) |
| `delete` | `(delete 2 '(1 2 3 2))` | `(1 3)` (destructive `remove`: splices out matching cells in place; use the return value since the head may change) |
| `delete-if` | `(delete-if #'evenp '(1 2 3 4))` | `(1 3)` (destructive `remove-if`) |
| `delete-if-not` | `(delete-if-not #'evenp '(1 2 3 4))` | `(2 4)` (destructive `remove-if-not`) |
| `substitute` | `(substitute 0 2 '(1 2 3 2))` | `(1 0 3 0)` (copy with every element `eql` to the old item replaced by the new one; positional args only, no `:test`/`:key`) |
| `nsubstitute` | `(nsubstitute 0 2 '(1 2 3 2))` | `(1 0 3 0)` (destructive `substitute`: rewrites matching cars in place) |
| `nconc` | `(nconc (list 1 2) (list 3 4))` | `(1 2 3 4)` (destructively concatenate two lists; two arguments only) |
| `copy-list` | `(copy-list '(1 2 3))` | `(1 2 3)` (shallow copy of a list) |
| `nreverse` | `(nreverse '(1 2 3))` | `(3 2 1)` (destructively reverse a list by rewiring each `cdr`; use the return value) |
| `make-list` | `(make-list 3)` | `(nil nil nil)` (list of n nil elements; no `:initial-element`) |
| `union` | `(union '(1 2 3) '(2 3 4))` | `(4 1 2 3)` (set union, `eql` compare, no `:test`/`:key`; result order unspecified) |
| `intersection` | `(intersection '(1 2 3) '(2 3 4))` | `(3 2)` (set intersection, `eql` compare; result order unspecified) |
| `set-difference` | `(set-difference '(1 2 3) '(2))` | `(3 1)` (elements of the first list not in the second, `eql` compare; result order unspecified) |
| `adjoin` | `(adjoin 1 '(2 3))` | `(1 2 3)` (prepend the item unless already a member; `eql` compare) |
| `list*` | `(list* 1 2 '(3 4))`, `(list* 1 2 3)` | `(1 2 3 4)`, `(1 2 . 3)` (cons the leading arguments onto the last one as the tail) |
| `acons` | `(acons 'a 1 nil)` | `((a . 1))` (prepend a `(key . value)` pair to an alist) |
| `endp` | `(endp nil)`, `(endp '(1))` | `t`, `nil` (end-of-list test; a synonym for `null`, the improper-list error is relaxed) |
| `elt` | `(elt '(a b c) 1)` | `b` (0-based element access; lists only, no string indexing) |
| `rassoc` | `(rassoc 2 (list (cons 'a 1) (cons 'b 2)))` | `(b . 2)` (first pair whose cdr is `eql` to the value, or nil) |
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
| `truncate` | `(truncate 3.7)`, `(truncate -3.7)` | `3`, `-3` (toward zero) |
| `floor` | `(floor 3.7)`, `(floor -3.7)` | `3`, `-4` (toward negative infinity) |
| `ceiling` | `(ceiling 3.2)`, `(ceiling -3.2)` | `4`, `-3` (toward positive infinity) |
| `round` | `(round 3.5)`, `(round 2.5)` | `4`, `2` (banker's rounding) |
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
| `mapc` | `(mapc #'print '(1 2 3))` | Apply a function to each element for effect, return the original list |
| `mapcan` | `(mapcan (lambda (x) (list x x)) '(1 2))` | `(1 1 2 2)` (apply a function and concatenate the result lists; uses non-destructive `append`) |
| `apply` | `(apply #'+ 1 2 '(3 4))` | `10` (apply a function to the leading args plus the spread final list) |
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
| `make-array` | `(make-array 5 :initial-element 0)`, `(make-array (list 2 3))` | Create an array of rank 1 or 2; `:initial-element` sets every cell (nil if omitted) |
| `aref` | `(aref a i)`, `(aref a i j)` | Return the element at the given subscripts |
| `(setf (aref a i j) v)` | `(setf (aref a 0 0) 1)` | Store `v` at the subscripts; works with `incf`/`decf`/`push` on the place |

**Deviations from Common Lisp.** Some functions accept fewer arguments than the Common
Lisp standard: `log` takes only one argument (no base: `(log x base)` is unsupported),
`atan` takes only one argument (no two-argument `(atan y x)` form), and `last` takes only
a list (no optional count: `(last list n)` is unsupported). `butlast` likewise takes only
a list, `getf` takes no `&optional default`, `nconc` concatenates exactly two lists, and
`remove-duplicates` compares with `eql` only (no `:test`/`:key`); `(setf (getf ...) v)` is
unsupported (use `remf` to delete a property). The rounding functions
`truncate`/`floor`/`ceiling`/`round` accept a single argument and return one value (no
optional divisor and no second remainder value). These remain on the to-do list.

**Hash tables.** `make-hash-table`, `gethash`, `(setf (gethash ...))`, `remhash`,
`clrhash`, `hash-table-count`, `hash-table-p` and `maphash` work in all three backends.
Keys are compared structurally (as if by `equal`): a list key like `(list r c)` matches an
equal list, and numbers, symbols, characters and strings match by value. `:test` is
accepted for familiarity but does not change this — an `eql` table also matches
structurally-equal aggregate keys. Iteration order (`maphash`) is not guaranteed across
backends, so portable code should not depend on it. They are also usable as first-class
function values (`#'gethash`, `#'remhash`, `#'clrhash`, `#'hash-table-count`,
`#'hash-table-p`, `#'maphash`, and `#'make-hash-table` in its no-argument form) on all
three backends -- passed via fixed-arity wrappers, so `gethash`'s optional default and
`make-hash-table`'s keyword arguments are not available through the function value. A
typical use -- counting with `incf` on the place:

```lisp
(let ((counts (make-hash-table :test 'equal)))
  (dolist (w '("a" "b" "a"))
    (incf (gethash w counts 0)))
  (gethash "a" counts)) ; => 2
```

**Arrays.** `make-array`, `aref` and `(setf (aref ...))` work in all three backends.
Only arrays of **rank 1 (vectors) and rank 2** are supported; the dimensions argument is
an integer (rank 1) or a list of one or two integers, and `:initial-element` sets every
cell (defaulting to nil). Elements are stored row-major with O(1) access, and arrays are
compared by identity (`eq`), so two distinct arrays are never `equal`. `length` returns the
element count of a vector (rank-1 array); a rank-2 array is not a sequence, so `length`
signals an error on it. Unlike the hash-table
operators, the array operators are not exposed as first-class function values, so `#'aref`
and `#'make-array` are not available (call them directly). A 2-D array indexed in nested
loops:

```lisp
(let ((m (make-array (list 2 3) :initial-element 0)))
  (setf (aref m 1 2) 9)
  (incf (aref m 1 2))
  (aref m 1 2)) ; => 10
```

The `#(...)` reader syntax denotes a self-evaluating rank-1 vector literal whose elements
are read as data (not evaluated), e.g. `#(1 2 3)` or `#(a "b")`. Arrays print in the same
readable syntax across all backends: a rank-1 array as `#(...)` and a rank-2 array as
`#2A((row) ...)`, with `prin1` quoting string elements and `princ` not:

```lisp
(print #(1 2 3))                          ; #(1 2 3)
(princ #(a "b"))                          ; #(a b)
(make-array (list 2 2) :initial-element 0) ; #2A((0 0) (0 0))
```

`read` works in all three backends. It reads one line from stdin and parses one S-expression from it. The interpreter uses the full Lisp reader; the JVM and WASM compilers each emit a small reader/parser into their output (the JVM reuses the JDK at runtime, so it has full parity; the WASM reader is limited to the value kinds listed under [Compiled `read`/`load` limitations](../guides/read-load-limitations.md)). Use `read-line` to read raw strings instead.

`load` works in all three backends. It reads a file and evaluates every top-level form in the global environment, so `defun`/`setq` definitions in the loaded file remain available to subsequent code. In compiled output the loaded definitions live in the runtime `eval` interpreter's global environment, so they are used through `eval` (e.g. `(load "lib.lisp")` then `(eval '(square 5))`). The WASM `load` reads the file with WASI `path_open`, so the module must be run with a directory granted (e.g. `wasmtime run -W gc --dir . prog.wasm`).

`with-open-file`/`open`/`close`/`write-line` and the stream-taking `read-line` work in all three backends. A stream value is an opaque handle (interpreter/JVM: an index into a stream table; WASM: the WASI file descriptor), so streams can be stored in variables and passed to functions, but they are only valid within the producing run. `open` supports the `:input` and `:output` directions only ( `:output` creates or truncates), and the direction must be a literal keyword so the compilers can pick the file mode statically. Like `load`, the WASM `open` resolves paths relative to the first preopened directory, so run with `--dir`.

```console
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out)
  (write-line "world" out))

(with-open-file (in "greeting.txt")
  (print (read-line in)) ; => "hello"
  (print (read-line in)) ; => "world"
  (print (read-line in))) ; => nil (EOF)
```

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
| `rontolisp:fetch` | `(rontolisp:fetch "http://example.com/")` | perform an HTTP request; returns a `(:status :body :headers)` plist |

The introspection functions (`list-functions` / `list-macros` /
`list-special-forms`) are described in detail under
[Package introspection](packages.md#package-introspection).

### HTTP requests (`rontolisp:fetch`)

`rontolisp:fetch` performs an outgoing HTTP request, modeled on the JavaScript `fetch` API.

```console
;; GET, no options
(rontolisp:fetch "http://example.com/")

;; GET with request headers (an alist of (name . value) string pairs)
(rontolisp:fetch "http://example.com/api"
                 (list :headers (list (cons "Accept" "application/json"))))

;; POST with a request body
(rontolisp:fetch "http://example.com/api"
                 (list :method "POST"
                       :headers (list (cons "Content-Type" "application/json"))
                       :body "{\"name\":\"rontolisp\"}"))
```

The optional second argument is an options property list. Recognized keys:

- `:method` — the HTTP method as a string (default `"GET"`). Supported methods are `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS` and `PATCH`, matched case-insensitively; any other method is an error.
- `:headers` — request headers, an alist of `(name . value)` string pairs.
- `:body` — the request body as a string (omit for no body).

The result is a property list `(:status <integer> :body <string> :headers <alist>)`, where `:headers` is an alist of `(name . value)` response-header pairs:

```console
(let ((res (rontolisp:fetch "http://example.com/")))
  (print (getf res :status))    ; => 200
  (print (getf res :body))      ; => "<html>...</html>"
  (print (getf res :headers)))  ; => (("content-type" . "text/html") ...)
```

Backend support:

- **Interpreter** and **JVM**: use the JDK `java.net.http.HttpClient`.
- **WASM**: component-only, and a **hybrid** — the base I/O is WASI 0.3 but fetch imports `wasi:http@0.2` + `wasi:io@0.2` (async `wasi:http@0.3` does not exist upstream yet; see `.todo/02-upgrade-fetch-to-wasi-http-0.3.md`). Compile with `--component` and run with `-S http=y` plus the async flags. It remains a compile error in Preview 1 (core-module) mode, which has no host `wasi:http`.

Current limitations:

- The method must be one of `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS`, `PATCH`. An unsupported `:method` is an error: the interpreter and JVM reject it at runtime; the WASM backend resolves the method statically and rejects a statically-known unsupported `:method` at compile time (a method computed at runtime cannot be checked there and is treated as GET, while a runtime-computed `:body` is sent normally).
- A failed request (for example a refused connection) raises an error in the interpreter and JVM, and returns `nil` in WASM.
- In WASM, the response body is capped (about 576 KiB) and very large programs may exhaust the shared linear memory the response buffers reuse.
