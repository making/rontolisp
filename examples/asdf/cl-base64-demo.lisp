;; Loads the REAL cl-base64 (BSD, Kevin M. Rosenberg) via asdf:load-system and
;; exercises its public encode/decode API. Run with:
;;   rontolisp examples/asdf/cl-base64-demo.lisp --system-path src/test/resources/cl-base64
;; (see examples/asdf/README.md for the compile-path variants).

(asdf:load-system :cl-base64)

;; string <-> base64 string (the names are synthesized at macro-expansion time
;; by (intern (concatenate 'string (symbol-name input-type) ...)))
(print (cl-base64:string-to-base64-string "Hello, World!"))
(print (cl-base64:base64-string-to-string "SGVsbG8sIFdvcmxkIQ=="))

;; :columns wraps the output with newlines
(print (cl-base64:string-to-base64-string "Hello, World!" :columns 5))

;; :uri t uses the URI-safe alphabet (- _ and . padding)
(print (cl-base64:string-to-base64-string "Hello?>>" :uri t))
(print (cl-base64:base64-string-to-string "SGVsbG8_Pj4." :uri t))

;; (unsigned-byte 8) arrays
(print
 (cl-base64:usb8-array-to-base64-string
  (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3))))
(print (cl-base64:base64-string-to-usb8-array "AQID"))

;; integers (exact on every backend within the signed 64-bit range the
;; WASM backends carry)
(print (cl-base64:integer-to-base64-string 1234567))
(print (cl-base64:base64-string-to-integer "EtaH"))

;; a bad input character signals bad-base64-character, caught by handler-case
(print
 (handler-case (cl-base64:base64-string-to-string "SGVsbG8@")
   (error (e) :caught-bad-char)))
