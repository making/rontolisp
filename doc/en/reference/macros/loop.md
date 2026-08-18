# loop

`(loop clause...)` (extended) or `(loop form...)` (simple)

A bounded subset of the ANSI `loop` macro. It expands to the existing iteration core (`do*`-style stepping wrapped in the internal block boundary), so it works identically on the interpreter and both compilers.

If every top-level subform is a compound form, `loop` is a **simple loop**: it repeats those forms forever until a `return` exits it.

```lisp
(let ((i 0))
  (loop
    (setq i (+ i 1))
    (when (= i 5) (return i)))) ; => 5
```

Otherwise it is an **extended loop** built from clauses. The supported clauses are:

- Numeric stepping: `for VAR from LO [to|upto|below|downto|above HI] [by STEP]` (also `upfrom`/`downfrom`; a limit keyword with no `from` starts at 0).
- List stepping: `for VAR in LIST [by FN]` and `for VAR on LIST [by FN]` (`VAR` may be a destructuring pattern).
- Sequence stepping: `for VAR across SEQ` binds `VAR` to each character of a string or each element of a vector in turn.
- General stepping: `for VAR = INIT [then STEP]` (`VAR` may be a destructuring pattern).
- Local variables: `with VAR [= INIT]` (`VAR` may be a destructuring pattern; `and`-joined `with` bindings are parallel).
- Type declaration: `for`/`as`/`with` accept an optional type spec right after `VAR`, in either ANSI spelling — `of-type TYPE` or the simple bare `fixnum`/`float`/`t`/`nil` — before the rest of the clause. It is parsed and discarded: rontolisp's loop expansion is untyped, so the declaration carries no semantics.
- Accumulation: `collect`, `append`, `nconc`, `sum`, `count`, `maximize`, `minimize`, each with an optional `into VAR`.
- Termination tests: `thereis EXPR`, `always EXPR`, `never EXPR`.
- Control: `while`/`until` (honoring their textual position), `repeat N`, `do FORM...`, `return EXPR`, `(loop-finish)` inside body forms, `initially FORM...`, `finally FORM...`, and the conditionals `when`/`if`/`unless` with optional `else` and `end` (the tested value is available as `it` in the selected clauses).

Multiple `for` clauses step together, and the loop ends as soon as the shortest driver is exhausted — the idiomatic indexed map. Sequential clauses step in order (a later clause's init and step forms see the values the earlier clauses just produced, and stepping stops at the first exhausted driver, so `for x in xs for a = (f x) then (g a x)` works as in CL):

```lisp
(loop for x in '(a b c) for i from 0 collect (list i x)) ; => ((0 A) (1 B) (2 C))
```

`and` joins `for` clauses into one group whose inits and steps are computed against the previous iteration's values (like `do`'s parallel stepping versus `do*`):

```lisp
(loop for a = 0 then b and b = 1 then (+ a b) repeat 8 collect b) ; => (1 1 2 3 5 8 13 21)
```

A `for` variable is **one binding stepped in place**, not a fresh binding per iteration, so once the loop has ended it still holds the value from the final iteration — which is what `finally` sees, and what a closure built in the body answers when it is called afterwards. (`dolist` binds freshly, so its closures each keep their own element; the two are supposed to differ.)

```lisp
(mapcar #'funcall (loop for x in '(1 2 3) collect (lambda () x))) ; => (3 3 3)
```

The variable is assigned only once its own termination test has passed, so the clause whose driver ran out keeps its last value while an earlier clause that still had an element does advance:

```lisp
(loop for x in '(1 2 3) for y in '(10 20) finally (return (list x y))) ; => (3 20)
```

`for VAR on` is the apparent exception and is not one: there the variable *is* the cursor, so it legitimately ends at `nil`, as does a `being the hash-key`/`hash-value` variable. A numeric variable ends one step past its limit:

```lisp
(loop for i from 1 to 3 finally (return i)) ; => 4
```

Accumulation and numeric ranges cover the common cases directly:

```lisp
(loop for i from 1 to 10 when (evenp i) sum i) ; => 30
```

A `while`/`until` after body clauses (or after a `for` that assigns its variable at the top of the body, such as `in`/`on`/`across`) tests at its textual position, so it can reference the current element:

```lisp
(loop for x in '(1 2 3 9 4) while (< x 4) collect x) ; => (1 2 3)
```

Inside a `when`/`if`/`unless`, the anaphoric `it` names the value the test produced:

```lisp
(loop for x in '(1 nil 3 nil 5) when x collect it) ; => (1 3 5)
```

`thereis` returns the first non-nil value of its expression; `always`/`never` short-circuit to `nil` on the first failure and return `t` on normal completion. Like `return`, an early exit from these skips `finally`:

```lisp
(loop for x in '(nil nil 7 9) thereis x) ; => 7
```

`(loop-finish)` inside a body form terminates the iteration normally: `finally` still runs and the loop result is produced (unlike `return`, which skips both):

```lisp
(loop for i from 1
      collect i into xs
      do (when (>= i 3) (loop-finish))
      finally (return (length xs))) ; => 3
```

A `for`/`with` variable may be a destructuring pattern — a list of variables (nested patterns allowed, `nil` ignores a position):

```lisp
(loop for (a b) in '((1 2) (3 4) (5 6)) collect (+ a b)) ; => (3 7 11)
```

A dotted pattern binds the rest of the list, so it walks an alist directly:

```lisp
(loop for (k . v) in '((a . 1) (b . 2)) collect (list k v)) ; => ((A 1) (B 2))
```

`for ... across` walks a string character by character, or a vector element by element:

```lisp
(loop for c across "hello" count (eql c #\l)) ; => 2
```

```lisp
(loop for x across #(1 2 3 4 5) collect (* x x)) ; => (1 4 9 16 25)
```

`for VAR being {the|each} {hash-keys|hash-key|hash-values|hash-value} {of|in} TABLE` drives the loop over a hash table, with an optional `using (hash-value V)` (or `using (hash-key K)`) to bind the other half:

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (loop for k being the hash-keys of h using (hash-value v) collect (list k v))) ; => ((A 1))
```

The clause snapshots the table and walks the snapshot, so the iteration order is the table's, and mutating the table inside the body does not affect the walk in progress.

The package form of `being` — `for VAR being {the|each} {symbols|present-symbols|external-symbols} {of|in} PACKAGE` — is accepted but **lite**: rontolisp has no runtime intern table, so the clause parses and iterates the *empty* sequence. The body never runs and accumulation yields `nil`. It exists so libraries whose load-time code walks a package (such as cl-who's hyperdoc table) load without error:

```lisp
(loop for s being the external-symbols of :cl collect s) ; => NIL
```

A type spec after the variable — either spelling — is accepted and ignored:

```lisp
(loop for v fixnum = 0 then (1+ v) for i from 1 to 3 collect v) ; => (0 1 2)
```

```lisp
(loop for v of-type fixnum = 0 then (1+ v) for i from 1 to 3 collect v) ; => (0 1 2)
```

Limitations: `named`/`return-from` is not supported. Destructuring patterns do not recognize lambda-list keywords (`&optional` and friends bind as ordinary variables rather than signalling). `(loop-finish)` must appear in statement position (not mid-expression) and not inside a nested iteration form. `thereis`/`always`/`never` cannot be combined with accumulation into the default result (use `into`). Accumulation clauses without `into` must all be of the same kind; collecting clauses build the result list in source order.
