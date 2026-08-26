(asdf:load-system :cffi)

;; 1. the real defcfun, over the process's own symbols
(cffi:defcfun "strlen" :long (s :string))
(format t "strlen        = ~a~%" (strlen "hello, world"))

;; 2. define-foreign-library / use-foreign-library, then a renamed defcfun
(cffi:define-foreign-library libm (t "libm.so.6"))
(cffi:use-foreign-library libm)
(cffi:defcfun ("cos" c-cos) :double (x :double))
(format t "cos(0.0)      = ~a~%" (c-cos 0.0d0))

;; 3. a third-party library and a :string return
(cffi:define-foreign-library libsqlite (t "libsqlite3.so.0"))
(cffi:use-foreign-library libsqlite)
(cffi:defcfun ("sqlite3_libversion" sqlite-version) :string)
(format t "sqlite ver    = ~a~%" (sqlite-version))

;; 4. foreign-funcall, the anonymous form
(format t "getpid        = ~a~%" (cffi:foreign-funcall "getpid" :int))

;; 5. with-foreign-object + mem-ref: an out parameter
(cffi:with-foreign-object (tv :long 2)
  (cffi:foreign-funcall "gettimeofday" :pointer tv :pointer (cffi:null-pointer) :int)
  (format t "gettimeofday  = ~a~%" (cffi:mem-ref tv :long)))

;; 6. defctype + foreign-type-size
(cffi:defctype my-size :uint64)
(format t "type sizes    = int ~a pointer ~a my-size ~a~%"
        (cffi:foreign-type-size :int) (cffi:foreign-type-size :pointer)
        (cffi:foreign-type-size 'my-size))

;; 7. foreign-string round trip
(cffi:with-foreign-string (s "hello")
  (format t "string trip   = ~a~%" (cffi:foreign-string-to-lisp s)))

;; 8. defcstruct + slot access
(cffi:defcstruct timeval (tv-sec :long) (tv-usec :long))
(cffi:with-foreign-object (tv '(:struct timeval))
  (cffi:foreign-funcall "gettimeofday" :pointer tv :pointer (cffi:null-pointer) :int)
  (format t "struct slot   = ~a~%" (cffi:foreign-slot-value tv '(:struct timeval) 'tv-sec)))

;; 9. a real sqlite3 session, entirely through cffi
(cffi:defcfun ("sqlite3_open" sqlite3-open) :int (path :string) (db :pointer))
(cffi:defcfun ("sqlite3_exec" sqlite3-exec) :int
  (db :pointer) (sql :string) (cb :pointer) (arg :pointer) (err :pointer))
(cffi:defcfun ("sqlite3_errmsg" sqlite3-errmsg) :string (db :pointer))
(cffi:defcfun ("sqlite3_close" sqlite3-close) :int (db :pointer))
(cffi:with-foreign-object (handle :pointer)
  (let ((rc (sqlite3-open ":memory:" handle)))
    (let ((db (cffi:mem-ref handle :pointer)))
      (format t "sqlite open   = rc ~a~%" rc)
      (format t "sqlite exec   = rc ~a~%"
              (sqlite3-exec db "create table t (a); insert into t values (1)"
                            (cffi:null-pointer) (cffi:null-pointer) (cffi:null-pointer)))
      (format t "sqlite errmsg = ~a~%" (sqlite3-errmsg db))
      (sqlite3-close db))))
