;;;; png.lisp -- a PNG writer: CRC-32, Adler-32 and deflate, in integer arithmetic.
;;;;
;;;; The page image is written as an 8-bit palette image, one byte a pixel. Deflate takes
;;;; one fixed-Huffman block whose matches are the two that pay on a drawn slide: a run
;;;; of the byte before (distance 1, a flat fill) and the bytes of the row above (a
;;;; distance of one row, where the row repeats it). The choice is greedy and depends only
;;;; on the bytes, so every backend writes the same file.

(in-package :lispresen)

(defun octet-buffer ()
  (make-array 0 :element-type '(unsigned-byte 8) :adjustable t :fill-pointer 0))

(defun put-u32 (n out)
  "N as four big-endian bytes."
  (vector-push-extend (ldb (byte 8 24) n) out)
  (vector-push-extend (ldb (byte 8 16) n) out)
  (vector-push-extend (ldb (byte 8 8) n) out)
  (vector-push-extend (ldb (byte 8 0) n) out))

(defvar *crc-table* nil)

(defun crc-table ()
  (or *crc-table*
      (let ((table (make-array 256)))
        (dotimes (n 256)
          (let ((c n))
            (dotimes (k 8)
              (setf c
                    (if (logbitp 0 c)
                        (logxor #xEDB88320 (ash c -1))
                        (ash c -1))))
            (setf (svref table n) c)))
        (setf *crc-table* table))))

(defun crc32 (octets &optional (start 0) (end (length octets)))
  "The CRC-32 of OCTETS from START below END, as PNG and zlib define it."
  (let ((table (crc-table)) (c #xFFFFFFFF))
    (loop for i from start below end
          do (setf c
                   (logxor (svref table (logand (logxor c (aref octets i)) 255))
                           (ash c -8))))
    (logxor c #xFFFFFFFF)))

(defun adler32 (octets)
  "The Adler-32 of OCTETS. The sums are reduced every 5552 bytes, the most that keep them
below 2^32."
  (let ((a 1) (b 0) (n (length octets)) (i 0))
    (loop while (< i n)
          do (let ((end (min n (+ i 5552))))
               (loop while (< i end)
                     do (setf a (+ a (aref octets i)) b (+ b a))
                        (incf i))
               (setf a (mod a 65521) b (mod b 65521))))
    (+ (* b 65536) a)))

;;; --- deflate -------------------------------------------------------------------------

(defparameter *length-bases*
  #(3 4 5 6 7 8 9 10 11 13 15 17 19 23 27 31 35 43 51 59 67 83 99 115 131 163 195 227
    258))

(defparameter *length-extra*
  #(0 0 0 0 0 0 0 0 1 1 1 1 2 2 2 2 3 3 3 3 4 4 4 4 5 5 5 5 0))

(defparameter *distance-bases*
  #(1 2 3 4 5 7 9 13 17 25 33 49 65 97 129 193 257 385 513 769 1025 1537 2049 3073
    4097 6145 8193 12289 16385 24577))

(defparameter *distance-extra*
  #(0 0 0 0 1 1 2 2 3 3 4 4 5 5 6 6 7 7 8 8 9 9 10 10 11 11 12 12 13 13))

(defun base-code (bases n)
  "The index of the last of BASES at most N."
  (let ((k 0))
    (loop while (and (< (1+ k) (length bases)) (<= (svref bases (1+ k)) n))
          do (incf k))
    k))

(defun deflate (octets &optional (row 0))
  "OCTETS as a zlib stream of one fixed-Huffman deflate block, matching runs (distance 1)
and, when ROW is positive, the bytes ROW before."
  (let ((out (octet-buffer)) (acc 0) (nacc 0) (n (length octets)) (i 0))
    (labels ((bits (value count)
               ;; Least significant bit first.
               (setf acc (logior acc (ash value nacc)))
               (incf nacc count)
               (loop while (>= nacc 8)
                     do (vector-push-extend (logand acc 255) out)
                        (setf acc (ash acc -8))
                        (decf nacc 8)))
             (code (value count)
               ;; A Huffman code, most significant bit first.
               (let ((r 0))
                 (dotimes (k count) (setf r (logior (ash r 1) (ldb (byte 1 k) value))))
                 (bits r count)))
             (symbol (s)
               (cond ((< s 144) (code (+ #x30 s) 8))
                     ((< s 256) (code (+ #x190 (- s 144)) 9))
                     ((< s 280) (code (- s 256) 7))
                     (t (code (+ #xC0 (- s 280)) 8))))
             (match-length (d)
               (let ((len 0) (limit (min 258 (- n i))))
                 (loop while (and (< len limit)
                                  (= (aref octets (+ i len))
                                     (aref octets (+ i len (- d)))))
                       do (incf len))
                 len)))
      ;; zlib header: deflate, 32K window, no preset dictionary.
      (vector-push-extend #x78 out)
      (vector-push-extend #x01 out)
      (bits 1 1)
      (bits 1 2)
      (loop while (< i n)
            do (let* ((run (if (>= i 1) (match-length 1) 0))
                      (up (if (and (< run 258) (> row 1) (>= i row))
                              (match-length row)
                              0))
                      (len (max run up))
                      (d (if (>= run up) 1 row)))
                 (cond ((< len 3)
                        (symbol (aref octets i))
                        (incf i))
                       (t
                        (let ((lc (base-code *length-bases* len))
                              (dc (base-code *distance-bases* d)))
                          (symbol (+ 257 lc))
                          (bits (- len (svref *length-bases* lc))
                                (svref *length-extra* lc))
                          (code dc 5)
                          (bits (- d (svref *distance-bases* dc))
                                (svref *distance-extra* dc)))
                        (incf i len)))))
      (symbol 256)
      (when (> nacc 0) (bits 0 (- 8 nacc)))
      (put-u32 (adler32 octets) out)
      out)))

;;; --- PNG -----------------------------------------------------------------------------

(defun put-chunk (type data out)
  (put-u32 (length data) out)
  (let ((start (fill-pointer out)))
    (loop for c across type do (vector-push-extend (char-code c) out))
    (loop for b across data do (vector-push-extend b out))
    (put-u32 (crc32 out start (fill-pointer out)) out)))

(defun png-octets (width height palette pixels)
  "The PNG file of a WIDTH x HEIGHT image of palette indices PIXELS (row by row), PALETTE
a vector of #xRRGGBB colors."
  (let ((out (octet-buffer))
        (raw (make-array (* height (1+ width)) :element-type '(unsigned-byte 8)
                                               :initial-element 0))
        (header (octet-buffer))
        (colors (octet-buffer)))
    (dolist (b '(137 80 78 71 13 10 26 10)) (vector-push-extend b out))
    (put-u32 width header)
    (put-u32 height header)
    ;; 8 bits a pixel, palette color, deflate, adaptive filtering, no interlace.
    (dolist (b '(8 3 0 0 0)) (vector-push-extend b header))
    (put-chunk "IHDR" header out)
    (loop for c across palette
          do (vector-push-extend (ldb (byte 8 16) c) colors)
             (vector-push-extend (ldb (byte 8 8) c) colors)
             (vector-push-extend (ldb (byte 8 0) c) colors))
    (put-chunk "PLTE" colors out)
    ;; Each row is filter type 0 and the row's indices.
    (dotimes (y height)
      (let ((base (* y (1+ width))) (from (* y width)))
        (dotimes (x width)
          (setf (aref raw (+ base 1 x)) (aref pixels (+ from x))))))
    (put-chunk "IDAT" (deflate raw (1+ width)) out)
    (put-chunk "IEND" (octet-buffer) out)
    out))
