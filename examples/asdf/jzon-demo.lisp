;;;; com.inuoe.jzon via ql:quickload / asdf:load-system
;;;; Loads the REAL jzon v1.1.4 (unmodified upstream sources) and exercises
;;;; JSON parsing and stringification: scalars, nested structures and a
;;;; round-trip. Its dependencies (closer-mop, flexi-streams,
;;;; float-features, trivial-gray-streams, uiop) resolve to rontolisp's
;;;; built-in shim systems. INTERPRETER ONLY for now: the compiled backends
;;;; cannot run jzon's float printer (64-bit/bignum bit arithmetic) or its
;;;; adjustable-string buffers; see the systems guide.
;;;;
;;;; Run (library vendored in this repository):
;;;;   rontolisp examples/asdf/jzon-demo.lisp --system-path src/test/resources/jzon/src

(asdf:load-system :com.inuoe.jzon)

;; A shorthand for the long package name (the README idiom).
(uiop:add-package-local-nickname '#:jzon '#:com.inuoe.jzon)

;; Scalars parse to the natural rontolisp values.
(print (jzon:parse "42"))
(print (jzon:parse "-1.5"))
(print (jzon:parse "\"hello\""))
(print (jzon:parse "true"))
(print (jzon:parse "null"))

;; An array parses to a vector, an object to a hash table.
(print (jzon:parse "[1, 2, 3]"))
(let ((obj (jzon:parse "{\"name\": \"rontolisp\", \"tags\": [\"lisp\", \"wasm\"]}")))
  (print (gethash "name" obj))
  (print (gethash "tags" obj)))

;; Stringify renders JSON text; :pretty adds newlines and indentation.
(write-string (jzon:stringify #(1 2 3)))
(terpri)
(let ((table (make-hash-table)))
  (setf (gethash "a" table) 1)
  (write-string (jzon:stringify table))
  (terpri))

;; Round-trip: parse then stringify.
(write-string (jzon:stringify (jzon:parse "{\"k\": [true, null, 7]}")))
(terpri)
