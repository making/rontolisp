;; The same CRUD cycle as postgres-crud.lisp, one layer up: the REAL
;; postmodern (zlib, Marijn Haverbeke / Sabra Crolleton) loaded from its
;; unmodified upstream sources. Where postgres-crud.lisp opens a connection by
;; hand and writes SQL strings, everything here is a macro over the same
;; driver: with-connection manages the connection, S-SQL writes the statements
;; as s-expressions, with-transaction wraps the update, and defprepared turns a
;; parameterised statement into an ordinary function.
;;
;; It needs a server, and reads where to find it out of DATABASE_URL
;; (database-url.lisp beside this file parses the URL). The one-liner this
;; example is written against, and the URL that reaches it:
;;   docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
;;   export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres
;;   rontolisp examples/db/postmodern-crud.lisp
;;
;; The other backends work exactly as in postgres-hello.lisp (see it, and the
;; README beside it):
;;   rontolisp examples/db/postmodern-crud.lisp -o Prog.class && java Prog
;;   rontolisp examples/db/postmodern-crud.lisp -o postmodern-crud.wasm --component --optimize
;;   wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y --env DATABASE_URL postmodern-crud.wasm
;;
;; This is the non-MOP build of postmodern: the query, transaction and
;; prepared-statement layers shown below. The DAO layer (defclass with
;; :metaclass dao-class, get-dao / select-dao / insert-dao) needs the MOP and
;; is not available.

(ql:quickload "postmodern")

(load "database-url.lisp")

(defun connection-spec ()
  "What DATABASE_URL says, in the shape with-connection wants: the four
   positional values, then :port. The same five values postgres-crud.lisp hands
   to open-database -- with-connection is a macro over that same driver."
  (multiple-value-bind (database user password host port)
      (database-url-parts (uiop:getenv "DATABASE_URL"))
    (list database user password host :port port)))

;; S-SQL turns an s-expression into an SQL string. When every value in the form
;; is a literal the whole translation happens while the program is being read,
;; and the statement reaches the server as a constant; when one is not, s-sql
;; assembles the string while the program runs. pomo:sql shows either.
(format t "sql: ~a~%" (pomo:sql (:select 'name :from 'fruits :where (:< 'price 100))))
(format t "sql: ~a~%" (pomo:sql (:insert-rows-into 'fruits :columns 'id :values '((1) (2)))))

;; The connection is bound for the whole body -- every query below finds it
;; through pomo:*database* -- and closed on the way out, however the body ends.
(pomo:with-connection (connection-spec)

  ;; CREATE. execute is query with no result; the leading drop makes the
  ;; example re-runnable.
  (pomo:execute (:drop-table :if-exists 'fruits))
  (pomo:execute (:create-table 'fruits ((id :type integer :primary-key t)
                                        (name :type text)
                                        (price :type integer))))

  ;; INSERT. :set takes alternating column and value forms.
  (pomo:execute (:insert-into 'fruits :set 'id 1 'name "apple" 'price 120))
  (pomo:execute (:insert-into 'fruits :set 'id 2 'name "banana" 'price 80))
  ;; A value that is not a literal -- here an expression, but a variable reads
  ;; the same -- is what makes s-sql assemble the statement while the program
  ;; runs rather than while it is read. pomo:sql shows the difference.
  (pomo:execute (:insert-into 'fruits :set 'id 3 'name "cherry" 'price (* 3 100)))

  ;; :insert-rows-into writes several rows at once, and is likewise assembled
  ;; at run time.
  (pomo:execute (:insert-rows-into 'fruits
                                   :columns 'id 'name 'price
                                   :values '((4 "durian" 900) (5 "elderberry" 60))))

  ;; READ. The result format is an argument: the default is a list of rows...
  (format t "rows: ~a~%"
          (pomo:query (:order-by (:select '* :from 'fruits) 'id)))
  ;; ...:alists labels each column with its name...
  (format t "alists: ~a~%"
          (pomo:query (:select 'name 'price :from 'fruits :where (:= 'id 2)) :alists))
  ;; ...and :single takes the one value out of a one-row, one-column result.
  (format t "count: ~a~%"
          (pomo:query (:select (:count '*) :from 'fruits) :single))

  ;; UPDATE, in a transaction: the body commits on a normal return and rolls
  ;; back if it signals.
  (pomo:with-transaction ()
    (pomo:execute (:update 'fruits :set 'price 150 :where (:= 'name "apple"))))
  (format t "after update: ~a~%"
          (pomo:query (:order-by (:select 'name 'price :from 'fruits) 'id)))

  ;; DELETE
  (pomo:execute (:delete-from 'fruits :where (:> 'price 200)))
  (format t "after delete: ~a~%"
          (pomo:query (:order-by (:select 'name :from 'fruits) 'name) :column))

  ;; A parameterised statement, prepared once on the server and then called
  ;; like any other function. $1 is the placeholder.
  (pomo:defprepared cheaper-than
      (:select 'name :from 'fruits :where (:< 'price '$1))
    :column)
  (format t "under 100: ~a~%" (cheaper-than 100))
  (format t "under 200: ~a~%" (cheaper-than 200)))

;; The table is left behind on purpose, so you can look at it with psql after
;; the run; the drop at the top is what makes the next run start clean.
