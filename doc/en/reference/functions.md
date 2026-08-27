# Functions

This page is the quick-reference table. **Each function name in the table links
to its own page**, which has a fuller description and a runnable example you can
evaluate in your browser. Cross-cutting topics have their own homes: the
`make-array`/`aref` and hash-table operators are described under
[Arrays](data-types.md#arrays) and [Hash tables](data-types.md#hash-tables) on
the Data Types page, and each function's deviations from Common Lisp are noted on
its own page.

## cl Package Functions

The standard Common Lisp functions, in the `cl` package (used by `cl-user`, so
they are available unqualified in ordinary programs). Each name links to its own
page.

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
| `equalp` | `(equalp "ABC" "abc")` | `t` (like `equal` but strings/characters compare case-insensitively and numbers by value; arrays/hash-tables fall back to `eql`) |
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
| `write` | `(write "hi" :escape nil)` | Prints `hi`; each keyword binds the matching printer control variable around the one print |
| `pprint` `pprint-newline` `pprint-indent` `pprint-tab` | `(pprint-newline :mandatory s)` | A newline for `:mandatory` only -- no stream carries a column, so nothing wraps |
| `copy-pprint-dispatch` `set-pprint-dispatch` `pprint-dispatch` | `(pprint-dispatch 21 table)` | The pretty-print dispatch table (real entries + lookup; the ordinary printing operators do not consult it) |
| `concatenate` | `(concatenate 'string "foo" "bar")` | `"foobar"` (`'string` / `'list` / `'vector` result families; the compilers require a literal quoted designator) |
| `string-upcase` | `(string-upcase "abc")` | `"ABC"` (full-Unicode, and length-preserving: `char-upcase` per character) |
| `string-downcase` | `(string-downcase "ABC")` | `"abc"` |
| `string-capitalize` | `(string-capitalize "hello world")` | `"Hello World"` (first letter of each word) |
| `nstring-upcase` `nstring-downcase` `nstring-capitalize` | `(nstring-upcase (copy-seq "abc"))` | `"ABC"` — the destructive spellings: the fold is written back into the argument (a mutable character vector is written in place on every backend; an immutable string is rebuilt on the compile paths) |
| `subseq` | `(subseq "hello" 1 3)` | `"el"` (works on strings and lists, e.g. `(subseq '(1 2 3 4) 1 3)` => `(2 3)`; the `end` argument is optional) |
| `make-string` | `(make-string 3 :initial-element #\x)` | `"xxx"` -- a fresh string of `n` copies of `:initial-element` (default space); `:element-type` is accepted and ignored |
| `make-sequence` | `(make-sequence 'list 3)` | `(nil nil nil)` -- a sequence of the literal quoted result type (string types via `make-string`, `list` via `make-list`, vector types via `make-array`) |
| `replace` | `(replace (make-string 5 :initial-element #\a) "XY" :start1 1)` | `"aXYaa"` -- copy `sequence-2` into `sequence-1` (`:start1`/`:end1`/`:start2`/`:end2`); string-aware, mutates an allocated buffer in place |
| `fill` | `(fill (list 1 2 3) 7)` | `(7 7 7)` -- store one item into every element between `:start`/`:end`; destructive over a vector or list, string-aware like `replace` |
| `string=` | `(string= "abc" "abc")`, `(string= "together" "frog" :start1 1 :end1 3 :start2 2)` | `t` (case-sensitive string equality; `:start1`/`:end1`/`:start2`/`:end2` bound the compared substrings) |
| `string<` `string>` `string<=` `string>=` `string/=` | `(string< "abc" "abd")` | `2` -- case-sensitive lexicographic comparison: the mismatch index in `string1` (`end1` when equal), or nil. Same `:start1`/`:end1`/`:start2`/`:end2` keywords |
| `string-equal` | `(string-equal "ABC" "abc")` | `t` (case-insensitive, ASCII) |
| `string-lessp` `string-greaterp` `string-not-greaterp` `string-not-lessp` `string-not-equal` | `(string-not-greaterp "Abcde" "abcdE")` | `5` -- the case-insensitive counterparts of `string<` `string>` `string<=` `string>=` `string/=` |
| `string-trim` | `(string-trim " " "  hi  ")` | `"hi"` (removes the bag's characters from both ends) |
| `string-left-trim` | `(string-left-trim "x" "xxhi")` | `"hi"` |
| `string-right-trim` | `(string-right-trim "x" "hixx")` | `"hi"` |
| `read-line` | `(read-line)`, `(read-line stream)` | Read one line from stdin (or from an input stream), return as string. `nil` on EOF |
| `y-or-n-p` | `(y-or-n-p "Delete ~A?" f)` | Print the optional `format` control plus `" (y or n) "`, read a LINE from stdin, and answer `t` for `y`/`Y`, `nil` for `n`/`N`, re-asking otherwise. Lite: CL reads single characters without echo, and end of input answers `nil` |
| `peek-char` | `(peek-char nil s)`, `(peek-char t s)`, `(peek-char #\; s)` | The next character of a stream WITHOUT consuming it. `peek-type` `nil` skips nothing, `t` skips whitespace, a character skips up to that character; the character returned is left in the stream. At EOF, signal `end-of-file`, or return `eof-value` when `eof-error-p` is `nil` |
| `read-char-no-hang` | `(read-char-no-hang s)` | One character if one is available without waiting. On a stream handle it is `read-char`; on a [Gray stream](../guides/gray-streams.md) instance it dispatches to `rontolisp:stream-read-char-no-hang` |
| `unread-char` | `(unread-char c s)` | Push the character just read back, so the next read returns it again; answers `nil`. One character for one stream, on a [Gray stream](../guides/gray-streams.md) instance and on a stream handle alike |
| `open` | `(open "f.txt")`, `(open "f.txt" :output)`, `(open "f.bin" :input '(unsigned-byte 8))` | Open a file and return a stream. The direction must be the literal `:input` (default, read) or `:output` (create/truncate, write); the optional element type must be the literal `'character` (default, text) or `'(unsigned-byte 8)` (binary) |
| `close` | `(close stream)` | Close a stream opened by `open`. Returns `t` |
| `probe-file` | `(probe-file "f.txt")` | The pathname when the file exists, `nil` otherwise. The only file operation that does not fail on a missing path (`open` signals). `uiop:file-exists-p` is the same operation |
| `truename` | `(truename "f.txt")` | The pathname when the file exists, an error otherwise — the signalling twin of `probe-file`, which is what makes `(ignore-errors (truename p))` a portable existence probe |
| `directory` | `(directory "src/*.lisp")` | The pathnames matching the pathspec, sorted, keeping its directory prefix and giving each subdirectory a trailing `/`. A wild NAME component matches (`*` any sequence, `?` one character, `*` alone meaning "no type" as in CL); a non-wild one designates itself, so listing a directory is `"src/*.*"`, not `"src/"`. A wild DIRECTORY component walks: `*` one level, `**` the whole subtree |
| `pathname-directory` | `(pathname-directory "a/b/c.txt")` | `(:RELATIVE "a" "b")` — the directory component of a namestring as CL's list (`:absolute`/`:relative` plus one string per level), `nil` when there is none. Pure string work; nothing is read |
| `pathname-name` | `(pathname-name "d/a.b.c")` | `"a.b"` — the file-name component without its type: everything after the last `/` and before the LAST dot (a dot at position 0 belongs to the name). `nil` when the namestring names no file |
| `pathname-type` | `(pathname-type "d/a.b.c")` | `"c"` — the type (extension) without its dot, `nil` when there is none. The other half of the same split |
| `pathname-host` | `(pathname-host "d/a.txt")` | always `nil` — a flat namestring carries no host component. The designator is still validated |
| `pathname-device` | `(pathname-device #P"d/a.txt")` | always `nil`, for the same reason (and what SBCL answers on Unix) |
| `pathname-version` | `(pathname-version #P"d/a.txt")` | always `nil` — there are no file versions here |
| `wild-pathname-p` | `(wild-pathname-p "d/*.txt" :name)` | whether the pathname (or just the `:directory`/`:name`/`:type` component named) holds a `*` or `?`. `:host`/`:device`/`:version` are always `nil` |
| `enough-namestring` | `(enough-namestring "/a/b/c.lisp" "/a/")` | `"b/c.lisp"` — the shortest namestring that still names the file when merged against the defaults (`*default-pathname-defaults*` by default): the inverse of `merge-pathnames` |
| `file-namestring` `directory-namestring` `host-namestring` | `(file-namestring #P"/a/b/c.txt")` | `"c.txt"` — the string-valued components: the name-and-type half, the directory half (they concatenate back to `namestring`), and `""` for the host a rontolisp namestring does not carry |
| `translate-pathname` | `(translate-pathname "src/f.lisp" "src/*.lisp" "build/*.fasl")` | `#P"build/f.fasl"` — matches the source against the from-wildcard and substitutes what each `*`/`?` captured into the to-wildcard. A source that does not match signals |
| `translate-logical-pathname` | `(translate-logical-pathname "d/a.txt")` | `#P"d/a.txt"` — the identity: every rontolisp pathname is physical, so there is nothing to translate |
| `logical-pathname` | `(logical-pathname "SYS:SRC;")` | always signals — no logical host can be defined here, so no argument can name a logical pathname |
| `pathname` | `(pathname "d/x")` | `#P"d/x"` — the canonical constructor: a pathname unchanged, a string wrapped into the pathname it designates, anything else signals |
| `parse-namestring` | `(parse-namestring "d/a.txt")` | `#P"d/a.txt"` (and the stop position as a second value) — lite: no host parsing, the whole string is the namestring |
| `make-pathname` | `(make-pathname :name "b" :defaults "d/a.sql")` | `#P"d/b.sql"` — composes a pathname from `:directory`/`:name`/`:type`, taking every UNSUPPLIED component from `:defaults`. Component-wise, NOT a merge: a supplied component replaces the defaults' one and an explicit `nil` means "no component". A real function on all four backends; literal calls are additionally folded at compile time |
| `namestring` | `(namestring #P"/tmp/x")` | `"/tmp/x"` — the namestring a pathname carries; a string (a designator) passes through, anything else signals. `uiop:namestring` and `uiop:native-namestring` are the same function |
| `merge-pathnames` | `(merge-pathnames "zoneinfo/" "/opt/lt/")` | Fills the gaps in the first pathname from the second (both spellings accepted): an absolute directory wins, a relative one is appended, an absent one is taken from the defaults. `uiop:merge-pathnames*` is the same merge |
| `open-stream-p` | `(open-stream-p stream)` | `t` while the handle names an open stream, `nil` after `close` (exact for sockets on the interpreter/JVM and on `--component`) |
| `force-output` | `(force-output stream)` | Flush an output stream (no argument = standard output). Returns nil |
| `finish-output` | `(finish-output stream)` | The same operation as `force-output` -- every write here is synchronous once flushed |
| `clear-output` | `(clear-output stream)` | Discard an output stream's unwritten buffer. Nothing is buffered that way here, so it validates the designator and returns nil |
| `listen` | `(listen stream)` | `t` when input is immediately available without blocking; Preview 1 WASM has no such probe |
| `write-line` | `(write-line "hi" stream)`, `(write-line "hi")` | Write the string plus a newline to an output stream (or to standard output). Returns the string |
| `read-byte` | `(read-byte stream)`, `(read-byte *standard-input* nil nil)` | Read one byte (0-255) from a binary input stream, or from standard input for the `t`/`nil` designator. At EOF, signal an `end-of-file` condition, or return `eof-value` when `eof-error-p` is `nil` |
| `write-byte` | `(write-byte 255 stream)`, `(write-byte 255 *standard-output*)` | Write one raw byte (0-255) to a binary output stream, or to standard output for the `t`/`nil` designator. Returns the byte |
| `read-sequence` | `(read-sequence buf stream)`, `(read-sequence buf stream :start 2 :end 4)` | Fill a vector from an input stream -- characters when the buffer is a character vector, raw little-endian elements in bulk when it is a packed float array (any rank) or packed integer vector, else bytes. Returns the fill position. `:start`/`:end` must be literal keywords |
| `write-sequence` | `(write-sequence "abcd" s :start 1 :end 3)`, `(write-sequence buf stream)` | Write a sequence to a stream and return it. A string is written as characters (like `write-string`); a packed float array / integer vector as raw little-endian elements in bulk; a vector of bytes (0-255) to a binary output stream. `:start`/`:end` must be literal keywords |
| `read` | `(read)`, `(read stream)` | Read one S-expression from stdin (or from an input stream opened by `open`/`with-open-file`) (all three backends). `nil` on EOF |
| `read-from-string` | `(read-from-string "(+ 1 2)")` | Parse one datum from a string (all three backends). The optional `eof-error-p`/`eof-value` and `:start`/`:end` arguments are not supported |
| `parse-integer` | `(parse-integer "42")`, `(parse-integer "ff" :radix 16)`, `(parse-integer "12x" :junk-allowed t)` | Parse an integer from a string. Supports `:start`/`:end`/`:radix`/`:junk-allowed` on all backends; the stop position is the second value, observable through `multiple-value-bind`. Without `:junk-allowed`, trailing non-whitespace is an error |
| `copy-readtable` | `(copy-readtable nil)` | Lite stub: always `nil` -- the reader is not readtable-driven, so there is no readtable object (`*readtable*` exists but is seeded to `nil`) |
| `set-dispatch-macro-character` | `(set-dispatch-macro-character #\# #\7 fn)` | Lite stub: accepted and ignored, returns `t` (user dispatch macros cannot extend the reader) |
| `readtable-case` | `(readtable-case *readtable*)` | Lite stub: always `:upcase` -- the reader always upcases unescaped symbol names, the standard readtable's mode |
| `char` `schar` | `(char "hello" 1)` | `#\e` -- the character at a 0-based string index |
| `char-code` | `(char-code #\A)` | `65` -- the code point of a character |
| `code-char` | `(code-char 66)` | `#\B` -- the character with a given code point |
| `char=` `char<` `char<=` `char>` `char>=` `char/=` `char-equal` | `(char< #\a #\b #\c)` | `t` (variadic comparison by code point; `char/=` = pairwise distinct, `char-equal` = case-insensitive `char=`) |
| `char-lessp` `char-greaterp` `char-not-lessp` `char-not-greaterp` `char-not-equal` | `(char-lessp #\a #\B)` | `t` (the case-INSENSITIVE ordering family) |
| `char-upcase` `char-downcase` | `(char-upcase #\a)` | `#\A` (full-Unicode case folding on every backend) |
| `characterp` | `(characterp #\a)` | `t` |
| `alpha-char-p` | `(alpha-char-p #\x)`, `(alpha-char-p #\5)` | `t`, `nil` (ASCII letters in the WASM backend) |
| `alphanumericp` | `(alphanumericp #\x)`, `(alphanumericp #\-)` | `t`, `nil` (letter or decimal digit) |
| `graphic-char-p` `standard-char-p` | `(graphic-char-p #\Space)`, `(standard-char-p #\Newline)` | `t`, `t` (printing character / the 96 standard characters) |
| `make-load-form` | `(make-load-form obj)` | Generic function the compiler consults for a literal OBJECT in code; a method's form reconstructs it (no system method, like `print-object`) |
| `make-load-form-saving-slots` | `(make-load-form-saving-slots obj)` | The ready-made answer: the form that rebuilds the object with its current slot values |
| `sxhash` | `(sxhash "ab")` | Structural hash (integers/characters/strings/symbols/conses); stable within a run, not across backends |
| `sbit` | `(sbit #*0110 1)` | Bit-vector element read; `(setf (sbit v i) b)` writes |
| `bit` | `(bit #*0110 1)` | Bit-array element read; `(setf (bit v i) b)` writes |
| `both-case-p` | `(both-case-p #\a)` | True for a cased letter (`lower-case-p` or `upper-case-p`) |
| `special-operator-p` | `(special-operator-p 'if)` | `t` for the 25 ANSI special operators, `nil` for everything else |
| `macro-function` | `(macro-function 'when)` | The macro expander (real on the interpreter, a signalling stub in compiled output), `nil` for a function or special operator |
| `compiled-function-p` | `(compiled-function-p #'car)` | Lite stub: always `nil` |
| `function-lambda-expression` | `(function-lambda-expression #'car)` | Lite stub: `(values nil t nil)` (no source recorded) |
| `list-all-packages` | `(list-all-packages)` | Every registered package, as the keywords `find-package` answers (the compilers answer from a table baked in at compile time) |
| `find-class` | `(find-class 'c)` | The memoized (`eq`-stable) class metaobject; signals unless `errorp` is `nil` |
| `allocate-instance` | `(allocate-instance (find-class 'c))` | A fresh instance with every slot unbound; no initforms, no `initialize-instance` |
| `class-name` | `(class-name (class-of 42))` | The name symbol of a class metaobject |
| `get` | `(get 'sym 'prop)`, `(setf (get 'sym 'prop) v)` | Symbol property lists over one program-global name-keyed store |
| `symbol-plist` | `(symbol-plist 'sym)` | The whole property list `get` indexes into, out of the same store; no `(setf symbol-plist)` |
| `remprop` | `(remprop 'sym 'prop)` | Drop one property from the same store; `t` when it was there, `nil` when not |
| `lower-case-p` `upper-case-p` | `(lower-case-p #\a)`, `(upper-case-p #\A)` | `t`, `t` -- true when up/down-casing changes the character (follows the Unicode case tables) |
| `digit-char-p` | `(digit-char-p #\7)`, `(digit-char-p #\f 16)` | `7`, `15` -- the digit weight in the given radix (default 10), or nil |
| `digit-char` | `(digit-char 11 16)` | `#\B` -- the character for a weight in the radix (default 10), or nil |
| `eval` | `(eval '(+ 1 2))` | Evaluate an expression (all three backends). Returns the result |
| `compile` | `(compile nil '(lambda (x) (* x x)))` | Coerce a lambda expression to a function (null lexical environment). In compiled programs, only the definition-time method-construction idiom is supported |
| `load` | `(load "bar.lisp")` | Read and evaluate every top-level form in a file in the global environment (all three backends). Returns `t` |
| `require` | `(require :util)`, `(require :util "lib/util.lisp")` | Load a module's file (`<name>.lisp` next to the requiring file, or the explicit path) unless already `provide`d. Returns the module name. On the compile path it must be a literal, top-level form |
| `provide` | `(provide :util)` | Mark a module as loaded so a later `require` of it is a no-op. Returns the module name. On the compile path it must be a literal, top-level form |
| `gensym` | `(gensym)`, `(gensym "tmp")` | `#:g1`, `#:tmp2` -- a fresh symbol for macro temporaries (the counter is program-wide) |
| `make-symbol` | `(make-symbol "temp")` | `#:temp` -- a fresh uninterned symbol (the gensym `#:` convention, no counter) |
| `gentemp` | `(gentemp "Q")` | `Q1` -- a fresh INTERNED symbol named prefix + a counter (CLHS-deprecated; iterate uses it) |
| `copy-symbol` | `(copy-symbol 'foo)` | `#:FOO` -- an uninterned symbol of the same name; the property-list argument is ignored, and the copy inherits `make-symbol`'s identity deviation |
| `intern` | `(intern "foo")` | The symbol `foo`. On the interpreter the name is interned into the current package (`in-package` state); `(intern name :keyword)` builds a keyword, any other package argument is an error |
| `find-symbol` | `(find-symbol "car")` | `car` when the name is known (cl symbol, keyword, or user definition), else `nil`; a package that does not exist yields `nil` too (compilers: only a literal string can answer `nil`) |
| `find-package` | `(find-package :cl)` | `:cl` -- lite: the upcased package name as a keyword (no package objects), `nil` when unknown (the compilers answer a computed designator from a table baked in at compile time) |
| `symbol-name` | `(symbol-name 'foo)` | `"FOO"` -- symbols read upcased like CL, so `(symbol-name 'car)` is `"CAR"` too |
| `symbol-package` | `(symbol-package :foo)` | `:keyword` -- the same keyword shape `find-package` returns (`:cl` for standard symbols, `:cl-user` otherwise, `nil` for `#:` symbols); the compilers answer `:cl-user` for both `cl` and `cl-user` |
| `package-name` | `(package-name (find-package :cl-user))` | `"CL-USER"` -- the name string of a package designator, resolved through `find-package`; an unknown designator signals |
| `package-use-list` | `(package-use-list :cl-user)` | `(:CL)` -- the packages a package uses, as `find-package` keywords; an unknown designator signals |
| `package-used-by-list` | `(package-used-by-list :cl)` | The inverse: every package whose use list names this one |
| `package-shadowing-symbols` | `(package-shadowing-symbols :cl-user)` | Always `nil` (there is no symbol shadowing); the designator is still validated |
| `symbol-value` | `(symbol-value '*level*)` | The global variable's value; unbound names signal an error (lexical bindings are invisible) |
| `boundp` | `(boundp '*level*)` | `t` when the symbol names a bound global variable (t/nil/keywords are self-bound) |
| `fboundp` | `(fboundp 'car)` | `t` for functions, macros and special forms (compilers: a computed argument sees functions only) |
| `fmakunbound` | `(fmakunbound 'greet)` | `greet` -- makes the name call-time-undefined again (compilers: late-bound references only) |
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
| `arrayp` | `(arrayp "abc")` | `T` -- a string is an array in CL, like `vectorp` |
| `simple-string-p` | `(simple-string-p "hello")` | `t` -- every rontolisp string is "simple" (lite) |
| `listp` | `(listp '(1 2))` | `t` |
| `consp` | `(consp '(1 2))` | `t` |
| `keywordp` | `(keywordp :foo)` | `t` |
| `constantp` | `(constantp 5)`, `(constantp 'x)` | `t`, `nil` -- true for self-evaluating objects (numbers, strings, characters, keywords, `t`/`nil`) and `(quote x)` forms (lite); an optional environment argument is accepted and ignored |
| `streamp` | `(streamp s)` | `t` if `s` is a stream, else `nil` (lite: streams are integer handles, so equivalent to `integerp`; also backs the `stream` type specifier) |
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
| `ldiff` | `(ldiff '(1 2 3) '(3))` | `(1 2)` (fresh copy of the elements before the given tail; the whole list if it is not a tail) |
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
| `count-if-not` | `(count-if-not #'evenp '(1 2 3 4 5))` | `3` (number of elements FAILING the predicate; `:key`/`:start`/`:end`/`:from-end`) |
| `assoc` | `(assoc 'b '((a . 1) (b . 2)))` | `(b . 2)` (first pair whose car matches the key, or nil; `eql` compare by default, optional `:test`/`:key` keywords, e.g. `(assoc "b" '(("a" . 1) ("b" . 2)) :test #'equal)`) |
| `assoc-if` | `(assoc-if #'oddp '((2 a) (3 b)))` | `(3 b)` (first pair whose car satisfies the predicate, or nil) |
| `getf` | `(getf '(:a 1 :b 2) :b)` | `2` (value following the indicator in a property list, or nil; the partner of `remf`. Two arguments only: no `&optional default`) |
| `last` | `(last '(1 2 3))`, `(last '(1 2 3) 2)` | `(3)`, `(2 3)` (last cons cell, or the last `n` conses; nil for an empty list) |
| `butlast` | `(butlast '(1 2 3))` | `(1 2)` (copy without the last element; nil for an empty or single-element list) |
| `remove` | `(remove 2 '(1 2 3 2))` | `(1 3)` (new list without items `eql` to the given one; optional `:test`/`:key` keywords) |
| `remove-if` | `(remove-if #'evenp '(1 2 3 4))` | `(1 3)` (new list without items satisfying the predicate) |
| `remove-if-not` | `(remove-if-not #'evenp '(1 2 3 4))` | `(2 4)` (new list keeping only items satisfying the predicate) |
| `remove-duplicates` | `(remove-duplicates '(1 2 1 3))` | `(2 1 3)` (copy with duplicate elements removed, keeping the last occurrence; `eql` compare by default, optional `:test`/`:key` keywords, `:from-end t` keeps the first occurrence) |
| `delete-duplicates` | `(delete-duplicates '(1 2 1 3) :from-end t)` | `(1 2 3)` (`remove-duplicates`' would-be-destructive twin, same rendering and keywords — the standard requires using the result) |
| `delete` | `(delete 2 '(1 2 3 2))` | `(1 3)` (destructive `remove`: splices out matching cells in place; optional `:test`/`:key` keywords; use the return value since the head may change) |
| `delete-if` | `(delete-if #'evenp '(1 2 3 4))` | `(1 3)` (destructive `remove-if`) |
| `delete-if-not` | `(delete-if-not #'evenp '(1 2 3 4))` | `(2 4)` (destructive `remove-if-not`) |
| `subst` | `(subst 'x 'a '(a (b a) c))` | `(x (b x) c)` (non-destructive tree substitution; optional `:test`/`:key` keywords) |
| `search` | `(search "bc" "abcd")` | `1` (position of one sequence inside another, or nil; `:start1`/`:end1`/`:start2`/`:end2`/`:test`/`:key`/`:from-end`) |
| `mismatch` | `(mismatch "apple" "apricot")` | `2` -- the index into the first sequence where the two differ, or nil; same keywords as `search` |
| `tree-equal` | `(tree-equal '(1 (2 3)) '(1 (2 3)))` | `t` (same tree shape with leaves matching under `:test` (default `eql`) or `:test-not`) |
| `substitute` | `(substitute 0 2 '(1 2 3 2))` | `(1 0 3 0)` (copy with every element `eql` to the old item replaced by the new one; optional `:test`/`:key` keywords) |
| `nsubstitute` | `(nsubstitute 0 2 '(1 2 3 2))` | `(1 0 3 0)` (destructive `substitute`: rewrites matching cars in place; optional `:test`/`:key` keywords) |
| `substitute-if` | `(substitute-if 0 #'oddp '(1 2 3))` | `(0 2 0)` (copy with every element satisfying the predicate replaced; optional `:key`, no `:test`) |
| `substitute-if-not` | `(substitute-if-not 0 #'oddp '(1 2 3))` | `(1 0 3)` (the complement of `substitute-if`) |
| `nsubstitute-if` | `(nsubstitute-if 0 #'oddp (list 1 2 3))` | `(0 2 0)` (destructive `substitute-if`; lists only) |
| `nsubstitute-if-not` | `(nsubstitute-if-not 0 #'oddp (list 1 2 3))` | `(1 0 3)` (destructive `substitute-if-not`; lists only) |
| `get-setf-expansion` | `(get-setf-expansion 'x)` | the five setf-expansion values, consumed with `multiple-value-bind` (lite: variable and accessor places) |
| `nconc` | `(nconc (list 1 2) (list 3 4) (list 5))` | `(1 2 3 4 5)` (destructively concatenate any number of lists; returns the first non-`nil` argument) |
| `copy-list` | `(copy-list '(1 2 3))` | `(1 2 3)` (shallow copy of a list) |
| `copy-tree` | `(copy-tree '(1 (2 3)))` | `(1 (2 3))` (deep copy of a cons tree) |
| `sublis` | `(sublis '((a . 1)) '(a b))` | `(1 B)` (fresh tree with every subtree matching an alist key replaced; `:key`/`:test`/`:test-not`) |
| `nreverse` | `(nreverse '(1 2 3))` | `(3 2 1)` (destructively reverse a list by rewiring each `cdr`; use the return value) |
| `make-list` | `(make-list 3 :initial-element 0)` | `(0 0 0)` (list of n cells sharing the one element value; `nil` by default) |
| `union` | `(union '(1 2 3) '(2 3 4))` | `(4 1 2 3)` (set union, `eql` compare by default, optional `:test`/`:key` keywords; result order unspecified) |
| `intersection` | `(intersection '(1 2 3) '(2 3 4))` | `(3 2)` (set intersection, `eql` compare by default, optional `:test`/`:key` keywords; result order unspecified) |
| `set-difference` | `(set-difference '(1 2 3) '(2))` | `(3 1)` (elements of the first list not in the second, `eql` compare by default, optional `:test`/`:key` keywords; result order unspecified) |
| `set-exclusive-or` | `(set-exclusive-or '(1 2 3) '(2 3 4))` | `(1 4)` (symmetric difference: the elements of either list with no match in the other; optional `:test`/`:test-not`/`:key` keywords; result order unspecified) |
| `adjoin` | `(adjoin 1 '(2 3))` | `(1 2 3)` (prepend the item unless already a member; `eql` compare by default, optional `:test`/`:key` keywords) |
| `subsetp` | `(subsetp '(1 2) '(1 2 3))` | `T` (true when every element of the first list is a member of the second; `eql` compare by default, optional `:test`/`:key` keywords) |
| `list*` | `(list* 1 2 '(3 4))`, `(list* 1 2 3)` | `(1 2 3 4)`, `(1 2 . 3)` (cons the leading arguments onto the last one as the tail) |
| `acons` | `(acons 'a 1 nil)` | `((a . 1))` (prepend a `(key . value)` pair to an alist) |
| `endp` | `(endp nil)`, `(endp '(1))` | `t`, `nil` (end-of-list test; a synonym for `null`, the improper-list error is relaxed) |
| `elt` | `(elt '(a b c) 1)` | `b` (0-based element access; lists only, no string indexing) |
| `rassoc` | `(rassoc 2 '((a . 1) (b . 2)))` | `(b . 2)` (first pair whose cdr matches the value, or nil; `eql` compare by default, optional `:test`/`:key` keywords) |
| `rassoc-if` | `(rassoc-if #'oddp '((a . 2) (b . 3)))` | `(b . 3)` (first pair whose cdr satisfies the predicate, or nil) |
| `pairlis` | `(pairlis '(a b) '(1 2))` | `((a . 1) (b . 2))` (pair up a list of keys and a list of values into an alist; an optional third argument is appended as the tail) |
| `copy-alist` | `(copy-alist '((a . 1)))` | `((a . 1))` (copy an alist's spine and its pair cells; the keys and values themselves are shared) |
| `revappend` | `(revappend '(1 2 3) '(4 5))` | `(3 2 1 4 5)` (reverse the first list and append the second) |
| `nreconc` | `(nreconc '(1 2 3) '(4 5))` | `(3 2 1 4 5)` (destructive `revappend`: expands to `(nconc (nreverse x) y)`, reusing the cons cells of the first list) |
| `maplist` | `(maplist #'identity '(1 2 3))` | `((1 2 3) (2 3) (3))` (apply to successive tails, collect results; takes any number of lists, stopping at the shortest) |
| `mapcon` | `(mapcon (lambda (x) (list (car x))) '(1 2 3))` | `(1 2 3)` (apply to successive tails, concatenate the result lists; takes any number of lists) |
| `mapl` | `(mapl #'identity '(1 2 3))` | `(1 2 3)` (apply to successive tails for effect, return the first list; takes any number of lists) |
| `sort` | `(sort '(3 1 2) #'<)` | `(1 2 3)` (destructively sort a list with a comparison predicate; not stable) |
| `merge` | `(merge 'list (list 1 3) (list 2 4) #'<)` | `(1 2 3 4)` (one sorted sequence from two, stable; the result type is built by `coerce`, so `list`/`vector`/`string`; non-destructive) |
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
| `random` | `(random 100)`, `(random 1.0)` | a value in `[0, 100)` / `[0.0, 1.0)` (the result type follows the limit; `(random 1)` is always `0`). every backend draws from a generator inside the program (`ThreadLocalRandom` on the interpreter and JVM, a built-in generator on WASM), seeded once per run from the host's entropy where there is a host — WASI `random_get` in Preview 1 mode, `wasi:random@0.3.0` in `--component` mode — so the sequence differs each run |
| `make-random-state` | `(make-random-state t)` | always `nil` -- no random-state objects exist; `random` accepts and ignores an optional state argument, so the store-and-pass-back seeding idiom works unchanged |
| `get-universal-time` | `(get-universal-time)` | seconds since 1900-01-01 GMT, as an integer on every backend (WASM reads the real host clock in Preview 1, `wasi:clocks@0.3.0` in `--component` mode) |
| `encode-universal-time` | `(encode-universal-time 0 0 0 1 1 1970 0)` | `2208988800` -- decoded components to universal time; a missing time zone means GMT, not the local zone |
| `decode-universal-time` | `(decode-universal-time 2208988800 0)` | the nine decoded values (second, minute, hour, date, month, year, day-of-week, daylight-p, zone); `daylight-p` is always nil |
| `get-internal-real-time` | `(get-internal-real-time)` | elapsed real time in milliseconds (integer on every backend) |
| `get-internal-run-time` | `(get-internal-run-time)` | consumed run time in milliseconds (integer on every backend) |
| `sleep` | `(sleep 0.5)` | block for a non-negative number of seconds and return `nil` (a real host timer everywhere but WASM Preview 1, which busy-waits on the clock, and `--no-wasi`, which signals) |
| `lisp-implementation-type` `lisp-implementation-version` `software-type` `software-version` `machine-type` `machine-version` `machine-instance` `short-site-name` `long-site-name` | `(lisp-implementation-type)` | `"rontolisp"` — the environment enquiry constants: the version is the build's, `software-type` is `"Unix"`, `machine-type` is the ABI targeted (`"JVM"` / `"WASM32"`), and everything rontolisp cannot know is `nil` |
| `user-homedir-pathname` | `(user-homedir-pathname)` | The `HOME` directory as a DIRECTORY pathname (trailing separator), or `nil` when the variable is unset |
| `invoke-debugger` | `(invoke-debugger c)` | Signals the condition and never returns -- no backend has a debugger to enter |
| `compile-file` `compile-file-pathname` `remove-method` | `(compile-file "x.lisp")` | Exist and signal: a rontolisp program is compiled whole (no fasl, no pathname naming one) and a method is not a first-class object |
| `exp` | `(exp 0)` | `1.0` (interpreter/JVM use `Math.exp`; WASM uses a software approximation) |
| `log` | `(log 1)` | `0.0` (natural log; interpreter/JVM use `Math.log`, WASM a software approximation) |
| `sin` `cos` `tan` | `(sin 0)`, `(cos 0)` | `0.0`, `1.0` (interpreter/JVM use `Math.sin`/`cos`/`tan`, WASM a software approximation) |
| `asin` `acos` `atan` | `(atan 0)` | `0.0` (all backends -- WASM uses a software approximation) |
| `sinh` `cosh` `tanh` | `(tanh 0)` | `0.0` (all backends -- WASM derives all three from its software `exp`) |
| `gcd` | `(gcd 12 18)`, `(gcd 24 36 60)` | `6`, `12` (variadic; greatest common divisor, `(gcd)` is `0`) |
| `lcm` | `(lcm 4 6)`, `(lcm 2 3 4)` | `12`, `12` (variadic; least common multiple; `0` if any argument is `0`, `(lcm)` is `1`) |
| `signum` | `(signum -5)`, `(signum 3.5)` | `-1`, `1.0` (sign, preserving integer/float type) |
| `logand` | `(logand 12 10)`, `(logand 12 10 6)` | `8`, `0` (variadic bitwise AND; `(logand)` is `-1`) |
| `logior` | `(logior 12 10)`, `(logior 1 2 4 8)` | `14`, `15` (variadic bitwise inclusive OR; `(logior)` is `0`) |
| `logxor` | `(logxor 12 10)` | `6` (variadic bitwise exclusive OR; `(logxor)` is `0`) |
| `lognot` | `(lognot 5)` | `-6` (bitwise NOT, i.e. ones' complement) |
| `logandc1` | `(logandc1 12 10)` | `2` (AND of the complement of the first argument with the second) |
| `logandc2` | `(logandc2 12 10)` | `4` (AND of the first argument with the complement of the second) |
| `logorc1` | `(logorc1 12 10)` | `-5` (OR of the complement of the first argument with the second) |
| `logorc2` | `(logorc2 12 10)` | `-3` (OR of the first argument with the complement of the second) |
| `ash` | `(ash 1 4)`, `(ash 255 -4)` | `16`, `15` (arithmetic shift: left for a non-negative count, right otherwise) |
| `logtest` | `(logtest 1 3)`, `(logtest 1 2)` | `T`, `NIL` (any bits set in common; `(not (zerop (logand a b)))`) |
| `funcall` | `(funcall #'+ 3 4)` | Apply a function to args. Accepts a function value (`#'f`, a lambda) or a symbol naming a function (`(funcall 'car ...)`) |
| `mapcar` | `(mapcar #'car '((1 2) (3 4)))` | Apply a function to each element, return new list |
| `map` | `(map 'list #'+ '(1 2 3) '(10 20 30))` | `(11 22 33)` (map over sequences -- list/string -- up to the shortest, building a `'list`/`'string` result, or nil for effect) |
| `mapc` | `(mapc #'print '(1 2 3))` | Apply a function to each element for effect, return the first list; takes any number of lists, stopping at the shortest |
| `mapcan` | `(mapcan (lambda (x) (list x x)) '(1 2))` | `(1 1 2 2)` (apply a function and concatenate the result lists; takes any number of lists, and uses non-destructive `append`) |
| `apply` | `(apply #'+ 1 2 '(3 4))` | `10` (apply a function to the leading args plus the spread final list) |
| `values` | `(values 1 2 3)`, `(multiple-value-list (values 1 2 3))` | `1`, `(1 2 3)` -- an ordinary context keeps the primary value only; `multiple-value-bind`/`-list`/`-call`/`nth-value` receive all values of a literal `(values ...)` call, the multi-value built-ins (`floor` family, `gethash`, `parse-integer`, `values-list`) and a user function returning `(values ...)` |
| `reduce` | `(reduce #'+ '(1 2 3) :initial-value 0)` | Left fold: `(f (f (f init a) b) c)`. Plain form `(reduce f list)` uses the first element as init; the `:initial-value` keyword (literal) supplies an explicit seed |
| `every` | `(every #'evenp '(2 4 6))`, `(every #'< '(1 2) '(3 4))` | `t` if the predicate is non-nil for every element (tuple), else `nil`; any number of sequences, stopping at the shortest |
| `some` | `(some #'oddp '(2 4 5))`, `(some #'> '(1 5) '(3 4))` | The first non-nil predicate result, or `nil` if every element (tuple) fails; any number of sequences |
| `notany` | `(notany #'evenp '(1 3 5))` | `t` if the predicate is nil for every element (tuple), else `nil` (the complement of `some`) |
| `notevery` | `(notevery #'evenp '(2 4 5))` | `t` if the predicate is nil for some element (tuple), else `nil` (the complement of `every`) |
| `symbol-function` | `(symbol-function 'car)` | Return the function named by a symbol (compilers: the argument must be a quoted symbol literal) |
| `identity` | `(identity 42)` | `42` (return the argument unchanged) |
| `constantly` | `(mapcar (constantly 7) '(a b c))` | `(7 7 7)` (a function of any arguments answering one fixed value) |
| `make-hash-table` | `(make-hash-table)`, `(make-hash-table :test 'equal)` | Create an empty hash table. `:test` is accepted but informational (see the note below); other keywords such as `:size` are ignored |
| `gethash` | `(gethash key table)`, `(gethash key table default)` | Return the value stored under `key`, or `default` (nil if omitted) when absent |
| `(setf (gethash key table) v)` | `(setf (gethash "a" h) 1)` | Store `v` under `key`; works with `incf`/`decf`/`push` on the place |
| `remhash` | `(remhash key table)` | Remove the entry for `key`; returns `t` if one was removed, else `nil` |
| `clrhash` | `(clrhash table)` | Remove all entries; returns the table |
| `hash-table-count` | `(hash-table-count table)` | The number of entries |
| `hash-table-test` | `(hash-table-test table)` | Always `EQUAL`: every backend keys structurally, whatever `:test` was requested |
| `hash-table-size` | `(hash-table-size table)` | The entry count (a rontolisp table has no separate capacity) |
| `hash-table-rehash-size` | `(hash-table-rehash-size table)` | The standard default `1.5` (growth belongs to the host map) |
| `hash-table-rehash-threshold` | `(hash-table-rehash-threshold table)` | The standard default `1.0` |
| `hash-table-p` | `(hash-table-p x)` | `t` if `x` is a hash table, else `nil` |
| `maphash` | `(maphash (lambda (k v) ...) table)` | Call the function on each key/value pair for effect; returns nil |
| `make-array` | `(make-array 5 :initial-element 0)`, `(make-array (list 2 3))` | Create an array of any rank; `:initial-element` sets every cell (nil if omitted). `:element-type` may be computed |
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
| `coerce` | `(coerce '(1 2 3) 'vector)`, `(coerce "ab" 'list)` | `#(1 2 3)`, `(#\a #\b)`; the `'list`/`'vector`/`'string` and float families, `t`, and a computed result type |
| `fill-pointer` | `(fill-pointer v)` | The fill pointer of a `:fill-pointer` vector (its effective length); also a `setf` place |
| `array-has-fill-pointer-p` | `(array-has-fill-pointer-p a)` | `t` if the array has a fill pointer, else `nil` |
| `adjustable-array-p` | `(adjustable-array-p a)` | `t` if the array was created `:adjustable`, else `nil` |
| `array-element-type` | `(array-element-type a)` | Always `t` (element types are not tracked) |
| `vector-push` | `(vector-push x v)` | Store `x` at the fill pointer and return the index, or `nil` when full |
| `vector-pop` | `(vector-pop v)` | Decrement the fill pointer and return the element it passed |
| `vector-push-extend` | `(vector-push-extend x v &optional ext)` | Like `vector-push` but grows the vector when full |
| `subtypep` | `(subtypep 'integer 'number)` | `t` -- the built-in type lattice plus `defclass`/condition hierarchies; a single value, unknown pairs answer `nil`; the compilers fold literal specifiers at compile time |
| `mask-field` | `(mask-field (byte 4 4) 255)` | `240` -- the `ldb` field left in its original position |
| `scale-float` | `(scale-float 1.5 3)` | `12.0` -- `float × 2^n` with IEEE semantics |
| `decode-float` | `(decode-float 6.5)` | `0.8125`, `3`, `1.0` -- significand in [1/2, 1), binary exponent, sign |
| `char-name` | `(char-name #\Space)` | `"Space"` -- `nil` for graphic characters |
| `fdefinition` | `(fdefinition 'car)` | the function value, like `symbol-function` |
| `use-package` | `(use-package :mypkg)` | add packages to a package's use list, so their external symbols are visible unqualified (a literal top-level call is a compile-time directive) |
| `export` | `(export '(run))` | make symbols external in a package (a literal top-level call is a compile-time directive) |
| `unexport` | `(unexport 'run)` | the inverse of `export`: the symbol stays present but is no longer visible unqualified |
| `import` | `(import 'other:sym)` | make another package's symbol accessible unqualified -- the runtime form of `:import-from` (a literal top-level call is a compile-time directive) |
| `file-position` | `(file-position s)` | always `nil` (lite: streams do not support repositioning) |
| `file-length` | `(file-length s)` | the byte length of the file a file stream is open on; `nil` for any other stream, and `nil` on both WASM backends |
| `file-write-date` | `(file-write-date "x.txt")` | the file's modification time as a universal time; `nil` when it cannot be determined (always `nil` on both WASM backends) |
| `ensure-directories-exist` | `(ensure-directories-exist "logs/app.log")` | create the pathspec's directory component and return the pathspec (signals on both WASM backends) |
| `delete-file` | `(delete-file "notes.txt")` | delete the named file and return `t`; anything that leaves it in place signals, "it was not there" included (signals on both WASM backends, like `ensure-directories-exist` and for the same reason) |
| `rename-file` | `(rename-file "notes.txt" "notes.bak")` | rename (move) the file and return the defaulted new name as a pathname; the new name is merged with the old one, so a bare file name keeps the directory. Anything that leaves the file in place signals, "it was not there" included (signals on both WASM backends, like `delete-file`) |
| `make-string-output-stream` | `(make-string-output-stream)` | a fresh string output stream -- the explicit form of what `with-output-to-string` builds |
| `make-string-input-stream` | `(make-string-input-stream string &optional start end)` | an input stream reading from a string -- the explicit form of what `with-input-from-string` binds |
| `get-output-stream-string` | `(get-output-stream-string s)` | everything written to a string output stream so far, CLEARING it (CL's contract) |
| `make-synonym-stream` | `(make-synonym-stream '*standard-output*)` | a stream forwarding every operation to the stream the named variable holds AT THAT MOMENT, for any symbol -- so rebinding the variable afterwards redirects it |
| `synonym-stream-symbol` | `(synonym-stream-symbol s)` | the symbol a synonym stream forwards to |
| `make-broadcast-stream` | `(make-broadcast-stream a b)` | an output stream fanning every write out to each component, in order; with no components, a discarding sink. A stream WITH components is a Gray stream and takes the whole output protocol |
| `pathnamep` | `(pathnamep #P"/tmp/x")` | `t` — whether the value is a pathname (the value `#P"..."` denotes); a string is NOT one, and it agrees with `(typep x 'pathname)` |
| `input-stream-p` | `(input-stream-p s)` | `t` for any stream handle |
| `output-stream-p` | `(output-stream-p s)` | `t` for any stream handle |
| `stream-element-type` | `(stream-element-type s)` | always `character` -- every stream is a character stream |
| `class-of` | `(class-of 42)` | The value's class metaobject, `eq` to `(find-class 'integer)`; built-ins, CLOS and struct instances alike |
| `type-of` | `(type-of 42)` | `integer` -- the type NAME symbol: a struct/CLOS instance answers its structure/class name, agreeing with `(class-name (class-of x))` |
| `simple-condition-format-control` | `(simple-condition-format-control c)` | the condition's `:format-control` slot, or `nil` |
| `simple-condition-format-arguments` | `(simple-condition-format-arguments c)` | the condition's `:format-arguments` slot, or `nil` |
| `type-error-datum` | `(type-error-datum c)` | the `datum` slot of a `type-error` -- the object whose type was wrong |
| `type-error-expected-type` | `(type-error-expected-type c)` | the `expected-type` slot of a `type-error` |
| `cell-error-name` | `(cell-error-name c)` | the `name` slot of a `cell-error` (`unbound-variable`, `undefined-function`, `unbound-slot`) |
| `unbound-slot-instance` | `(unbound-slot-instance c)` | the object whose slot was unbound |
| `print-object` | `(print-object obj stream)` | the generic function the printer consults; define a method to control how instances of a type print |
| `find-restart` | `(find-restart 'retry c)` | the innermost active restart with that name as a first-class object, or `nil`. Lite: the condition argument is ignored |
| `invoke-restart` | `(invoke-restart :reconnect host)` | invoke a restart by name (symbol or keyword) or object, with arguments; a `restart-case` restart transfers control to its clause |
| `compute-restarts` | `(compute-restarts)` | every active restart record, innermost first |
| `restart-name` | `(restart-name r)` | the name of a restart object |
| `muffle-warning` | `(muffle-warning w)` | invoke the `muffle-warning` restart a `warn` establishes, aborting the warning before it prints |
| `abort` | `(abort)` | invoke the innermost `abort` restart; an error when none is active |
| `continue` | `(continue)` | invoke the innermost `continue` restart (a `cerror`'s); `nil` when none is active |
| `use-value` | `(use-value v)` | invoke the innermost `use-value` restart with a value; `nil` when none is active |
| `store-value` | `(store-value v)` | invoke the innermost `store-value` restart with a value; `nil` when none is active |

## rontolisp Package Functions

The `rontolisp` package provides implementation-specific functions that are
**not part of Common Lisp**. Reference them with the `rontolisp:` qualifier (or
unqualified after `(in-package rontolisp)`); see [Packages](packages.md) for the
package system. Each name below links to its own page.

| Function | Example | Result |
|----------|---------|--------|
| `rontolisp:version` | `(rontolisp:version)` | a property list of build info (`:version`, `:build-timestamp`, `:git-commit`, `:git-branch`) |
| `rontolisp:random-bytes` | `(rontolisp:random-bytes 16)` | a vector of cryptographically strong random bytes (`SecureRandom` / WASI `random_get`) |
| `rontolisp:make-mutex` | `(rontolisp:make-mutex)` | a fresh mutual-exclusion lock, as an opaque handle (real on the interpreter and the JVM, a no-op on WASM) |
| `rontolisp:mutex-acquire` | `(rontolisp:mutex-acquire m)` | block until this thread holds the mutex; returns it (prefer `rontolisp:with-mutex`) |
| `rontolisp:mutex-release` | `(rontolisp:mutex-release m)` | release one acquisition of the mutex; returns it |
| `rontolisp:make-thread` | `(rontolisp:make-thread fn bindings)` | spawn a virtual thread running the zero-argument function, with optional `(symbol . value)` dynamic bindings established in it; returns an opaque handle (interpreter and JVM; the WASM shims signal) |
| `rontolisp:join-thread` | `(rontolisp:join-thread th)` | wait for the thread and yield its function's value; an error it died on is re-signaled here |
| `rontolisp:threadp` | `(rontolisp:threadp v)` | `t` if the value is a thread handle |
| `rontolisp:thread-alive-p` | `(rontolisp:thread-alive-p th)` | `t` while the thread is still running (`nil` after a join) |
| `rontolisp:destroy-thread` | `(rontolisp:destroy-thread th)` | interrupt the thread; returns the handle |
| `rontolisp:current-thread` | `(rontolisp:current-thread)` | the calling thread's own handle, `eq`-stable per thread (works for any thread, not only `make-thread` spawns) |
| `rontolisp:fetch` | `(rontolisp:fetch "http://example.com/")` | start an HTTP request asynchronously; returns a future |
| `rontolisp:futurep` | `(rontolisp:futurep v)` | `t` if the value is a future (as returned by calling an `async-defun` function, `rontolisp:fetch`, `rontolisp:stream-read`, ...) |
| `rontolisp:streamp` | `(rontolisp:streamp v)` | `t` if the value is an asynchronous stream (a different predicate from `cl:streamp`, which answers file streams) |
| `rontolisp:make-stream` | `(rontolisp:make-stream)` | create a fresh open asynchronous stream; one value owns both the read and the write end |
| `rontolisp:stream-read` | `(rontolisp:stream-read s)` | a future settling to the stream's next chunk, or `nil` at end of stream |
| `rontolisp:stream-write` | `(rontolisp:stream-write s "chunk")` | append a chunk (never `nil`); returns a future that settles when the stream accepted it |
| `rontolisp:stream-close` | `(rontolisp:stream-close s)` | close the write end; buffered chunks stay readable, then reads observe end of stream |
| `rontolisp:read-all` | `(rontolisp:read-all s)` | a future settling to the remaining chunks drained into one string (octet chunks -- every HTTP body stream's -- UTF-8 decoded) |
| `rontolisp:wait-for` | `(rontolisp:wait-for 100)` | a future settling to `nil` after the given milliseconds; the async counterpart of `cl:sleep` |
| `rontolisp:then` | `(rontolisp:then f (lambda (v) (* 2 v)))` | attach a transform to a future as a value; returns a fresh future on the success channel (JavaScript `.then`) |
| `rontolisp:then*` | `(rontolisp:then* f #'1+ #'1+)` | variadic chain sugar for `rontolisp:then`; each function receives the previous stage's flattened value |
| `rontolisp:catch` | `(rontolisp:catch f (lambda (c) :fallback))` | attach an error fallback to a future as a value (JavaScript `.catch`); distinct from `cl:catch`/`throw` |
| `rontolisp:finally` | `(rontolisp:finally f (lambda () (cleanup)))` | run a cleanup thunk on both success and error channels; the original outcome carries through |
| `rontolisp:http-handler` | `(rontolisp:http-handler 'handle 8080)` | serve HTTP requests with a handler function taking the Clack environment plist and returning `(status headers body)` (a blocking server; a `wasi:http` component under `--component`) |
| `rontolisp:json-parse` | `(rontolisp:json-parse "{\"n\": 1}")` | parse a JSON string (jzon-compatible): objects become hash tables with string keys, arrays vectors |
| `rontolisp:json-stringify` | `(rontolisp:json-stringify (vector 1 2))` | serialize a value to a JSON string (hash tables and CLOS instances become objects, lists and vectors arrays) |
| `rontolisp:plist-hash-table` | `(rontolisp:plist-hash-table (list :n 1))` | build a hash table from a property list (subset of `alexandria:plist-hash-table`); handy for JSON objects |
| `rontolisp:hash-table-plist` | `(rontolisp:hash-table-plist h)` | property list of a hash table's pairs (subset of `alexandria:hash-table-plist`) |
| `rontolisp:alist-hash-table` | `(rontolisp:alist-hash-table al)` | build a hash table from an association list (subset of `alexandria:alist-hash-table`) |
| `rontolisp:hash-table-alist` | `(rontolisp:hash-table-alist h)` | association list of a hash table's pairs (subset of `alexandria:hash-table-alist`) |
| `rontolisp:alist-plist` | `(rontolisp:alist-plist al)` | property list with an association list's keys and values, order preserved (subset of `alexandria:alist-plist`) |
| `rontolisp:plist-alist` | `(rontolisp:plist-alist pl)` | association list with a property list's keys and values, order preserved (subset of `alexandria:plist-alist`) |
| `rontolisp:tcp-connect` | `(rontolisp:tcp-connect "127.0.0.1" 7777)` | open a blocking TCP connection; returns a bidirectional stream handle |
| `rontolisp:tcp-listen` | `(rontolisp:tcp-listen 7777)`, `(rontolisp:tcp-listen 0 "127.0.0.1")` | bind a listening TCP socket and return a listener handle; port `0` picks a free ephemeral port |
| `rontolisp:tcp-accept` | `(rontolisp:tcp-accept listener)` | wait for a client connection (blocking); returns a bidirectional stream handle |
| `rontolisp:tcp-local-port` | `(rontolisp:tcp-local-port listener)` | the local port a listener or socket is actually bound to |
| `rontolisp:tcp-local-address` | `(rontolisp:tcp-local-address listener)` | the local IP address a listener or socket is bound to, as a string |
| `rontolisp:tcp-peer-address` | `(rontolisp:tcp-peer-address sock)` | the remote IP address of a connected socket, as a string |
| `rontolisp:tcp-peer-port` | `(rontolisp:tcp-peer-port sock)` | the remote port of a connected socket |
| `rontolisp:tcp-set-timeout` | `(rontolisp:tcp-set-timeout sock 5000)` | set a read deadline in milliseconds (`nil` clears); a timed-out read signals a catchable error |
| `rontolisp:tls-connect` | `(rontolisp:tls-connect "example.com" 443)` | open an encrypted (TLS) client connection; returns the same kind of stream handle as `tcp-connect` |
| `rontolisp:tls-listen` | `(rontolisp:tls-listen "server.p12" "changeit" 8443)` | bind an encrypted listening socket from a PKCS12 keystore; accept with `tcp-accept` |
| `rontolisp:tls-listen-pem` | `(rontolisp:tls-listen-pem "cert.pem" "key.pem" 8443)` | bind an encrypted listening socket from PEM certificate/key files |
| `rontolisp:tls-upgrade` | `(rontolisp:tls-upgrade sock "example.com")` | wrap an already-connected stream handle in TLS as a client; returns a new stream handle |
| `rontolisp:wasm-export` | `(rontolisp:wasm-export 'fact :params '(:int) :returns :int)` | mark a `defun` as host-callable when compiling to a WASM core module |
| `rontolisp:jvm-export` | `(rontolisp:jvm-export 'fact :params '(:s64) :returns :s64)` | declare a typed, Java-callable static method for a `defun` when compiling to a JVM class |
| `rontolisp:wasm-import` | `(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)` | declare a host function callable from Lisp when compiling to a WASM core module |
| `rontolisp:wit-export` | `(rontolisp:wit-export "greeter.wit" :world greeter)` | declare that the program implements a WIT world: its exports are checked against the program's `defun`s, and their types come from the WIT |
| `rontolisp:wit-import` | `(rontolisp:wit-import "store.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)` | declare that the program calls a WIT interface: every function it declares is bound as an ordinary Lisp function (`kv:bucket-get`), against a provider on the interpreter/JVM, a WASM import on Preview 1, and a `canon lower`ed component-model import under `--component`, where the host is the provider |
| `rontolisp:wit-provide` | `(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)` | bind the implementation of a `wit-import`ed interface on the interpreter and JVM backends (inert on WASM, where the host provides it) |

`rontolisp:fetch` starts an outgoing HTTP request and returns a future, resolved with
`rontolisp:await`; see the
[HTTP Requests guide](../guides/http-fetch.md) for a worked overview, and the
[fetch](functions/rontolisp-fetch.md),
[await](special-forms/rontolisp-await.md) and
[futurep](functions/rontolisp-futurep.md) reference pages for options, the
result plist, backend support, and limitations. `rontolisp:http-handler` is
the incoming counterpart of `fetch` -- it serves HTTP requests with a handler
function over the Clack environment plist and `(status headers body)`
response list; see the
[Serving HTTP guide](../guides/http-handler.md) for a worked example on every
backend, and the [http-handler](functions/rontolisp-http-handler.md) reference
page for backend support and limitations. `rontolisp:json-parse` and
`rontolisp:json-stringify` convert between JSON documents and Lisp values
(a lightweight, `com.inuoe.jzon`-compatible subset) -- for example to parse a
fetch response body; see the
[json-parse](functions/rontolisp-json-parse.md) and
[json-stringify](functions/rontolisp-json-stringify.md) reference pages for
the value mapping and limitations. The tcp functions
(`rontolisp:tcp-connect` / `tcp-listen` / `tcp-accept` / `tcp-local-port` and
the [address accessors](functions/rontolisp-tcp-addresses.md))
open plain TCP sockets whose handles work with the standard stream functions
(`read-line` / `write-line` / `read-byte` / `write-byte` / `close`); see the
[TCP Sockets guide](../guides/tcp-sockets.md) for a worked echo server, and
the [tcp-connect](functions/rontolisp-tcp-connect.md),
[tcp-listen](functions/rontolisp-tcp-listen.md),
[tcp-accept](functions/rontolisp-tcp-accept.md) and
[tcp-local-port](functions/rontolisp-tcp-local-port.md) reference pages for
backend support and limitations. A
[usocket-compatible shim](#usocket-package-functions) is layered over them for
portability with existing Common Lisp code. The TLS variants (`rontolisp:tls-connect` /
`tls-upgrade` / `tls-listen` / `tls-listen-pem`) wrap the same stream handles in TLS; see the
[tls-connect](functions/rontolisp-tls-connect.md),
[tls-upgrade](functions/rontolisp-tls-upgrade.md),
[tls-listen](functions/rontolisp-tls-listen.md) and
[tls-listen-pem](functions/rontolisp-tls-listen-pem.md) reference pages.
`rontolisp:wasm-export`,
`rontolisp:jvm-export`,
`rontolisp:wasm-import`, `rontolisp:wit-export` and `rontolisp:wit-import` are
compile-time directives; `jvm-export` is `wasm-export`'s JVM twin — a typed,
Java-callable entry point on a compiled class
([jvm-export](functions/rontolisp-jvm-export.md)); the WIT pair take a `.wit` file as the single source of
truth for a boundary, so the types are never hand-written. `wit-export` declares
that the program **implements** a WIT world (and `--scaffold-wit` generates the
implementation's skeleton from it); `wit-import` declares that it **calls** a WIT
interface, binding every function the interface declares as an ordinary Lisp
function — dispatched on the interpreter and JVM backends to a *provider*
([`rontolisp:wit-provide`](functions/rontolisp-wit-provide.md)), and lowered to
`rontolisp:wasm-import` on Preview 1 WASM, where the host is the provider, so one
source runs on every backend. rontolisp ships **no provider for any interface**:
it knows the provider mechanism, not what any particular interface is, so an
implementation of a WIT interface is ordinary Lisp code. A WIT `result`'s error
arm signals the `rontolisp:wit-error` condition, whose payload is read with
`rontolisp:wit-error-payload`. See their
[wasm-export](functions/rontolisp-wasm-export.md),
[wasm-import](functions/rontolisp-wasm-import.md),
[wit-export](functions/rontolisp-wit-export.md),
[wit-import](functions/rontolisp-wit-import.md) and
[wit-provide](functions/rontolisp-wit-provide.md) reference pages and the
[Compiling to WebAssembly](../compiling/wasm.md) guide.

## linalg Package Functions

The `linalg` package provides numpy-style vector and matrix operations over
the built-in arrays (the elementwise operations and reductions work for any
rank). It is **not part of Common Lisp**;
reference its functions with the `linalg:` qualifier (the package does not use
`cl`, so most programs stay in `cl-user` and call the qualified names). The
package is implemented once in Lisp source and behaves identically on every
backend, and its constructors build packed double-float arrays, so it computes
in floating point (`det`, `inv` and `solve` run like numpy's). Each name below
links to its own page; the [Vectors & Matrices
guide](../guides/linear-algebra.md) gives an overview and worked examples.

| Function | Example | Result |
|----------|---------|--------|
| `linalg:zeros` | `(linalg:zeros 3)`, `(linalg:zeros '(2 2))` | `#d(0.0 0.0 0.0)`, `#d((0.0 0.0) (0.0 0.0))` (shape: integer or `(rows cols)` list) |
| `linalg:ones` | `(linalg:ones '(2 2))` | `#d((1.0 1.0) (1.0 1.0))` |
| `linalg:full` | `(linalg:full '(2 2) 7)` | `#d((7.0 7.0) (7.0 7.0))` |
| `linalg:zeros-like` | `(linalg:zeros-like #2A((1 2) (3 4)))` | `#d((0.0 0.0) (0.0 0.0))` (zeros with the input's shape and width) |
| `linalg:eye` | `(linalg:eye 2)` | `#d((1.0 0.0) (0.0 1.0))` (the identity matrix) |
| `linalg:arange` | `(linalg:arange 5)`, `(linalg:arange 2 10 2)` | `#d(0.0 1.0 2.0 3.0 4.0)`, `#d(2.0 4.0 6.0 8.0)` (stop exclusive; step may be negative) |
| `linalg:linspace` | `(linalg:linspace 0 1 5)` | `#d(0.0 0.25 0.5 0.75 1.0)` (n evenly spaced values, inclusive) |
| `linalg:from-list` | `(linalg:from-list '((1 2) (3 4)))` | `#d((1.0 2.0) (3.0 4.0))` (a flat list gives a vector) |
| `linalg:to-list` | `(linalg:to-list (linalg:eye 2))` | `((1.0 0.0) (0.0 1.0))` |
| `linalg:shape` | `(linalg:shape #2A((1 2 3) (4 5 6)))` | `(2 3)` |
| `linalg:ndim` | `(linalg:ndim #2A((1 2) (3 4)))` | `2` (the number of dimensions; 0 for a number) |
| `linalg:size` | `(linalg:size (linalg:eye 3))` | `9` (the total element count) |
| `linalg:reshape` | `(linalg:reshape (linalg:arange 6) '(2 3))` | `#d((0.0 1.0 2.0) (3.0 4.0 5.0))` (row-major; one extent may be `-1` and is inferred) |
| `linalg:flatten` | `(linalg:flatten (linalg:eye 2))` | `#d(1.0 0.0 0.0 1.0)` |
| `linalg:transpose` | `(linalg:transpose #2A((1 2 3) (4 5 6)))` | `#d((1.0 4.0) (2.0 5.0) (3.0 6.0))` (a vector is returned unchanged) |
| `linalg:pad` | `(linalg:pad #(1 2) 1)` | `#d(0.0 1.0 2.0 0.0)` (constant-0 padding; a list gives per-axis `(before after)` pairs) |
| `linalg:expand-dims` | `(linalg:expand-dims #(1 2 3) 0)` | `#d((1.0 2.0 3.0))` (a new extent-1 axis; numpy's `expand_dims` / torch's `unsqueeze`) |
| `linalg:squeeze` | `(linalg:squeeze #2A((1 2 3)))` | `#d(1.0 2.0 3.0)` (drops extent-1 axes; `:axis` picks which) |
| `linalg:concatenate` | `(linalg:concatenate (list #(1 2) #(3)))` | `#d(1.0 2.0 3.0)` (join a LIST of arrays along an existing `:axis`) |
| `linalg:stack` | `(linalg:stack (list #(1 2) #(3 4)))` | `#d((1.0 2.0) (3.0 4.0))` (join along a NEW `:axis`) |
| `linalg:slice` | `(linalg:slice #(0 1 2 3 4 5) '((nil nil 2)))` | `#d(0.0 2.0 4.0)` (basic numpy slicing; one `nil` / `(start end [step])` spec per axis) |
| `linalg:triu` | `(linalg:triu (linalg:ones '(3 3)) :k 1)` | `#d((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0))` (upper triangle; the causal mask) |
| `linalg:tril` | `(linalg:tril #2A((1 2) (3 4)))` | `#d((1.0 0.0) (3.0 4.0))` (lower triangle) |
| `linalg:add` | `(linalg:add #(1 2 3) 10)` | `#d(11.0 12.0 13.0)` (elementwise; a scalar operand broadcasts) |
| `linalg:sub` | `(linalg:sub #(5 5) 1)` | `#d(4.0 4.0)` |
| `linalg:mul` | `(linalg:mul m1 m2)` | The Hadamard (elementwise) product -- not the matrix product |
| `linalg:div` | `(linalg:div #(1 2 3) 2)` | `#d(0.5 1.0 1.5)` (a packed double-float array) |
| `linalg:+` | `(linalg:+ #(1 2) #(3 4) #(10 10))` | `#d(14.0 16.0)` (n-ary `add`; the CL operator spelling) |
| `linalg:-` | `(linalg:- #(10 10) 1 2)` | `#d(7.0 7.0)` (n-ary `sub`; one argument negates) |
| `linalg:*` | `(linalg:* #(1 2) #(3 4))` | `#d(3.0 8.0)` (n-ary `mul`, Hadamard -- not the matrix product) |
| `linalg:/` | `(linalg:/ #(1 2 3) 2)` | `#d(0.5 1.0 1.5)` (n-ary `div`; one argument gives the reciprocal) |
| `linalg:emap` | `(linalg:emap (lambda (x) (* x x)) (linalg:arange 4))` | `#d(0.0 1.0 4.0 9.0)` (apply a function to every element) |
| `linalg:exp` | `(linalg:exp (linalg:zeros 3))` | `#d(1.0 1.0 1.0)` (elementwise `e^x`) |
| `linalg:log` | `(linalg:log #(1 1 1))` | `#d(0.0 0.0 0.0)` (elementwise natural log) |
| `linalg:tanh` | `(linalg:tanh (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise hyperbolic tangent) |
| `linalg:sin` | `(linalg:sin (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise sine) |
| `linalg:cos` | `(linalg:cos (linalg:zeros 3))` | `#d(1.0 1.0 1.0)` (elementwise cosine) |
| `linalg:tan` | `(linalg:tan (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise tangent) |
| `linalg:asin` | `(linalg:asin (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise arc sine) |
| `linalg:acos` | `(linalg:acos (linalg:ones 3))` | `#d(0.0 0.0 0.0)` (elementwise arc cosine) |
| `linalg:atan` | `(linalg:atan (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise arc tangent) |
| `linalg:sinh` | `(linalg:sinh (linalg:zeros 3))` | `#d(0.0 0.0 0.0)` (elementwise hyperbolic sine) |
| `linalg:cosh` | `(linalg:cosh (linalg:zeros 3))` | `#d(1.0 1.0 1.0)` (elementwise hyperbolic cosine) |
| `linalg:sqrt` | `(linalg:sqrt #(4 9 16))` | `#d(2.0 3.0 4.0)` (elementwise square root) |
| `linalg:abs` | `(linalg:abs #(-3 2 -1))` | `#d(3.0 2.0 1.0)` (elementwise absolute value) |
| `linalg:square` | `(linalg:square #(1 2 3))` | `#d(1.0 4.0 9.0)` (elementwise `x * x`) |
| `linalg:negative` | `(linalg:negative #(1 -2 3))` | `#d(-1.0 2.0 -3.0)` (elementwise negation) |
| `linalg:sign` | `(linalg:sign #(-5 0 7))` | `#d(-1.0 0.0 1.0)` (elementwise sign) |
| `linalg:reciprocal` | `(linalg:reciprocal #(2 4 8))` | `#d(0.5 0.25 0.125)` (elementwise `1 / x`, in float) |
| `linalg:power` | `(linalg:power #(1 2 3) 2)` | `#d(1.0 4.0 9.0)` (elementwise `a ** b`; either operand may be a scalar) |
| `linalg:maximum` | `(linalg:maximum #(1 5 3) #(4 2 3))` | `#d(4.0 5.0 3.0)` (elementwise larger; either operand may be a scalar) |
| `linalg:minimum` | `(linalg:minimum #(1 5 3) 4)` | `#d(1.0 4.0 3.0)` (elementwise smaller; either operand may be a scalar) |
| `linalg:clip` | `(linalg:clip #(-2 0 3) -1.0 1.0)` | `#d(-1.0 0.0 1.0)` (elementwise `min(max(x, lo), hi)`) |
| `linalg:relu` | `(linalg:relu #(-2 0 3))` | `#d(0.0 0.0 3.0)` (elementwise `max(x, 0.0)`) |
| `linalg:erf` | `(linalg:erf #(0 1))` | `#d(0.0 0.842700792949715)` (elementwise Gauss error function) |
| `linalg:softmax` | `(linalg:softmax #(1 1 1 1))` | `#d(0.25 0.25 0.25 0.25)` (max-subtracted softmax; `:axis` normalizes per slice) |
| `linalg:log-softmax` | `(linalg:log-softmax #(0 0))` | `#d(-0.6931471805599453 -0.6931471805599453)` (the stable log of `softmax`) |
| `linalg:dot` | `(linalg:dot v1 v2)` | numpy-style dispatch: vec.vec scalar, mat.vec / vec.mat vector, mat.mat matrix product |
| `linalg:matmul` | `(linalg:matmul #2A((1 2) (3 4)) #2A((5 6) (7 8)))` | `#d((19.0 22.0) (43.0 50.0))` (the matrix product; rank >= 3 stacks on the last two axes) |
| `linalg:outer` | `(linalg:outer #(1 2) #(3 4 5))` | `#d((3.0 4.0 5.0) (6.0 8.0 10.0))` (the outer product) |
| `linalg:cross` | `(linalg:cross #(1 0 0) #(0 1 0))` | `#d(0.0 0.0 1.0)` (the 3-D cross product; length-2 vectors answer the implied scalar z) |
| `linalg:sum` | `(linalg:sum #2A((1 2) (3 4)))` | `10` (a reduction follows the element type; `:axis` / `:keepdims` keywords) |
| `linalg:mean` | `(linalg:mean #(1 2 3 4))` | `5/2` (a reduction follows the element type; `:axis` / `:keepdims` keywords) |
| `linalg:var` | `(linalg:var #(1 2 3 4))` | `1.25` (variance; `:axis` / `:keepdims` / `:ddof` keywords) |
| `linalg:std` | `(linalg:std #(2 4 4 4 5 5 7 9))` | `2.0` (the square root of `linalg:var`, same keywords) |
| `linalg:amax` | `(linalg:amax #2A((1 9) (3 4)))` | `9` (the largest element; `:axis` / `:keepdims` keywords) |
| `linalg:amin` | `(linalg:amin #(5 2 8))` | `2` (the smallest element; `:axis` / `:keepdims` keywords) |
| `linalg:argmax` | `(linalg:argmax #(1 9 3))` | `1` (first index on ties; `:axis` gives per-slice indices) |
| `linalg:argmin` | `(linalg:argmin #(5 2 8))` | `1` (first index on ties; `:axis` gives per-slice indices) |
| `linalg:norm` | `(linalg:norm #(3 4))` | `5.0` (the Euclidean / Frobenius norm) |
| `linalg:trace` | `(linalg:trace #2A((1 2) (3 4)))` | `5` (square matrices only) |
| `linalg:diff` | `(linalg:diff #(1 2 4 7 0))` | `#d(1.0 2.0 3.0 -7.0)` (the `:n`-th discrete difference along `:axis`; defaults 1 and the last axis) |
| `linalg:gradient` | `(linalg:gradient #(0 1 4 9 16))` | `#d(1.0 2.0 4.0 6.0 7.0)` (central differences, same length as the input; optional scalar spacing or coordinate vector) |
| `linalg:det` | `(linalg:det #2A((1 2) (3 4)))` | `-2.0` (floating point; a singular matrix may give a small epsilon) |
| `linalg:inv` | `(linalg:inv #2A((4 0) (2 4)))` | `#d((0.25 0.0) (-0.125 0.25))` (signals an error for a singular matrix) |
| `linalg:solve` | `(linalg:solve a b)` | The solution of `a . x = b` (`b` a vector or matrix) |
| `linalg:array-equal` | `(linalg:array-equal (linalg:eye 2) #2A((1 0) (0 1)))` | `t` (same shape and numerically equal elements; arrays themselves are only `eq`-comparable) |
| `linalg:equal` | `(linalg:equal #(1 5 3) #(2 5 1))` | `#d(0.0 1.0 0.0)` (elementwise `=` as a 0/1 mask; broadcasts) |
| `linalg:greater` | `(linalg:greater #(1 5 3) 2)` | `#d(0.0 1.0 1.0)` (elementwise `>` as a 0/1 mask) |
| `linalg:greater-equal` | `(linalg:greater-equal #(1 5 3) #(1 6 2))` | `#d(1.0 0.0 1.0)` (elementwise `>=` as a 0/1 mask) |
| `linalg:less` | `(linalg:less #(1 5 3) 3)` | `#d(1.0 0.0 0.0)` (elementwise `<` as a 0/1 mask) |
| `linalg:less-equal` | `(linalg:less-equal #(1 5 3) 3)` | `#d(1.0 0.0 1.0)` (elementwise `<=` as a 0/1 mask) |
| `linalg:where` | `(linalg:where #(1 0 1) 10 20)` | `#d(10.0 20.0 10.0)` (elementwise select on a non-zero mask; broadcasts) |
| `linalg:take-rows` | `(linalg:take-rows #2A((1 2 3) (4 5 6) (7 8 9)) #(2 0))` | `#d((7.0 8.0 9.0) (1.0 2.0 3.0))` (the axis-0 slices selected by an index vector) |
| `linalg:row` | `(linalg:row #2A((1 2 3) (4 5 6) (7 8 9)) 1)` | `#d(4.0 5.0 6.0)` (one axis-0 slice, axis dropped -- numpy's `x[i]`) |
| `linalg:gather` | `(linalg:gather #2A((10 11 12) (20 21 22)) #(2 0))` | `#d(12.0 20.0)` (the per-row elements `a[i, idx[i]]` of a matrix) |
| `linalg:one-hot` | `(linalg:one-hot #(1 0 2) 3)` | `#d((0.0 1.0 0.0) (1.0 0.0 0.0) (0.0 0.0 1.0))` (row i holds 1.0 in column `indices[i]`) |
| `linalg:seed` | `(linalg:seed 42)` | `42` (resets the shared random generator; seeded draws are identical on every backend) |
| `linalg:rand` | `(linalg:rand 4)` | Uniform `[0, 1)` draws with the given shape |
| `linalg:randn` | `(linalg:randn '(2 2))` | Standard-normal draws (Irwin-Hall; tails clip at +/- 6 sigma) |
| `linalg:uniform` | `(linalg:uniform 10 20 4)` | Uniform draws in `[lo, hi)` with the given shape |
| `linalg:choice` | `(linalg:choice 60000 4)` | 4 uniform indices in `[0, 60000)`, with replacement (a packed double vector) |
| `linalg:permutation` | `(linalg:permutation 10)` | The integers `0..9` in a Fisher-Yates shuffle (a packed double vector) |

## torch Package Functions

The `torch` package is the PyTorch-style differentiable layer over `linalg`
(see the [Neural Networks guide](../guides/neural-networks.md)): a tensor that
records how it was computed, and a `torch:backward` that walks that history to
fill in gradients. It is **not part of Common Lisp**; reference its functions
with the `torch:` qualifier (the package does not use `cl`). Every operation
accepts tensors, numbers, arrays or lists as operands, computes through the
`linalg` kernels (so `--simd` accelerates torch programs for free), and a
tensor prints as `#<TENSOR data>` -- read the values back with `torch:data` /
`torch:item` / `torch:grad`. The middle of the table is the `nn`-style module
layer: a module owns its parameters in a fields plist, composes, and is run with
`torch:forward`. The last part is what turns a model into a training run: the
optimizers, whose `torch:step` updates every parameter in place, and the
batching / padding / mask helpers, which are plain functions rather than a
`Dataset`/`DataLoader` hierarchy. The one macro, `torch:no-grad`, is on the
[Macros page](macros/torch-no-grad.md).

| Function | Example | Result |
|----------|---------|--------|
| `torch:tensor` | `(torch:tensor '(1 2) :requires-grad t)` | a leaf tensor over packed data (`:element-type 'single-float` for `#f`) |
| `torch:tensorp` | `(torch:tensorp x)` | `T` for a tensor, `NIL` otherwise |
| `torch:data` | `(torch:data tn)` | the linalg array (or number, for a scalar tensor) |
| `torch:grad` | `(torch:grad tn)` | the accumulated gradient, or `NIL` before backward |
| `torch:shape` | `(torch:shape tn)` | the dims list; `NIL` for a scalar tensor |
| `torch:item` | `(torch:item tn)` | the number in a one-element tensor |
| `torch:detach` | `(torch:detach tn)` | a leaf sharing the data, cut off from the tape |
| `torch:zero-grad` | `(torch:zero-grad tn)` | clears the gradient slot; returns the tensor |
| `torch:requires-grad-p` | `(torch:requires-grad-p tn)` | whether the tensor participates in autograd |
| `torch:backward` | `(torch:backward loss)` | reverse-mode autograd from a scalar tensor (accumulates into `torch:grad`) |
| `torch:add` | `(torch:add a b)` | differentiable elementwise `+` with broadcasting |
| `torch:sub` | `(torch:sub a b)` | differentiable elementwise `-` |
| `torch:mul` | `(torch:mul a b)` | differentiable elementwise (Hadamard) `*` |
| `torch:div` | `(torch:div a b)` | differentiable elementwise `/` |
| `torch:neg` | `(torch:neg a)` | differentiable negation |
| `torch:power` | `(torch:power a 2)` | differentiable elementwise `a ** b` |
| `torch:exp` | `(torch:exp a)` | differentiable `e^x` |
| `torch:log` | `(torch:log a)` | differentiable natural log |
| `torch:sqrt` | `(torch:sqrt a)` | differentiable square root |
| `torch:tanh` | `(torch:tanh a)` | differentiable hyperbolic tangent |
| `torch:relu` | `(torch:relu a)` | differentiable `max(x, 0.0)` |
| `torch:erf` | `(torch:erf a)` | differentiable Gauss error function |
| `torch:gelu` | `(torch:gelu a)` | differentiable GELU (`:approximate :none` / `:tanh`) |
| `torch:matmul` | `(torch:matmul a b)` | differentiable matrix product (batched at rank >= 3) |
| `torch:sum` | `(torch:sum a :axis 0)` | differentiable sum (whole tensor or along an axis) |
| `torch:mean` | `(torch:mean a)` | differentiable mean |
| `torch:var` | `(torch:var a :ddof 1)` | differentiable variance (`(n - ddof)` divisor) |
| `torch:std` | `(torch:std a)` | differentiable standard deviation |
| `torch:amax` | `(torch:amax a :axis 0)` | differentiable maximum (gradient split among ties) |
| `torch:argmax` | `(torch:argmax a)` | index of the largest element (non-differentiable, raw value) |
| `torch:topk` | `(torch:topk a 5)` | the `k` largest values along an axis, largest first (`:indices t` for their positions) |
| `torch:multinomial` | `(torch:multinomial probs)` | indices drawn per row from the seeded generator (`:num-samples`, `:replacement`) |
| `torch:softmax` | `(torch:softmax a :axis 1)` | differentiable max-subtracted softmax |
| `torch:log-softmax` | `(torch:log-softmax a :axis 1)` | differentiable log-softmax (cross-entropy half) |
| `torch:masked-fill` | `(torch:masked-fill a mask v)` | differentiable fill of `v` where the mask is non-zero |
| `torch:gather` | `(torch:gather a idx)` | differentiable per-row element pick of a matrix |
| `torch:index-select` | `(torch:index-select a idx)` | differentiable row selection (the embedding lookup; repeats accumulate) |
| `torch:reshape` | `(torch:reshape a '(2 3))` | differentiable row-major reshape |
| `torch:view` | `(torch:view a '(2 3))` | `torch:reshape` under PyTorch's other name |
| `torch:transpose` | `(torch:transpose a '(1 0 2))` | differentiable transpose / axes permutation |
| `torch:unsqueeze` | `(torch:unsqueeze a 0)` | differentiable extent-1 axis insertion |
| `torch:squeeze` | `(torch:squeeze a)` | differentiable extent-1 axis removal |
| `torch:cat` | `(torch:cat (list a b) :axis 1)` | differentiable concatenation along an existing axis |
| `torch:stack` | `(torch:stack (list a b))` | differentiable join along a new axis |
| `torch:slice` | `(torch:slice a '(nil (0 2)))` | differentiable numpy basic slicing |
| `torch:set-data` | `(torch:set-data tn v)` | replaces a tensor's data in place (the parameter update) |
| `torch:module` | `(torch:module :k fields fn)` | a user layer: a kind, a fields plist and a forward function |
| `torch:modulep` | `(torch:modulep x)` | `T` for a module, `NIL` otherwise |
| `torch:module-kind` | `(torch:module-kind m)` | the module's kind keyword |
| `torch:field` | `(torch:field m :weight)` | the value of a module's named field (signals when absent) |
| `torch:fields` | `(torch:fields m)` | the whole fields plist, as a fresh list -- the module walk |
| `torch:set-field` | `(torch:set-field m :weight p)` | sets a module's named field; returns the module |
| `torch:forward` | `(torch:forward m x)` | runs a module's (or a plain function's) forward pass |
| `torch:parameter` | `(torch:parameter '(1.0))` | a leaf tensor with `requires-grad` -- a trainable parameter |
| `torch:parameters` | `(torch:parameters m)` | every parameter reachable from a module, deduplicated |
| `torch:train` | `(torch:train m)` | puts the module and its submodules into training mode |
| `torch:eval` | `(torch:eval m)` | puts the module and its submodules into evaluation mode |
| `torch:training-p` | `(torch:training-p m)` | whether the module is in training mode |
| `torch:linear` | `(torch:linear 4 8)` | a fully connected layer (`:weight`, `:bias`) |
| `torch:embedding` | `(torch:embedding 100 8)` | an embedding table (`:weight`), indices of any shape |
| `torch:sequential` | `(torch:sequential a #'torch:relu b)` | a chain of layers and/or plain functions |
| `torch:layer-norm` | `(torch:layer-norm 8)` | layer normalization over the last axis (`ddof` 0) |
| `torch:dropout` | `(torch:dropout 0.1)` | inverted dropout; the identity in evaluation mode |
| `torch:mse-loss` | `(torch:mse-loss y target)` | mean squared error (`:reduction :mean` / `:sum` / `:none`) |
| `torch:cross-entropy-loss` | `(torch:cross-entropy-loss logits target)` | cross entropy over logits; target is class indices (`:ignore-index` skips padding) or a probability distribution |
| `torch:optimizer` | `(torch:optimizer :k ps fields fn)` | a user optimizer: a kind, parameters, a fields plist and a step function |
| `torch:optimizerp` | `(torch:optimizerp x)` | `T` for an optimizer, `NIL` otherwise |
| `torch:optimizer-kind` | `(torch:optimizer-kind o)` | the optimizer's kind keyword |
| `torch:optimizer-params` | `(torch:optimizer-params o)` | the parameter tensors the optimizer updates |
| `torch:step` | `(torch:step o)` | applies the update rule to every parameter (in place, off the tape) |
| `torch:step-count` | `(torch:step-count o)` | how many times `torch:step` has run (Adam's `t`) |
| `torch:sgd` | `(torch:sgd model :lr 0.1)` | SGD, optionally with `:momentum` / `:weight-decay` |
| `torch:adam` | `(torch:adam model :lr 0.001)` | Adam (`:betas`, `:eps`, `:weight-decay`), bias-corrected from the first step |
| `torch:adamw` | `(torch:adamw model :lr 0.001)` | AdamW: the same rule with DECOUPLED `:weight-decay` (default `0.01`) |
| `torch:clip-grad-norm` | `(torch:clip-grad-norm model 1.0)` | scales every gradient in place when their total L2 norm exceeds the bound; returns that norm |
| `torch:pad-sequence` | `(torch:pad-sequence seqs)` | variable-length sequences as one padded batch-first tensor |
| `torch:shuffled-batches` | `(torch:shuffled-batches n 32)` | mini-batches from the seeded generator (`:shuffle`, `:drop-last`) |
| `torch:padding-mask` | `(torch:padding-mask tokens)` | `(batch 1 length)` mask of the padding positions (a raw array) |
| `torch:subsequent-mask` | `(torch:subsequent-mask 8)` | `(1 n n)` causal mask, `1.0` above the diagonal (a raw array) |

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

## ffi Package Functions

The `ffi` package binds plain C through the JVM's foreign function API — no
JNI, no bundled native library, no reflection, which is why it works in the
**native binary** as well as under `java -jar` and in a compiled `.class` /
`.jar`. Neither WASM backend has a foreign function API, so a program reaching
it refuses to compile there. It is **not part of Common Lisp**; reference its
functions with the `ffi:` qualifier. These are primitives, and they exist for
one consumer: a binding should be written against `cffi:defcfun`, not against
these verbs — see the [C libraries guide](../guides/cffi.md).

| Function | Example | Result |
|----------|---------|--------|
| `ffi:open` | `(ffi:open "libsqlite3.so.0")` | a library handle, an integer (no argument = the process) |
| `ffi:symbol` | `(ffi:symbol (ffi:open) "strlen")` | the symbol's address, a pointer (`nil` when absent) |
| `ffi:call` | `(ffi:call addr :long '(:string) "hello")` | the C function's answer, marshalled by the return type |
| `ffi:callback` | `(ffi:callback fn :int '(:int :int))` | a C function pointer that calls the Lisp function |
| `ffi:alloc` | `(ffi:alloc 8)` | a pointer to that many bytes (`malloc`) |
| `ffi:free` | `(ffi:free p)` | `nil`, the block released |
| `ffi:peek` | `(ffi:peek p :int 8)` | the value of that type at the address plus offset |
| `ffi:poke` | `(ffi:poke p :double 1.5)` | the value, written at the address |
| `ffi:size` | `(ffi:size :pointer)` | the type's size in bytes |
| `ffi:align` | `(ffi:align :double)` | the type's alignment in bytes |
| `ffi:pointerp` | `(ffi:pointerp 42)` | `t` for a foreign pointer |
| `ffi:address` | `(ffi:address 4096)` | the pointer for an integer, the integer for a pointer |
| `ffi:errno` | `(ffi:errno)` | the `errno` the calling thread's last call left |

## objc Package Functions

The `objc` package binds the Objective-C runtime and AppKit through the foreign
function API — no reflection, so unlike `java:` it works in the **native
binary** as well as under `java -jar`. It is **macOS, interpreter only** (a
compiled `.class` or `.wasm` refuses it) and **not part of Common Lisp**;
reference its functions with the `objc:` qualifier. Each name below links to its
own page; the [macOS GUI guide](../guides/objc-appkit.md) covers marshalling,
threads, ownership and the native binary's shape table.

| Function | Example | Result |
|----------|---------|--------|
| `objc:class` | `(objc:class "NSWindow")` | a class (`#<objc NSWindow>`) |
| `objc:send` | `(objc:send (objc:string "hi") "length")` | the result, marshalled by the selector's declared type |
| `objc:define-class` | `(objc:define-class "Target" "NSObject" (list (list "invoke:" fn)))` | a class whose methods are Lisp functions |
| `objc:on-main` | `(objc:on-main (lambda () ...))` | the function's value, computed on the main thread |
| `objc:string` | `(objc:string "hi")` | an `NSString` |
| `objc:data` | `(objc:data buffer)` | an `NSMutableData` holding the buffer's bytes |
| `objc:bytes` | `(objc:bytes data)` | an `NSData`'s bytes as a packed `(unsigned-byte 8)` vector |
| `objc:address` | `(objc:address obj)` | the object's address, an integer |
| `objc:objectp` | `(objc:objectp x)` | `t` for an Objective-C object |

## appkit Package Functions

The `appkit` package is a Cocoa widget layer written in rontolisp over `objc`,
loaded on first use like `linalg`; it needs a macOS display, and runs on the
interpreter and in a program compiled to a `.class` or a `.jar` (never a
`.wasm`). It is **not part of Common Lisp**; reference its functions
with the `appkit:` qualifier. See the [macOS GUI guide](../guides/objc-appkit.md).

| Function | Example | Result |
|----------|---------|--------|
| `appkit:window` | `(appkit:window "title" :width 480 :height 300)` | a shown, centered `NSWindow` |
| `appkit:label` | `(appkit:label win "text" :x 20 :y 120 :width 380)` | an `NSTextField` label, its string centred in the rectangle |
| `appkit:button` | `(appkit:button win "Click" :x 20 :y 40 :on-click (lambda () ...))` | an `NSButton` whose action runs the closure |
| `appkit:panel` | `(appkit:panel win :x 20 :y 20 :width 34 :height 34 :fill c :radius 7)` | a filled, rounded `NSBox` in the window |
| `appkit:color` | `(appkit:color 90 200 250)` | an `NSColor` from 0-255 components |
| `appkit:font` | `(appkit:font 19 :bold t)` | the system `NSFont` at that size |
| `appkit:set-text` | `(appkit:set-text label "new text")` | the text, now shown |
| `appkit:set-color` | `(appkit:set-color tile (appkit:color 214 69 65))` | the colour: a panel's fill, any other control's text colour |
| `appkit:text` | `(appkit:text label)` | the control's text as a string |
| `appkit:on-click` | `(appkit:on-click tile (lambda (button) ...))` | the view; the closure runs on a click (1 left, 3 right) |
| `appkit:click` | `(appkit:click button)` | `nil`; the action ran as a click would |
| `appkit:timer` | `(appkit:timer 0.12 (lambda () ...))` | a repeating `NSTimer`; a `nil` answer stops it |
| `appkit:menu` | `(appkit:menu (list (list "Quit" #'appkit:quit "q")))` | an `NSMenu` whose items are Lisp closures; `:separator` is a dividing line |
| `appkit:status-item` | `(appkit:status-item "λ" :menu m :dock nil)` | an `NSStatusItem` in the system menu bar; `:dock nil` hides the Dock icon |
| `appkit:quit` | `(appkit:quit)` | never answers: the application ends, as Cmd-Q ends it |
| `appkit:close` | `(appkit:close win)` | `nil`; the window is closed (hidden) |
| `appkit:visible-p` | `(appkit:visible-p win)` | whether the window is on screen |
| `appkit:wait` | `(appkit:wait win)` | `nil`, once the window has been closed; with no window, until the application ends |

## asdf Package Functions

The `asdf` package is a limited, API-compatible subset of ASDF for loading
multi-file systems from `.asd` definitions. It is **not part of Common Lisp**;
reference its symbols with the `asdf:` qualifier. Each name below links to its
own page; the [Systems guide](../guides/asdf-systems.md) gives a full project
layout and the search-path details.

| Function | Example | Result |
|----------|---------|--------|
| `asdf:defsystem` | `(asdf:defsystem :my-lib :components ((:file "main")))` | define a system (name, `:depends-on`, `:serial`, `:components`) for a later `load-system` |
| `asdf:load-system` | `(asdf:load-system :my-lib)` | load a system: its dependency systems first, then its component files in order (a literal, top-level form on the compile path) |
| `asdf:test-system` | `(asdf:test-system "my-app")` | load the system, follow its `:in-order-to` test-op chain, then run its recorded `:perform (test-op ...)` body — the standard `.asd` test entry point |
| `asdf:find-system` | `(asdf:find-system :my-lib nil)` | the system's metaobject, a real `asdf:system` CLOS instance memoized per name (`eq` across calls); nil for an unknown name when `error-p` is nil |
| `asdf:registered-systems` | `(asdf:registered-systems)` | the downcased names of every registered system, in registration order |
| `asdf:system-relative-pathname` | `(asdf:system-relative-pathname :my-lib "data/tlds.dat")` | the namestring of a path resolved against the system's source directory (folded to a literal on the compile path) |
| `asdf:component-pathname` | `(asdf:component-pathname (asdf:find-system :my-lib))` | a system's source directory with a trailing `/`, or a source-file child's resolved path; accepts the metaobject or a name designator |
| `asdf:component-name` | `(asdf:component-name (asdf:find-system :my-lib))` | reader: the component's downcase-canonical name |
| `asdf:component-version` | `(asdf:component-version (asdf:find-system :my-lib))` | reader: the declared `:version` string, or nil when the `.asd` declared none as a plain string (a computed spelling is never evaluated) |
| `asdf:component-children` | `(asdf:component-children (asdf:find-system :my-lib))` | reader: a system's component files in load order, one `asdf:cl-source-file` per file |
| `asdf:component-sideway-dependencies` | `(asdf:component-sideway-dependencies (asdf:find-system :my-lib))` | reader: the system's `:depends-on` names (package-inferred sub-system names included) |
| `asdf:component-parent` | `(asdf:component-parent child)` | reader: the parent component — the system for a source file, nil for a system |
| `asdf:component-system` | `(asdf:component-system child)` | the system a component belongs to (walks `component-parent` up) |

## uiop Package Functions

The `uiop` package is ASDF's portability layer — the spelling
implementation-independent libraries already use for the operations Common Lisp
never standardized. It is **not part of Common Lisp**; reference its symbols
with the `uiop:` qualifier, never unqualified. It is 15 sub-packages and 429
exports, so it has a page of its own: **[The uiop Package](uiop.md)** — the
sub-package layout, what is implemented, and what an unimplemented member
signals.

## ql and ql-dist Package Functions

The `ql` package is a limited, API-compatible subset of Quicklisp:
`quickload` downloads a system from the real Quicklisp distribution into a local
cache and then loads it through the `asdf` subset (`quicklisp` is a built-in
nickname). `ql-dist` holds the distribution machinery, of which the one member a
program writes is `install-dist`: it adds another Quicklisp-format distribution —
[Ultralisp](https://ultralisp.org/), or any distinfo URL — to the dists
`quickload` searches, in installation order. Neither package is **part of Common
Lisp**; reference their symbols with the `ql:` / `ql-dist:` qualifier. The names
below link to their own pages; the [Systems
guide](../guides/asdf-systems.md#downloading-with-quickload) covers the cache
layout and limitations.

| Function | Example | Result |
|----------|---------|--------|
| `ql:quickload` | `(ql:quickload "split-sequence")` | download a system (and its dependencies) from the installed dists, cache it under `~/.rontolisp/<dist>`, and load it; returns the list of loaded system names |
| `ql-dist:install-dist` | `(ql-dist:install-dist "ultralisp")` | add a Quicklisp-format distribution (a known name or a distinfo URL) to the dists `quickload` searches; returns the dist name |
| `ql:update-dist` | `(ql:update-dist "ultralisp")` | drop a dist's cached indexes so the next `quickload` sees its newest releases; returns the dist name |

## usocket Package Functions

The `usocket` package is a compatibility shim over the `rontolisp:tcp-*`
built-ins reproducing the [usocket](https://github.com/usocket/usocket) API,
so existing Common Lisp networking code (such as Postmodern's cl-postgres
socket layer) runs with fewer changes. It is **not part of Common Lisp**;
reference its symbols with the `usocket:` qualifier. A socket IS its stream
handle here, so `socket-stream` is the identity function and the standard
stream functions work on sockets directly. The package is loaded on first use
and is also the built-in ASDF system `"usocket"` (satisfying
`asdf:load-system`, `ql:quickload` and `:depends-on ("usocket")` without a
download). TCP only -- UDP (`socket-send` / `socket-receive`),
`wait-for-input`, `socket-server` and the condition hierarchy
(`usocket:socket-error` under `handler-case`) are not supported. The variables
`usocket:*wildcard-host*` (`"0.0.0.0"`) and `usocket:*auto-port*` (`0`) are
provided. See the
[TCP Sockets guide](../guides/tcp-sockets.md#the-usocket-compatible-shim) for
a worked overview and the full limitation list.

| Function | Example | Result |
|----------|---------|--------|
| `usocket:socket-connect` | `(usocket:socket-connect "localhost" 5432 :element-type '(unsigned-byte 8))` | open a blocking TCP connection; `:protocol :datagram` signals, the other options are accepted and ignored |
| `usocket:socket-listen` | `(usocket:socket-listen usocket:*wildcard-host* usocket:*auto-port*)` | bind a listening TCP socket (host first, usocket-style) |
| `usocket:socket-accept` | `(usocket:socket-accept listener)` | wait for a client connection (blocking) |
| `usocket:socket-stream` | `(read-line (usocket:socket-stream sock))` | the stream of a socket (the identity function in this shim) |
| `usocket:socket-close` | `(usocket:socket-close sock)` | close a socket or listener |
| `usocket:get-local-port` | `(usocket:get-local-port listener)` | the locally bound port (read an ephemeral port back) |
| `usocket:get-local-address` | `(usocket:get-local-address listener)` | the locally bound IP address, as a string |
| `usocket:get-peer-address` | `(usocket:get-peer-address sock)` | the remote IP address of a connected socket |
| `usocket:get-peer-port` | `(usocket:get-peer-port sock)` | the remote port of a connected socket |
| `usocket:get-local-name` | `(usocket:get-local-name sock)` | local address and port as `(values address port)` |
| `usocket:get-peer-name` | `(usocket:get-peer-name sock)` | remote address and port as `(values address port)` |
| `usocket:host-to-hostname` | `(usocket:host-to-hostname #(192 168 0 1))` | a host designator (string, vector quad, host-byte-order integer or `nil`) as a hostname/dotted-quad string |
| `usocket:get-host-by-name` | `(usocket:get-host-by-name "example.com")` | lite: renders the designator through `host-to-hostname` instead of resolving it — no backend has a name-resolution primitive, and the socket call the address reaches resolves it for real |

The `with-*` convenience macros (`usocket:with-client-socket` /
`with-connected-socket` / `with-server-socket` / `with-socket-listener`) are
listed on the [macros page](macros.md) and described on their
[reference page](macros/usocket-with-macros.md); on the interpreter and the
JVM they close the socket on every exit (they expand over
[`unwind-protect`](special-forms/unwind-protect.md)), on the WASM component
backend on normal exit only.
