;;;; inflate.lisp -- rontolisp::%inflate-new / %inflate-update / %inflate-finish, the
;;;; streaming DEFLATE decoder (RFC 1951) with its zlib (RFC 1950) and gzip (RFC 1952)
;;;; wrappers that rontolisp.http-client reads a compressed reply through.
;;;;
;;;; The wasm targets' decoder, spliced by eval/InflateLibrary into a --component or a
;;;; Preview 1 module (a --native output's) that names one of the three. The
;;;; interpreter and the JVM run runtime/RontoInflate, the same decoder in Java unit for
;;;; unit; InflateLibraryTest pins this one to it, and RontoInflateTest that one to
;;;; java.util.zip, which reports the messages below (zlib's, and GZIPInputStream's).
;;;;
;;;; Input arrives a chunk at a time. Decoding goes a UNIT at a time -- a block header, a
;;;; symbol and its extra bits, a match, a header, a trailer -- and a unit the input runs
;;;; out inside (an underflow, thrown to the update) is put back whole and decoded again
;;;; once more input has come, so the output does not depend on where chunks were cut.
;;;; Every value stays a fixnum on a 31-bit target: the bit buffer holds at most 23
;;;; bits, the CRC-32 is two 16-bit halves.
;;;;
;;;; A decoder is a simple vector:
;;;;  0 kind (0 raw, 1 zlib, 2 gzip)   1 phase   2 in   3 pos   4 hold   5 bits
;;;;  6 mark-pos   7 mark-hold   8 mark-bits   9 window   10 wpos   11 have
;;;;  12 last   13 lens   14 dists   15 remaining (stored)   16 copy-len   17 copy-dist
;;;;  18 check-lo   19 check-hi   20 size-lo   21 size-hi   22 crc table   23 error
;;;;  24 tables #(lbase lext dbase dext order)   25 out   26 opos   27 fixed codes
;;;;  28 sum-from
;;;; phases: 0 header, 1 block, 2 stored, 3 codes, 4 check, 5 next member, 6 done.
;;;; A code is #(count symbols table): count[1..15] codes of each length, the longest
;;;; at 16; the symbols in code order; a 512-entry table from the next 9 bits to
;;;; (symbol << 4) | length for every code of at most 9 bits, 0 elsewhere.

(defun rontolisp::%inflate-new (kind)
  (let ((st (make-array 29 :initial-element 0)))
    (setf (svref st 0) kind)
    (setf (svref st 1) (if (= kind 0) 1 0))
    (setf (svref st 2) (make-array 0 :element-type '(unsigned-byte 8)))
    (setf (svref st 9) (make-array 32768 :element-type '(unsigned-byte 8)))
    (setf (svref st 12) nil)
    (setf (svref st 18) (if (= kind 1) 1 0))
    (setf (svref st 22) (if (= kind 2) (rontolisp::%inflate-crc-table) nil))
    (setf (svref st 23) nil)
    (setf (svref st 24)
          (vector
           (vector 3 4 5 6 7 8 9 10 11 13 15 17 19 23 27 31 35 43 51 59 67 83 99
                   115 131 163 195 227 258)
           (vector 0 0 0 0 0 0 0 0 1 1 1 1 2 2 2 2 3 3 3 3 4 4 4 4 5 5 5 5 0)
           (vector 1 2 3 4 5 7 9 13 17 25 33 49 65 97 129 193 257 385 513 769
                   1025 1537 2049 3073 4097 6145 8193 12289 16385 24577)
           (vector 0 0 0 0 1 1 2 2 3 3 4 4 5 5 6 6 7 7 8 8 9 9 10 10 11 11 12 12
                   13 13)
           (vector 16 17 18 0 8 7 9 6 10 5 11 4 12 3 13 2 14 1 15)))
    (setf (svref st 25) nil)
    (setf (svref st 27) nil)
    st))

(defun rontolisp::%inflate-crc-table ()
  ;; CRC-32 (the gzip polynomial #xEDB88320, reflected) of each octet, as two
  ;; 16-bit halves (low at 2n, high at 2n+1): every value stays a fixnum on a
  ;; 31-bit target
  (let ((table (make-array 512 :element-type '(unsigned-byte 16))))
    (dotimes (n 256)
      (let ((lo n) (hi 0))
        (dotimes (k 8)
          (let ((odd (logand lo 1)))
            (setq lo (logior (ash lo -1) (ash (logand hi 1) 15)))
            (setq hi (ash hi -1))
            (if (= odd 1)
                (progn
                  (setq lo (logxor lo #x8320))
                  (setq hi (logxor hi #xEDB8))))))
        (setf (aref table (* 2 n)) lo)
        (setf (aref table (+ (* 2 n) 1)) hi)))
    table))

(defun rontolisp::%inflate-crc (st lo hi octets start end)
  ;; the CRC-32 register LO/HI run over OCTETS[START..END), stored into the
  ;; state's check slots
  (let ((table (svref st 22)) (i start))
    (while (< i end)
      (let ((idx (* 2 (logand (logxor lo (aref octets i)) 255))))
        (setq lo
         (logxor (logior (ash lo -8) (ash (logand hi 255) 8)) (aref table idx)))
        (setq hi (logxor (ash hi -8) (aref table (+ idx 1))))
        (setq i (+ i 1))))
    (setf (svref st 18) lo)
    (setf (svref st 19) hi)
    nil))

(defun rontolisp::%inflate-sum (st)
  ;; the check and the size run over the octets decoded since the last sum
  (let ((out (svref st 25))
        (from (svref st 28))
        (to (svref st 26))
        (kind (svref st 0)))
    (cond ((= kind 2)
           (rontolisp::%inflate-crc st (svref st 18) (svref st 19) out from to)
           (let ((lo (+ (svref st 20) (- to from))))
             (setf (svref st 21) (logand (+ (svref st 21) (ash lo -16)) 65535))
             (setf (svref st 20) (logand lo 65535))))
          ((= kind 1)
           (let ((a (svref st 18)) (b (svref st 19)) (i from))
             (while (< i to)
               (setq a (+ a (aref out i)))
               (if (>= a 65521) (setq a (- a 65521)))
               (setq b (+ b a))
               (if (>= b 65521) (setq b (- b 65521)))
               (setq i (+ i 1)))
             (setf (svref st 18) a)
             (setf (svref st 19) b))))
    (setf (svref st 28) to)
    nil))

(defun rontolisp::%inflate-underflow ()
  (throw 'rontolisp::%inflate-stop :underflow))

(defun rontolisp::%inflate-fail (message)
  (throw 'rontolisp::%inflate-stop message))

(defun rontolisp::%inflate-need (st n)
  ;; at least N bits in the bit buffer, pulled an octet at a time, or the
  ;; underflow that puts the unit back
  (let ((bits (svref st 5)))
    (if (< bits n)
        (let ((in (svref st 2)) (pos (svref st 3)) (hold (svref st 4)))
          (while (< bits n)
            (if (>= pos (length in)) (rontolisp::%inflate-underflow))
            (setq hold (logior hold (ash (aref in pos) bits)))
            (setq pos (+ pos 1))
            (setq bits (+ bits 8)))
          (setf (svref st 3) pos)
          (setf (svref st 4) hold)
          (setf (svref st 5) bits)))
    nil))

(defun rontolisp::%inflate-bits (st n)
  ;; the next N bits, least significant first
  (rontolisp::%inflate-need st n)
  (let ((hold (svref st 4)))
    (setf (svref st 4) (ash hold (- n)))
    (setf (svref st 5) (- (svref st 5) n))
    (logand hold (- (ash 1 n) 1))))

(defun rontolisp::%inflate-octet (st) (rontolisp::%inflate-bits st 8))

(defun rontolisp::%inflate-align (st)
  ;; the rest of the current octet dropped
  (let ((drop (logand (svref st 5) 7)))
    (setf (svref st 4) (ash (svref st 4) (- drop)))
    (setf (svref st 5) (- (svref st 5) drop))
    nil))

(defun rontolisp::%inflate-mark (st)
  ;; the start of a unit: where an underflow puts the decoder back
  (setf (svref st 6) (svref st 3))
  (setf (svref st 7) (svref st 4))
  (setf (svref st 8) (svref st 5))
  nil)

(defun rontolisp::%inflate-code (lengths n strict)
  ;; the canonical Huffman code of the N code LENGTHS. Nil for an
  ;; over-subscribed set, and for an incomplete one unless it is a single code
  ;; of length 1 and not STRICT -- zlib's inflate_table, which takes a set with
  ;; no code at all
  (let ((count (make-array 17 :initial-element 0))
        (offs (make-array 16 :initial-element 0))
        (next (make-array 16 :initial-element 0))
        (symbols (make-array n :initial-element 0))
        (table
         (make-array 512 :element-type '(unsigned-byte 16) :initial-element 0))
        (left 1)
        (max 0)
        (len 1))
    (dotimes (s n)
      (let ((l (aref lengths s))) (setf (svref count l) (+ (svref count l) 1))))
    (while (<= len 15)
      (if (> (svref count len) 0) (setq max len))
      (setq left (- (* left 2) (svref count len)))
      (if (< left 0) (setq len 16) (setq len (+ len 1))))
    (setf (svref count 16) max)
    (cond ((< left 0) nil)
          ((= max 0) (vector count symbols table))
          ((and (> left 0) (or strict (/= max 1))) nil)
          (t
           (setq len 1)
           (let ((c 0))
             (while (<= len 15)
               (setq c (ash (+ c (if (= len 1) 0 (svref count (- len 1)))) 1))
               (setf (svref next len) c)
               (if (< len 15)
                   (setf (svref offs (+ len 1))
                         (+ (svref offs len) (svref count len))))
               (setq len (+ len 1))))
           (dotimes (s n)
             (let ((l (aref lengths s)))
               (if (/= l 0)
                   (let ((c (svref next l)) (r 0))
                     (setf (svref symbols (svref offs l)) s)
                     (setf (svref offs l) (+ (svref offs l) 1))
                     (setf (svref next l) (+ c 1))
                     (if (<= l 9)
                         (progn
                           (dotimes (k l)
                             (setq r
                                   (logior (ash r 1) (logand (ash c (- k)) 1))))
                           (let ((step (ash 1 l)) (entry (+ (* s 16) l)))
                             (while (< r 512)
                               (setf (aref table r) entry)
                               (setq r (+ r step))))))))))
           (vector count symbols table)))))

(defun rontolisp::%inflate-lookup (code hold)
  ;; the symbol of CODE the bits of HOLD (at least the longest code's) start
  ;; with, as (symbol << 4) | length; -1 for bits no symbol has (an
  ;; incomplete code's, or any of a code with none)
  (let* ((count (svref code 0)) (max (svref count 16)))
    (if (= max 0)
        -1
        (let ((c 0) (first 0) (index 0) (len 1) (found nil))
          (while (null found)
            (setq c (logior c (logand (ash hold (- 1 len)) 1)))
            (let ((k (svref count len)))
              (if (< (- c k) first)
                  (setq found
                   (+ (* (svref (svref code 1) (+ index (- c first))) 16) len))
                  (progn
                    (setq index (+ index k))
                    (setq first (ash (+ first k) 1))
                    (setq c (ash c 1))
                    (if (>= len max) (setq found -1) (setq len (+ len 1)))))))
          found))))

(defun rontolisp::%inflate-decode (st code)
  ;; the next symbol of CODE, the bits taken a unit at a time; -1 for bits no
  ;; symbol has (an incomplete code's after its longest code, a bit of a code
  ;; with none)
  (let* ((count (svref code 0)) (symbols (svref code 1)) (max (svref count 16)))
    (if (= max 0)
        (progn
          (rontolisp::%inflate-bits st 1)
          -1)
        (let ((c 0) (first 0) (index 0) (len 1) (found nil))
          (while (null found)
            (setq c (logior c (rontolisp::%inflate-bits st 1)))
            (let ((k (svref count len)))
              (if (< (- c k) first)
                  (setq found (svref symbols (+ index (- c first))))
                  (progn
                    (setq index (+ index k))
                    (setq first (ash (+ first k) 1))
                    (setq c (ash c 1))
                    (if (>= len max) (setq found -1) (setq len (+ len 1)))))))
          found))))

(defun rontolisp::%inflate-fixed (st)
  ;; the fixed literal/length and distance codes, made once per decoder
  (or (svref st 27)
      (let ((lengths (make-array 288 :initial-element 8)))
        (dotimes (s 288)
          (setf (svref lengths s)
                (cond ((< s 144) 8) ((< s 256) 9) ((< s 280) 7) (t 8))))
        (setf (svref st 27)
              (cons (rontolisp::%inflate-code lengths 288 nil)
                    (rontolisp::%inflate-code (make-array 32 :initial-element 5)
                                              32 nil))))))

(defun rontolisp::%inflate-dynamic (st)
  ;; a dynamic block's code definitions, zlib's checks in zlib's order
  (let* ((nlen (+ (rontolisp::%inflate-bits st 5) 257))
         (ndist (+ (rontolisp::%inflate-bits st 5) 1))
         (ncode (+ (rontolisp::%inflate-bits st 4) 4))
         (order (svref (svref st 24) 4))
         (lengths (make-array 320 :initial-element 0)))
    (if (or (> nlen 286) (> ndist 30))
        (rontolisp::%inflate-fail "too many length or distance symbols"))
    (dotimes (i ncode)
      (setf (svref lengths (svref order i)) (rontolisp::%inflate-bits st 3)))
    (let ((lencode (rontolisp::%inflate-code lengths 19 t))
          (have 0)
          (total (+ nlen ndist)))
      (if (null lencode) (rontolisp::%inflate-fail "invalid code lengths set"))
      (fill lengths 0)
      (while (< have total)
        (let ((sym (rontolisp::%inflate-decode st lencode)))
          ;; a code length code with no code reads every length as 0, a bit each
          (if (< sym 16)
              (progn
                (setf (svref lengths have) (if (< sym 0) 0 sym))
                (setq have (+ have 1)))
              (let ((len 0) (copy 0))
                (cond
                 ((= sym 16)
                  (rontolisp::%inflate-need st 2)
                  (if (= have 0)
                      (rontolisp::%inflate-fail "invalid bit length repeat"))
                  (setq len (svref lengths (- have 1)))
                  (setq copy (+ 3 (rontolisp::%inflate-bits st 2))))
                 ((= sym 17) (setq copy (+ 3 (rontolisp::%inflate-bits st 3))))
                 (t (setq copy (+ 11 (rontolisp::%inflate-bits st 7)))))
                (if (> (+ have copy) total)
                    (rontolisp::%inflate-fail "invalid bit length repeat"))
                (dotimes (k copy)
                  (setf (svref lengths have) len)
                  (setq have (+ have 1)))))))
      (if (= (svref lengths 256) 0)
          (rontolisp::%inflate-fail "invalid code -- missing end-of-block"))
      (let ((lens (rontolisp::%inflate-code lengths nlen nil))
            (dlengths (make-array ndist :initial-element 0)))
        (if (null lens)
            (rontolisp::%inflate-fail "invalid literal/lengths set"))
        (dotimes (i ndist) (setf (svref dlengths i) (svref lengths (+ nlen i))))
        (let ((dists (rontolisp::%inflate-code dlengths ndist nil)))
          (if (null dists) (rontolisp::%inflate-fail "invalid distances set"))
          (cons lens dists))))))

(defun rontolisp::%inflate-room (st n)
  ;; the output with room for N more octets
  (let ((out (svref st 25)) (opos (svref st 26)))
    (if (> (+ opos n) (length out))
        (let ((grown
               (make-array (max (* 2 (length out)) (+ opos n))
                           :element-type '(unsigned-byte 8))))
          (replace grown out :end2 opos)
          (setf (svref st 25) grown)
          grown)
        out)))

(defun rontolisp::%inflate-put (st b)
  ;; one decoded octet into the window and the output
  (let ((window (svref st 9))
        (wpos (svref st 10))
        (out (rontolisp::%inflate-room st 1))
        (opos (svref st 26)))
    (setf (aref window wpos) b)
    (setf (svref st 10) (logand (+ wpos 1) 32767))
    (if (< (svref st 11) 32768) (setf (svref st 11) (+ (svref st 11) 1)))
    (setf (aref out opos) b)
    (setf (svref st 26) (+ opos 1))
    nil))

(defun rontolisp::%inflate-emit (st src start end)
  ;; SRC[START..END) into the window and the output, a stored block's octets
  (let* ((n (- end start))
         (out (rontolisp::%inflate-room st n))
         (opos (svref st 26))
         (window (svref st 9))
         (wpos (svref st 10))
         (from start))
    (replace out src :start1 opos :start2 start :end2 end)
    (setf (svref st 26) (+ opos n))
    (while (< from end)
      (let ((k (min (- end from) (- 32768 wpos))))
        (replace window src :start1 wpos :start2 from :end2 (+ from k))
        (setq wpos (logand (+ wpos k) 32767))
        (setq from (+ from k))))
    (setf (svref st 10) wpos)
    (setf (svref st 11) (min 32768 (+ (svref st 11) n)))
    nil))

(defun rontolisp::%inflate-copy (st limit)
  ;; the pending match copied while the output is under LIMIT
  (let ((len (svref st 16)) (dist (svref st 17)) (window (svref st 9)))
    (while (and (> len 0) (or (null limit) (< (svref st 26) limit)))
      (rontolisp::%inflate-put st
       (aref window (logand (- (svref st 10) dist) 32767)))
      (setq len (- len 1)))
    (setf (svref st 16) len)
    nil))

(defun rontolisp::%inflate-header (st)
  ;; a zlib or gzip header, one unit
  (if (= (svref st 0) 1)
      (let* ((cmf (rontolisp::%inflate-octet st))
             (flg (rontolisp::%inflate-octet st)))
        (cond ((/= (mod (+ (* cmf 256) flg) 31) 0)
               (rontolisp::%inflate-fail "incorrect header check"))
              ((/= (logand cmf 15) 8)
               (rontolisp::%inflate-fail "unknown compression method"))
              ((> (+ (ash cmf -4) 8) 15)
               (rontolisp::%inflate-fail "invalid window size")))
        (if (/= (logand flg 32) 0)
            (progn
              ;; a preset dictionary's id, after which java.util.zip reads nothing
              (dotimes (i 4) (rontolisp::%inflate-octet st))
              (setf (svref st 1) 6))
            (setf (svref st 1) 1)))
      (let ((start (svref st 3)) (b0 (rontolisp::%inflate-octet st)))
        (if (or (/= b0 #x1f) (/= (rontolisp::%inflate-octet st) #x8b))
            (rontolisp::%inflate-fail "Not in GZIP format"))
        (if (/= (rontolisp::%inflate-octet st) 8)
            (rontolisp::%inflate-fail "Unsupported compression method"))
        (let ((flg (rontolisp::%inflate-octet st)))
          (dotimes (i 6) (rontolisp::%inflate-octet st))
          (if (/= (logand flg 4) 0)
              (let ((m (rontolisp::%inflate-octet st)))
                (setq m (+ m (* 256 (rontolisp::%inflate-octet st))))
                (dotimes (i m) (rontolisp::%inflate-octet st))))
          (if (/= (logand flg 8) 0)
              (while (/= (rontolisp::%inflate-octet st) 0)))
          (if (/= (logand flg 16) 0)
              (while (/= (rontolisp::%inflate-octet st) 0)))
          (if (/= (logand flg 2) 0)
              (progn
                (rontolisp::%inflate-crc st 65535 65535 (svref st 2) start
                                         (svref st 3))
                (let ((v (logxor (svref st 18) 65535))
                      (got (rontolisp::%inflate-octet st)))
                  (setq got (+ got (* 256 (rontolisp::%inflate-octet st))))
                  (if (/= v got)
                      (rontolisp::%inflate-fail "Corrupt GZIP header")))))
          ;; a member: its check, size and window start afresh
          (setf (svref st 18) 65535)
          (setf (svref st 19) 65535)
          (setf (svref st 20) 0)
          (setf (svref st 21) 0)
          (setf (svref st 11) 0)
          (setf (svref st 12) nil)
          (setf (svref st 1) 1)))))

(defun rontolisp::%inflate-check (st)
  ;; the zlib Adler-32 or the gzip trailer, one unit
  (rontolisp::%inflate-sum st)
  (rontolisp::%inflate-align st)
  (let ((kind (svref st 0)))
    (cond ((= kind 0) (setf (svref st 1) 6))
          ((= kind 1)
           (let* ((b0 (rontolisp::%inflate-octet st))
                  (b1 (rontolisp::%inflate-octet st))
                  (b2 (rontolisp::%inflate-octet st))
                  (b3 (rontolisp::%inflate-octet st)))
             (if (or (/= (+ (* b0 256) b1) (svref st 19))
                     (/= (+ (* b2 256) b3) (svref st 18)))
                 (rontolisp::%inflate-fail "incorrect data check"))
             (setf (svref st 1) 6)))
          (t
           (let* ((c0 (rontolisp::%inflate-octet st))
                  (c1 (rontolisp::%inflate-octet st))
                  (c2 (rontolisp::%inflate-octet st))
                  (c3 (rontolisp::%inflate-octet st)))
             (if (or (/= (+ c0 (* 256 c1)) (logxor (svref st 18) 65535))
                     (/= (+ c2 (* 256 c3)) (logxor (svref st 19) 65535)))
                 (rontolisp::%inflate-fail "Corrupt GZIP trailer")))
           (let* ((s0 (rontolisp::%inflate-octet st))
                  (s1 (rontolisp::%inflate-octet st))
                  (s2 (rontolisp::%inflate-octet st))
                  (s3 (rontolisp::%inflate-octet st)))
             (if (or (/= (+ s0 (* 256 s1)) (svref st 20))
                     (/= (+ s2 (* 256 s3)) (svref st 21)))
                 (rontolisp::%inflate-fail "Corrupt GZIP trailer")))
           (setf (svref st 1) 5)))))

(defun rontolisp::%inflate-match (st len d)
  ;; the length LEN and the distance symbol D of a match: its distance read,
  ;; checked, and the copy made pending
  (if (or (< d 0) (> d 29)) (rontolisp::%inflate-fail "invalid distance code"))
  (let* ((tables (svref st 24))
         (dist
          (+ (svref (svref tables 2) d)
             (rontolisp::%inflate-bits st (svref (svref tables 3) d)))))
    (if (> dist (svref st 11))
        (rontolisp::%inflate-fail "invalid distance too far back"))
    (setf (svref st 16) len)
    (setf (svref st 17) dist)
    nil))

(defun rontolisp::%inflate-codes (st limit)
  ;; one unit of a Huffman block: a literal, a match (copied while the output
  ;; is under LIMIT) or the block's end
  (let ((sym (rontolisp::%inflate-decode st (svref st 13))))
    (cond ((< sym 0) (rontolisp::%inflate-fail "invalid literal/length code"))
          ((< sym 256) (rontolisp::%inflate-put st sym))
          ((= sym 256) (setf (svref st 1) (if (svref st 12) 4 1)))
          ((> sym 285) (rontolisp::%inflate-fail "invalid literal/length code"))
          (t
           (let* ((tables (svref st 24))
                  (i (- sym 257))
                  (len
                   (+ (svref (svref tables 0) i)
                    (rontolisp::%inflate-bits st (svref (svref tables 1) i)))))
             (rontolisp::%inflate-match st len
              (rontolisp::%inflate-decode st (svref st 14)))
             (rontolisp::%inflate-copy st limit))))
    nil))

(defun rontolisp::%inflate-fast (st)
  ;; zlib's inflate_fast: Huffman symbols decoded while at least ten octets of
  ;; input are left, which no symbol can run past, over the state read into
  ;; locals once; the end of the block, or too little input, ends it
  (let* ((in (svref st 2))
         (n (- (length in) 10))
         (pos (svref st 3))
         (hold (svref st 4))
         (bits (svref st 5))
         (window (svref st 9))
         (wpos (svref st 10))
         (have (svref st 11))
         (out (svref st 25))
         (opos (svref st 26))
         (lcode (svref st 13))
         (dcode (svref st 14))
         (ltab (svref lcode 2))
         (dtab (svref dcode 2))
         (tables (svref st 24))
         (lbase (svref tables 0))
         (lext (svref tables 1))
         (dbase (svref tables 2))
         (dext (svref tables 3))
         (fail nil))
    (while (and (null fail) (<= pos n))
      (while (< bits 16)
        (setq hold (logior hold (ash (aref in pos) bits)))
        (setq pos (+ pos 1))
        (setq bits (+ bits 8)))
      (let ((e (aref ltab (logand hold 511))))
        (if (= e 0) (setq e (rontolisp::%inflate-lookup lcode hold)))
        (if (< e 0)
            (setq fail "invalid literal/length code")
            (let ((sym (ash e -4)) (l (logand e 15)))
              (setq hold (ash hold (- l)))
              (setq bits (- bits l))
              (cond ((< sym 256)
                     (if (>= opos (length out))
                         (progn
                           (setf (svref st 26) opos)
                           (setq out (rontolisp::%inflate-room st 1))))
                     (setf (aref window wpos) sym)
                     (setq wpos (logand (+ wpos 1) 32767))
                     (setf (aref out opos) sym)
                     (setq opos (+ opos 1))
                     (if (< have 32768) (setq have (+ have 1))))
                    ((= sym 256)
                     (setf (svref st 1) (if (svref st 12) 4 1))
                     (setq n -1))
                    ((> sym 285) (setq fail "invalid literal/length code"))
                    (t
                     (let* ((i (- sym 257))
                            (x (svref lext i))
                            (len (svref lbase i)))
                       (if (> x 0)
                           (progn
                             (while (< bits 16)
                               (setq hold
                                     (logior hold (ash (aref in pos) bits)))
                               (setq pos (+ pos 1))
                               (setq bits (+ bits 8)))
                             (setq len (+ len (logand hold (- (ash 1 x) 1))))
                             (setq hold (ash hold (- x)))
                             (setq bits (- bits x))))
                       (while (< bits 16)
                         (setq hold (logior hold (ash (aref in pos) bits)))
                         (setq pos (+ pos 1))
                         (setq bits (+ bits 8)))
                       (let ((e2 (aref dtab (logand hold 511))))
                         (if (= e2 0)
                             (setq e2 (rontolisp::%inflate-lookup dcode hold)))
                         (if (or (< e2 0) (> (ash e2 -4) 29))
                             (setq fail "invalid distance code")
                             (let* ((d (ash e2 -4))
                                    (dl (logand e2 15))
                                    (dist (svref dbase d))
                                    (dx (svref dext d)))
                               (setq hold (ash hold (- dl)))
                               (setq bits (- bits dl))
                               (if (> dx 0)
                                   (progn
                                     (while (< bits 16)
                                       (setq hold
                                        (logior hold (ash (aref in pos) bits)))
                                       (setq pos (+ pos 1))
                                       (setq bits (+ bits 8)))
                                     (setq dist
                                      (+ dist (logand hold (- (ash 1 dx) 1))))
                                     (setq hold (ash hold (- dx)))
                                     (setq bits (- bits dx))))
                               (if (> dist have)
                                   (setq fail "invalid distance too far back")
                                   (progn
                                     (if (> (+ opos len) (length out))
                                         (progn
                                           (setf (svref st 26) opos)
                                           (setq out
                                            (rontolisp::%inflate-room st len))))
                                     (dotimes (k len)
                                       (let ((b
                                              (aref window
                                               (logand (- wpos dist) 32767))))
                                         (setf (aref window wpos) b)
                                         (setq wpos (logand (+ wpos 1) 32767))
                                         (setf (aref out opos) b)
                                         (setq opos (+ opos 1))))
                                     (setq have
                                           (min 32768 (+ have len)))))))))))))))
    (setf (svref st 3) pos)
    (setf (svref st 4) hold)
    (setf (svref st 5) bits)
    (setf (svref st 10) wpos)
    (setf (svref st 11) have)
    (setf (svref st 25) out)
    (setf (svref st 26) opos)
    (if fail (rontolisp::%inflate-fail fail))
    nil))

(defun rontolisp::%inflate-block (st)
  ;; a block header, one unit: the state changes only once it is whole
  (let ((last (= (rontolisp::%inflate-bits st 1) 1))
        (type (rontolisp::%inflate-bits st 2)))
    (cond ((= type 0)
           (rontolisp::%inflate-align st)
           (let* ((len (rontolisp::%inflate-bits st 16))
                  (nlen (rontolisp::%inflate-bits st 16)))
             (if (/= len (logxor nlen 65535))
                 (rontolisp::%inflate-fail "invalid stored block lengths"))
             (setf (svref st 15) len)
             (setf (svref st 12) last)
             (setf (svref st 1) 2)))
          ((= type 1)
           (let ((fixed (rontolisp::%inflate-fixed st)))
             (setf (svref st 13) (car fixed))
             (setf (svref st 14) (cdr fixed))
             (setf (svref st 12) last)
             (setf (svref st 1) 3)))
          ((= type 2)
           (let ((codes (rontolisp::%inflate-dynamic st)))
             (setf (svref st 13) (car codes))
             (setf (svref st 14) (cdr codes))
             (setf (svref st 12) last)
             (setf (svref st 1) 3)))
          (t (rontolisp::%inflate-fail "invalid block type")))))

(defun rontolisp::%inflate-stored (st limit)
  ;; what of a stored block has arrived, copied while the output is under
  ;; LIMIT; what is copied stays copied when the input runs out
  (let* ((in (svref st 2))
         (pos (svref st 3))
         (remaining (svref st 15))
         (k (min remaining (- (length in) pos))))
    (if limit (setq k (min k (- limit (svref st 26)))))
    (rontolisp::%inflate-emit st in pos (+ pos k))
    (setf (svref st 3) (+ pos k))
    (setf (svref st 15) (- remaining k))
    (cond ((= remaining k) (setf (svref st 1) (if (svref st 12) 4 1)))
          ((>= (+ pos k) (length in))
           (rontolisp::%inflate-mark st)
           (rontolisp::%inflate-underflow)))
    nil))

(defun rontolisp::%inflate-next (st)
  ;; the header of a gzip member after the first: none (the end), or one not
  ;; to be read, ends the stream, as GZIPInputStream ignores a malformed tail
  (if (>= (svref st 3) (length (svref st 2))) (rontolisp::%inflate-underflow))
  (let ((stop
         (catch 'rontolisp::%inflate-stop
           (rontolisp::%inflate-header st)
           nil)))
    (cond ((eq stop :underflow) (rontolisp::%inflate-underflow))
          (stop (setf (svref st 1) 6)))
    nil))

(defun rontolisp::%inflate-run (st limit)
  ;; decodes until the input runs out (an underflow), LIMIT octets are out or
  ;; the stream is done; a zlib or gzip header is read whatever LIMIT is, so a
  ;; LIMIT of 0 reads it and nothing more
  (let ((going t))
    (while going
      (rontolisp::%inflate-mark st)
      (let ((phase (svref st 1)))
        (cond
         ((and limit (>= (svref st 26) limit) (/= phase 0)) (setq going nil))
         ((> (svref st 16) 0) (rontolisp::%inflate-copy st limit))
         ((= phase 3)
          (if (and (null limit) (<= (+ (svref st 3) 10) (length (svref st 2))))
              (rontolisp::%inflate-fast st)
              (rontolisp::%inflate-codes st limit)))
         ((= phase 1) (rontolisp::%inflate-block st))
         ((= phase 2) (rontolisp::%inflate-stored st limit))
         ((= phase 0) (rontolisp::%inflate-header st))
         ((= phase 4) (rontolisp::%inflate-check st))
         ((= phase 5) (rontolisp::%inflate-next st))
         (t
          ;; done: whatever follows is not read
          (setf (svref st 3) (length (svref st 2)))
          (setq going nil)))))
    nil))

(defun rontolisp::%inflate-update (st octets limit)
  "The octets the compressed OCTETS, appended to what the decoder holds, make
   decodable -- at most LIMIT of them unless LIMIT is nil; 0 reads a zlib or
   gzip header and stops -- as one fresh (unsigned-byte 8) vector; for a
   malformed stream its message, a string, which every later update answers."
  (if (svref st 23)
      (svref st 23)
      (let ((in (svref st 2)) (pos (svref st 3)))
        (cond ((>= pos (length in)) (setf (svref st 2) octets))
              ((> (length octets) 0)
               (let ((joined
                      (make-array (+ (- (length in) pos) (length octets))
                                  :element-type '(unsigned-byte 8))))
                 (replace joined in :start2 pos)
                 (replace joined octets :start1 (- (length in) pos))
                 (setf (svref st 2) joined)))
              ((> pos 0) (setf (svref st 2) (subseq in pos))))
        (setf (svref st 3) 0)
        (setf (svref st 25)
              (make-array (max 1024 (* 4 (length (svref st 2))))
                          :element-type '(unsigned-byte 8)))
        (setf (svref st 26) 0)
        (setf (svref st 28) 0)
        (let ((stop
               (catch 'rontolisp::%inflate-stop
                 (rontolisp::%inflate-run st limit)
                 nil)))
          (cond ((stringp stop)
                 (setf (svref st 25) nil)
                 (setf (svref st 23) stop)
                 stop)
                (t
                 (if (eq stop :underflow)
                     (progn
                       (setf (svref st 3) (svref st 6))
                       (setf (svref st 4) (svref st 7))
                       (setf (svref st 5) (svref st 8))))
                 (rontolisp::%inflate-sum st)
                 (let ((out (subseq (svref st 25) 0 (svref st 26))))
                   (setf (svref st 25) nil)
                   out)))))))

(defun rontolisp::%inflate-finish (st)
  "Whether the compressed data given the decoder is whole: nil when it is, 1
   when a gzip header or trailer is cut short, 2 when the compressed data is."
  (let ((phase (svref st 1)))
    (cond ((or (= phase 6) (= phase 5)) nil)
          ((and (= (svref st 0) 2) (or (= phase 0) (= phase 4))) 1)
          (t 2))))
