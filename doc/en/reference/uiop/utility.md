# uiop/utility

`uiop/utility` is the layer the rest of uiop is written in: string, list, plist,
hash-table, timestamp and condition helpers that need nothing from the operating
system. **All 68 exports are implemented**, and because none of them touches the
file system, a subprocess or the network, every one runs on all four backends —
the interpreter, the JVM, and both WASM outputs.

Every name is reachable through either spelling: `uiop:strcat` and
`uiop/utility:strcat` are the same function
([The uiop Package](../uiop.md#sub-packages)).

## Strings

| Function | What it does |
|----------|--------------|
| `uiop:strcat` | concatenate string designators, where `nil` is the empty string and a character is a string of length one |
| `uiop:reduce/strcat` | `strcat` over a LIST, with `:key`, `:start` and `:end` as `reduce` takes them |
| `uiop:string-prefix-p` | does the string begin with the prefix? |
| `uiop:string-suffix-p` | does the string end with the suffix? |
| `uiop:string-enclosed-p` | both at once |
| `uiop:stripln` | strip a trailing CR, LF or CRLF; two values, the stripped string and the ending removed |
| `uiop:frob-substrings` | replace (or remove) each of several substrings, left to right, never inside an earlier match |
| `uiop:first-char` / `uiop:last-char` | the first / last character of a non-empty string, else `nil` |
| `uiop:split-string` | split on any character of a separator sequence |
| `uiop:emptyp` | true for `nil` and for a zero-length vector or string |
| `uiop:+cr+` / `uiop:+lf+` / `uiop:+crlf+` | the three line endings as strings |
| `uiop:standard-case-symbol-name` | a name designator as a string, upcasing a string one |
| `uiop:find-standard-case-symbol` | that name looked up in a package |

`strcat`'s tolerance is the point of it: an optional piece concatenates without a
test around it.

```lisp
(print (uiop:strcat "a" nil #\b "c"))
(print (uiop:reduce/strcat (list "aa" "bb" "cc") :start 1))
(print (list (uiop:string-prefix-p "ab" "abc")
             (uiop:string-suffix-p "abc" "bc")
             (uiop:string-enclosed-p "a" "abc" "c")))
(print (uiop:frob-substrings "hello world" (list "o") "0"))
```

```
"abc"
"bbcc"
(T T T)
"hell0 w0rld"
```

`stripln` returns what it removed as a second value, so `strcat` of the two
reconstitutes the original line.

```lisp
(multiple-value-bind (line ending) (uiop:stripln (uiop:strcat "hi" uiop:+crlf+))
  (print (list line (length ending)))
  (print (string= (uiop:strcat line ending) (uiop:strcat "hi" uiop:+crlf+))))
```

```
("hi" 2)
T
```

## Lists, plists and hash tables

| Function | What it does |
|----------|--------------|
| `uiop:ensure-list` | wrap a non-list in a one-element list |
| `uiop:length=n-p` | is the list exactly `n` long? — without walking past `n` |
| `uiop:appendf` | `(appendf place list...)`, i.e. `(setf place (append place list...))` |
| `uiop:remove-plist-key` / `uiop:remove-plist-keys` | a plist without the given key(s) — keyword-argument cleanup |
| `uiop:ensure-gethash` | the entry, computing and storing a default on a miss; a second value says whether it was already there |
| `uiop:list-to-hash-set` | a list as an `equal` hash set |
| `uiop:lexicographic<` / `uiop:lexicographic<=` | compare two lists element by element with a supplied `element<` |

```lisp
(print (uiop:remove-plist-keys (list :b :c) (list :a 1 :b 2 :c 3)))
(print (let ((l (list 1))) (uiop:appendf l (list 2 3)) l))
(print (let ((h (make-hash-table :test 'equal)))
         (list (multiple-value-list (uiop:ensure-gethash "k" h (constantly 5)))
               (multiple-value-list (uiop:ensure-gethash "k" h (constantly 6))))))
```

```
(:A 1)
(1 2 3)
((5 NIL) (5 T))
```

## Timestamps

A timestamp is a real number or a boolean, where `t` is minus infinity and `nil`
is plus infinity — so a missing file is "infinitely old" and an unknown one
"infinitely new", which is how ASDF orders a build.

| Function | What it does |
|----------|--------------|
| `uiop:timestamp<` / `uiop:timestamp<=` | compare two timestamps |
| `uiop:timestamps<` / `uiop:timestamp*<` | is a list (or an argument list) strictly increasing? |
| `uiop:earlier-timestamp` / `uiop:later-timestamp` | the smaller / larger of two |
| `uiop:timestamps-earliest` / `uiop:timestamps-latest` | over a list |
| `uiop:earliest-timestamp` / `uiop:latest-timestamp` | over an argument list |
| `uiop:latest-timestamp-f` | `(latest-timestamp-f place timestamp...)`, accumulating into the place |

```lisp
(print (list (uiop:timestamp< 1 2) (uiop:timestamp< t 3) (uiop:timestamp< 3 nil)))
(print (list (uiop:earliest-timestamp 3 1 2) (uiop:latest-timestamp 3 1 2)))
(print (let ((newest 1)) (uiop:latest-timestamp-f newest 5 3) newest))
```

```
(T T T)
(1 3)
5
```

`timestamps<` chains from `nil` = plus infinity, so a non-empty list is never
"increasing" — upstream's own answer, kept rather than corrected.

## Function designators

`uiop:ensure-function` coerces a *designator* into a function: a function is
itself, a constant (boolean, keyword, character, number, pathname) becomes
`(constantly it)`, a hash table becomes its lookup, a symbol its `fdefinition`, a
cons a partially applied call (or an evaluated `lambda` form), and a string is
read and evaluated as a function name.

| Function | What it does |
|----------|--------------|
| `uiop:ensure-function` | the coercion above |
| `uiop:call-function` | `(apply (ensure-function spec) args)` |
| `uiop:call-functions` | `call-function` over a list, in order |
| `uiop:access-at` | apply a chain of accessors: an integer is `elt`, a keyword is `getf`, `nil` is identity, a symbol or function is called, a cons is `ensure-function` |
| `uiop:access-at-count` | how many sub-objects an `access-at` specifier reads |
| `uiop:register-hook-function` | push a hook onto a variable — see [What is missing](#what-is-missing) |

```lisp
(print (funcall (uiop:ensure-function 'car) (list 9 8)))
(print (uiop:call-function (list '+ 1) 2))
(print (uiop:access-at (list :a (list 10 20)) (list :a 1)))
```

```
9
3
20
```

## Conditions

| Name | What it does |
|------|--------------|
| `uiop:not-implemented-error` | the condition, and the function that signals it, naming an operation this implementation does not have |
| `uiop:parameter-error` | the operation exists but does not accept that parameter combination |
| `uiop:simple-style-warning` | uiop's own style warning — a real `style-warning`, so a handler for the standard type catches it |
| `uiop:style-warn` | signal one, from a format string, a condition type or a condition |
| `uiop:match-condition-p` | does a condition match a pattern? (a type name, a `#(name package)` vector, a predicate, or a `simple-condition` format string) |
| `uiop:match-any-condition-p` | any of several patterns |
| `uiop:call-with-muffled-conditions` | run a thunk with matching conditions muffled |
| `uiop:with-muffled-conditions` | the macro over it |
| `uiop:boolean-to-feature-expression` | `(:and)` or `(:or)` — an always-true / always-false `#+` test |
| `uiop:symbol-test-to-feature-expression` | the same, from "does this package export this name?" |

```lisp
(print (uiop:with-muffled-conditions ('(warning)) (warn "not shown") :muffled))
(print (handler-bind ((style-warning (lambda (c) (muffle-warning c))))
         (uiop:style-warn "deprecated: ~A" 'old-name)
         :warned))
(print (list (uiop:boolean-to-feature-expression t)
             (uiop:symbol-test-to-feature-expression "CAR" :cl)))
```

```
:MUFFLED
:WARNED
((:AND) (:AND))
```

The one deviation worth knowing: a STRING pattern for `match-condition-p` is
compared against `simple-condition-format-control`, which in rontolisp holds the
already-formatted message. A pattern with format directives in it therefore
cannot match; a pattern without them still does.

## Macros

| Macro | What it does |
|-------|--------------|
| [`uiop:if-let`](../macros/uiop-if-let.md) | bind, then take the `then` branch only if every variable is non-nil |
| `uiop:nest` | nest each form inside the previous one's tail — indentation control |
| `uiop:while-collecting` | bind one collector FUNCTION per name; the form answers one list each, in order |
| `uiop:with-upgradability` | upstream wraps every definition in it; here it is `progn` — see below |
| `uiop:with-muffled-conditions` | shorthand for `call-with-muffled-conditions` |
| `uiop:appendf` / `uiop:latest-timestamp-f` | the two `define-modify-macro`s above |
| `uiop:compatfmt` | strip pretty-printer directives a weaker `format` cannot read; rontolisp reads them all, so the string is returned unchanged |
| `uiop:uiop-debug` | load a developer's personal debug file — see [What is missing](#what-is-missing) |
| `uiop:parse-body` | (a function, not a macro) split a body into forms, declarations and a docstring — what a macro-writing library calls |

```lisp
(print (uiop:nest (list 1) (list 2) (list 3)))
(print (multiple-value-list
        (uiop:while-collecting (names numbers)
          (dolist (row (list (list 'a 1) (list 'b 2)))
            (names (first row))
            (numbers (second row))))))
(print (multiple-value-list (uiop:parse-body '("doc" (declare (ignore x)) (+ 1 2))
                                             :documentation t)))
```

```
(1 (2 (3)))
((A B) (1 2))
(((+ 1 2)) ((DECLARE (IGNORE X))) "doc")
```

**`uiop:with-upgradability` expands to `progn`.** Upstream wraps every one of its
definitions in it so that ASDF can redefine itself inside a running image: the
body is evaluated at compile, load and run time and each function is declared
`notinline`. rontolisp has no image to upgrade — a program is compiled once and
run — so `progn` is the whole meaning of it here. This is a deliberate choice,
not a gap: the definitions are established exactly as written, and they stay
top-level definitions on the compile backends.

```lisp
(uiop:with-upgradability ()
  (defun double-it (x) (* x 2))
  (defvar *scale* 5))
(print (list (double-it 3) *scale*))
```

```
(6 5)
```

## Characters: one character type

Upstream's character quartet exists because `base-char` and `character` are
different types on some implementations, so a string's element type has to be
discovered. rontolisp has **one** character type — `(subtypep 'character
'base-char)` is true — and running upstream's own derivation on that gives one
element, index 0, and a false `+non-base-chars-exist-p+`. Everything else
follows: every string is a base string, and the common element type of any group
of strings is `character`.

```lisp
(print (list uiop:+max-character-type-index+
             (uiop:character-type-index #\a)
             uiop:+non-base-chars-exist-p+))
(print (list (uiop:base-string-p "abc")
             (uiop:strings-common-element-type (list "a" #\b))))
```

```
(0 0 NIL)
(T CHARACTER)
```

## What is missing

Two members name what rontolisp does not have, rather than pretending, and both
signal `uiop:not-implemented-error` with the reason:

- **`uiop:register-hook-function`** would push onto a variable named at run time,
  which needs `(setf (symbol-value var) ...)` — not a place on any backend.
- **`uiop:load-uiop-debug-utility`** (and `uiop:uiop-debug`, which calls it)
  would `load` a computed pathname at run time; `load` is a compile-time splice
  on every backend. `uiop:*uiop-debug-utility*` still holds upstream's default
  form.

```console
$ rontolisp -e '(uiop:register-hook-function (quote *h*) (lambda () 1))'
Unhandled condition: Not (currently) implemented on rontolisp: UIOP/UTILITY:REGISTER-HOOK-FUNCTION pushing onto a hook needs (setf (symbol-value ...)), which is not a place on any backend
```
