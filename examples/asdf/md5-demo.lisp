;; Loads the REAL md5 (public domain, Pierre R. Mai) via asdf:load-system and
;; digests the RFC 1321 A.5 test vectors. Run with:
;;   rontolisp examples/asdf/md5-demo.lisp --system-path src/test/resources/md5
;; Interpreter and JVM backends only: the MD5 working state is unsigned 32-bit
;; arithmetic, which does not fit the WASM i31 fixnum range.

(asdf:load-system :md5)

(defun hex (digest)
  (string-downcase
   (with-output-to-string (s)
     (dotimes (i (length digest))
       (format s "~2,'0X" (aref digest i))))))

;; RFC 1321 A.5 test vectors
(print (hex (md5:md5sum-sequence "")))
(print (hex (md5:md5sum-sequence "abc")))
(print (hex (md5:md5sum-sequence "message digest")))

;; the (unsigned-byte 8) vector shape (what a database driver hands it)
(let ((v (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(97 98 99))))
  (print (hex (md5:md5sum-sequence v))))

;; md5sum-string UTF-8-encodes through the flexi-streams shim first
(print (hex (md5:md5sum-string "日本語")))

;; the incremental API: same digest, fed in two chunks
(let ((state (md5:make-md5-state)))
  (md5:update-md5-state state "ab")
  (md5:update-md5-state state "c")
  (print (hex (md5:finalize-md5-state state))))
