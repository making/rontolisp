;; The base64 pair of ring.util.codec (ring-codec 1.3.0): loaded into the
;; namespace where a program first names one of them, so a program encoding no
;; bytes makes no byte array. java.util.Base64's basic encoder and decoder are
;; the kernels, their refusals the JDK's words.

(defn base64-encode
  "Encode an array of bytes into a base64 encoded string."
  [unencoded]
  (kernel/base64-encode unencoded))

(defn base64-decode
  "Decode a base64 encoded string into an array of bytes."
  [encoded]
  (kernel/base64-decode encoded))
