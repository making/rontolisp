;; The layer postmodern-crud.lisp stops short of: the DAO. A table row becomes
;; an ordinary CLOS instance -- the class IS the table definition. Writing
;; (:metaclass pomo:dao-class) runs postmodern's metaclass protocol when the
;; class is defined: every :col-type slot becomes a column, :keys names the
;; primary key, and the insert-dao / get-dao / update-dao / upsert-dao /
;; delete-dao / select-dao methods for exactly this class are built on the
;; spot.
;;
;; It needs a server, and reads where to find it out of DATABASE_URL
;; (database-url.lisp beside this file parses the URL). The one-liner this
;; example is written against, and the URL that reaches it:
;;   docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
;;   export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres
;;   rontolisp examples/db/postmodern-dao.lisp
;;
;; The other backends work exactly as in postgres-hello.lisp (see it, and the
;; README beside it):
;;   rontolisp examples/db/postmodern-dao.lisp -o Prog.class && java Prog
;;   rontolisp examples/db/postmodern-dao.lisp -o postmodern-dao.wasm --component --optimize
;;   wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y --env DATABASE_URL postmodern-dao.wasm

(ql:quickload "postmodern")

(load "database-url.lisp")

(defun connection-spec ()
  "What DATABASE_URL says, in the shape with-connection wants."
  (multiple-value-bind (database user password host port)
      (database-url-parts (uiop:getenv "DATABASE_URL"))
    (list database user password host :port port)))

;; The class definition is the whole schema: two columns, id the primary key.
;; Slots without :col-type would be ordinary (non-column) slots.
(defclass fruit ()
  ((id :col-type integer :initarg :id :accessor fruit-id)
   (name :col-type text :initarg :name :accessor fruit-name)
   (price :col-type integer :initarg :price :accessor fruit-price))
  (:metaclass pomo:dao-class)
  (:keys id))

;; The CREATE TABLE statement is derived from the class -- no server needed to
;; look at it.
(format t "ddl: ~a~%" (pomo:dao-table-definition 'fruit))

;; deftable records how to create the table; !dao-def says "from the DAO class
;; of the same name". create-table below replays it against the server.
(pomo:deftable fruit (pomo:!dao-def))

(pomo:with-connection (connection-spec)

  ;; The leading drop makes the example re-runnable.
  (pomo:execute (:drop-table :if-exists 'fruit))
  (pomo:create-table 'fruit)

  ;; CREATE: an instance goes in as a row.
  (pomo:insert-dao (make-instance 'fruit :id 1 :name "apple" :price 120))
  (pomo:insert-dao (make-instance 'fruit :id 2 :name "banana" :price 80))

  ;; READ: get-dao fetches by primary key and answers an instance.
  (let ((apple (pomo:get-dao 'fruit 1)))
    (format t "got: ~a at ~a~%" (fruit-name apple) (fruit-price apple))

    ;; UPDATE: change the instance, then write it back.
    (setf (fruit-price apple) 150)
    (pomo:update-dao apple))

  ;; UPSERT: update the row if the key exists, insert it otherwise. The second
  ;; value says which happened -- nil for an update, t for an insert.
  (multiple-value-bind (dao inserted-p)
      (pomo:upsert-dao (make-instance 'fruit :id 2 :name "blueberry" :price 90))
    (format t "upsert ~a: inserted-p ~a~%" (fruit-name dao) inserted-p))
  (multiple-value-bind (dao inserted-p)
      (pomo:upsert-dao (make-instance 'fruit :id 3 :name "cherry" :price 300))
    (format t "upsert ~a: inserted-p ~a~%" (fruit-name dao) inserted-p))

  ;; SELECT: a test (any S-SQL expression) and an ordering, answered as a list
  ;; of instances.
  (dolist (f (pomo:select-dao 'fruit (:< 'price 200) 'id))
    (format t "row: ~a ~a ~a~%" (fruit-id f) (fruit-name f) (fruit-price f)))

  ;; DELETE takes the instance too.
  (pomo:delete-dao (pomo:get-dao 'fruit 3))
  (format t "count: ~a~%"
          (pomo:query (:select (:count '*) :from 'fruit) :single)))

;; The table is left behind on purpose, so you can look at it with psql after
;; the run; the drop at the top is what makes the next run start clean.
