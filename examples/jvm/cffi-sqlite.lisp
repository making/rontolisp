;; A real SQLite database through cl-sqlite, which is upstream CFFI and nothing
;; else: (ql:quickload "sqlite") downloads the same release SBCL would, and its
;; defcfun forms bind libsqlite3 through the JVM's foreign function API. No SQL
;; engine is bundled here -- the C library on the machine IS the engine.
;;
;; JVM family only: the interpreter, the native binary and a compiled .class or
;; .jar. Neither WASM backend has a foreign function API, so a program reaching
;; cffi refuses to compile there.

(ql:quickload "sqlite")

(defvar *db* (sqlite:connect ":memory:"))

(sqlite:execute-non-query *db*
 "create table parts (id integer primary key, name text, qty integer)")

(dolist (row '(("bolt" 12) ("nut" 30) ("washer" 7)))
  (sqlite:execute-non-query *db* "insert into parts (name, qty) values (?, ?)"
                            (first row) (second row)))

(sqlite:execute-non-query *db* "update parts set qty = ? where name = ?" 25
                          "bolt")

(format t "sqlite ~a~%" (sqlite:execute-single *db* "select sqlite_version()"))

(format t "rows ~a~%" (sqlite:execute-single *db* "select count(*) from parts"))

(dolist (row
         (sqlite:execute-to-list *db*
          "select name, qty from parts order by qty desc"))
  (format t "~a ~a~%" (first row) (second row)))

(format t "total ~a~%"
        (sqlite:execute-single *db* "select sum(qty) from parts"))

;; A prepared statement stepped by hand, the other half of the binding's API.
(sqlite:with-transaction *db*
  (let ((statement
         (sqlite:prepare-statement *db*
                                   "select name from parts where qty > ?")))
    (sqlite:bind-parameter statement 1 10)
    (loop while (sqlite:step-statement statement)
          do
            (format t "over-ten ~a~%"
                    (sqlite:statement-column-value statement 0)))
    (sqlite:finalize-statement statement)))

(sqlite:disconnect *db*)
