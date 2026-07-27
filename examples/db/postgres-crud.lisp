;; The whole CRUD cycle -- create, insert, select, update, delete -- against a
;; real PostgreSQL with the REAL cl-postgres (zlib, Marijn Haverbeke / Sabra
;; Crolleton -- Postmodern's low-level driver) loaded from its unmodified
;; upstream sources. Also two things postgres-hello.lisp does not show: a
;; parameterised statement through the extended query protocol
;; (prepare-query + exec-prepared), and a second row reader shape.
;;
;; It needs a server. The one-liner this example is written against:
;;   docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
;;   rontolisp examples/db/postgres-crud.lisp
;;
;; Connecting, authenticating and the other backends work exactly as in
;; postgres-hello.lisp (see it, and the README beside it):
;;   rontolisp examples/db/postgres-crud.lisp -o Prog.class && java Prog
;;   rontolisp examples/db/postgres-crud.lisp -o postgres-crud.wasm --component --optimize
;;   wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y postgres-crud.wasm

(ql:quickload "cl-postgres")

(defun fruits (conn)
  "Every row, as a list of (id name price) lists."
  (cl-postgres:exec-query conn "select id, name, price from fruits order by id"
                          'cl-postgres:list-row-reader))

(let ((conn (cl-postgres:open-database "postgres" "postgres" nil "127.0.0.1" 54329)))
  ;; close-database in the cleanup, so the connection goes back even if a query
  ;; signals.
  (unwind-protect
      (progn
        ;; One transaction, rolled back at the end: the example leaves the
        ;; server exactly as it found it, so it can be re-run as often as you
        ;; like -- and a run that dies halfway leaves nothing behind either.
        (cl-postgres:exec-query conn "begin")

        ;; CREATE. A statement with no result needs no row reader.
        (cl-postgres:exec-query
         conn "create table fruits (id integer primary key, name text, price integer)")

        ;; INSERT
        (cl-postgres:exec-query
         conn "insert into fruits (id, name, price)
               values (1, 'apple', 120), (2, 'banana', 80), (3, 'cherry', 300)")
        (format t "inserted: ~a~%" (fruits conn))

        ;; READ. The row reader decides the shape: alist-row-reader labels each
        ;; column with its name, where list-row-reader gives bare lists.
        (format t "as alists: ~a~%"
                (cl-postgres:exec-query conn "select name, price from fruits where id = 2"
                                        'cl-postgres:alist-row-reader))

        ;; UPDATE
        (cl-postgres:exec-query conn "update fruits set price = 150 where name = 'apple'")
        (format t "after update: ~a~%" (fruits conn))

        ;; DELETE
        (cl-postgres:exec-query conn "delete from fruits where price > 200")
        (format t "after delete: ~a~%" (fruits conn))

        ;; A parameterised statement: prepared once on the server, then run with
        ;; different arguments. $1 is the placeholder; the arguments are a list.
        (cl-postgres:prepare-query conn "cheaper-than"
                                   "select name from fruits where price < $1 order by name")
        (format t "under 100: ~a~%"
                (cl-postgres:exec-prepared conn "cheaper-than" (list 100)
                                           'cl-postgres:list-row-reader))
        (format t "under 200: ~a~%"
                (cl-postgres:exec-prepared conn "cheaper-than" (list 200)
                                           'cl-postgres:list-row-reader))

        (cl-postgres:exec-query conn "rollback"))
    (cl-postgres:close-database conn)))
