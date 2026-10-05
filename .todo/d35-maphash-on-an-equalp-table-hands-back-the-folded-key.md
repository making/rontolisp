# d35. `maphash` on an `equalp` table hands back the folded key

Difficulty: Medium

An `equalp` table stores the key FOLD (`.kb/hash-tables.md`, "`equalp` is a KEY FOLD"), so
every key read back -- `maphash`, `with-hash-table-iterator`, `loop ... being the hash-keys` --
is the representative, not the key as written. SBCL keeps the key of the FIRST insertion and
never replaces it on a later `setf` under an equalp-equal key:

```lisp
(let ((h (make-hash-table :test 'equalp)))
  (setf (gethash "hello" h) 1 (gethash 2.0 h) 2 (gethash 0.5 h) 3
        (gethash '("ab" #\c) h) 4)
  (setf (gethash "HELLO" h) 5)
  (maphash (lambda (k v) (print (list k v))) h))
```

| key read back | SBCL | rontolisp (all four backends) |
|---|---|---|
| `"hello"` (value 5) | `"hello"` | `"HELLO"` |
| `2.0` | `2.0` | `2` |
| `0.5` | `0.5` | `1/2` |
| `("ab" #\c)` | `("ab" #\c)` | `("AB" #\C)` |

Plan: keep the fold as the lookup/hash key and store the original key beside it (first
insertion wins, as in SBCL), on the interpreter (`LispHashTable`), JVM (`RontoHashTable`,
`_hashPut`) and WASM (`_equalp_key` callers). Measure the size of a module with an `equalp`
table first; a module without one must stay byte-identical. One fixture and a ci-spec case on
all four backends; update `.kb/hash-tables.md` ("The fold is also what is STORED") and the
hash-table pages in `doc/en` / `doc/ja` if they state the deviation.
