;; A leaf-module shim replacing ironclad's src/public-key/public-key.lisp.
;;
;; The real file is 3,065 lines of RSA/DSA/ElGamal/elliptic-curve machinery
;; that the loadable slice has no route to (modular exponentiation over the
;; math/ module, cipher and prng dependencies, dozens of CLOS classes and
;; generics). Two of its functions, however, are self-contained integer <->
;; octet-vector converters with no reference to any of that, and cl-postgres'
;; SCRAM-SHA-256 client proof needs exactly those two. They are reproduced
;; here VERBATIM from ironclad v0.61 (only the compiler `declare`s, which
;; carry no semantics, are dropped) so the arithmetic is the library's own.
;;
;; Written in canonical shape (qualified names, no in-package): the leaf-module
;; splice bypasses the package-resolution bracketing a loaded file gets. The
;; IRONCLAD package is already registered by src/package.lisp, which the core
;; loads first, and both names are in its :export list.

(defun ironclad:octets-to-integer
    (octet-vec &key (start 0) end (big-endian t) n-bits)
  (let ((end (or end (length octet-vec))))
    (multiple-value-bind (n-bits n-bytes) (let ((size (- end start)))
                                            (if n-bits
                                                (values n-bits
                                                 (min (ceiling n-bits 8) size))
                                                (values (* 8 size) size)))
      (let ((sum
             (if big-endian
                 (loop with
                       sum = 0
                       for i from (- end n-bytes) below
                       end
                       do (setf sum (+ (ash sum 8) (aref octet-vec i)))
                       finally (return sum))
                 (loop for i from start below (+ start n-bytes)
                       for j from 0 by 8
                       sum (ash (aref octet-vec i) j)))))
        (ldb (byte n-bits 0) sum)))))

(defun ironclad:integer-to-octets (bignum &key n-bits (big-endian t))
  (let* ((n-bits (or n-bits (integer-length bignum)))
         (bignum (ldb (byte n-bits 0) bignum))
         (n-bytes (ceiling n-bits 8))
         (octet-vec (make-array n-bytes :element-type '(unsigned-byte 8))))
    (if big-endian
        (loop for i from (1- n-bytes) downto 0
              for index from 0
              do (setf (aref octet-vec index) (ldb (byte 8 (* i 8)) bignum))
              finally (return octet-vec))
        (loop for i from 0 below n-bytes
              for byte from 0 by 8
              do (setf (aref octet-vec i) (ldb (byte 8 byte) bignum))
              finally (return octet-vec)))))
