;;;; image-size.lisp -- the intrinsic size of an image file, read from its header.
;;;;
;;;; The PPTX emitter has to know the aspect ratio to show an image whole or to crop it
;;;; to a box, and lispresen never decodes an image. PNG, JPEG, GIF, WebP and the SVG
;;;; subset are read here; a format whose size cannot be read is reported and placed as
;;;; the box it was given.

(in-package :lispresen)

(defun u16-be (o i) (logior (ash (aref o i) 8) (aref o (+ i 1))))
(defun u32-be (o i)
  (logior (ash (aref o i) 24) (ash (aref o (+ i 1)) 16)
          (ash (aref o (+ i 2)) 8) (aref o (+ i 3))))
(defun u16-le (o i) (logior (aref o i) (ash (aref o (+ i 1)) 8)))
(defun u24-le (o i)
  (logior (aref o i) (ash (aref o (+ i 1)) 8) (ash (aref o (+ i 2)) 16)))

(defun bytes-at-p (o i octets)
  (and (<= (+ i (length octets)) (length o))
       (loop for b in octets for k from i always (= (aref o k) b))))

(defun png-size (o)
  (when (and (>= (length o) 24) (bytes-at-p o 0 '(137 80 78 71 13 10 26 10))
             (bytes-at-p o 12 '(73 72 68 82)))
    (let ((w (u32-be o 16)) (h (u32-be o 20)))
      (when (and (> w 0) (> h 0)) (values w h)))))

(defun jpeg-size (o)
  "The size of a JPEG, from its first frame header (SOF); nil when none is found."
  (when (and (>= (length o) 4) (= (aref o 0) #xFF) (= (aref o 1) #xD8))
    (let ((i 2) (n (length o)))
      (loop while (< (+ i 9) n)
            do
              (when (/= (aref o i) #xFF) (return))
              (let ((marker (aref o (+ i 1))))
                (cond ((= marker #xFF) (incf i)) ; a fill byte
                      ((member marker '(#xD8 #xD9)) (incf i 2))
                      ((= marker #xDA) (return)) ; no frame header before the scan
                      (t
                       (let ((length (u16-be o (+ i 2))))
                         (when (< length 2) (return))
                         (when (and (>= marker #xC0) (<= marker #xCF)
                                    (not (member marker '(#xC4 #xC8 #xCC))))
                           (return-from jpeg-size
                             (values (u16-be o (+ i 7)) (u16-be o (+ i 5)))))
                         (incf i (+ 2 length)))))))
      nil)))

(defun gif-size (o)
  (when (and (>= (length o) 10)
             (or (bytes-at-p o 0 '(71 73 70 56 55 97))
                 (bytes-at-p o 0 '(71 73 70 56 57 97))))
    (let ((w (u16-le o 6)) (h (u16-le o 8)))
      (when (and (> w 0) (> h 0)) (values w h)))))

(defun webp-size (o)
  (when (and (>= (length o) 16) (bytes-at-p o 0 '(82 73 70 70))
             (bytes-at-p o 8 '(87 69 66 80)))
    (let ((chunk (subseq o 12 (min (length o) 16))))
      (cond ((equalp chunk "VP8X")
             (when (>= (length o) 30)
               (let ((w (1+ (u24-le o 24))) (h (1+ (u24-le o 27))))
                 (values w h))))
            ((equalp chunk "VP8L")
             (when (and (>= (length o) 25) (= (aref o 20) #x2F))
               (let* ((b0 (aref o 21)) (b1 (aref o 22))
                      (b2 (aref o 23)) (b3 (aref o 24))
                      (w (1+ (logior b0 (ash (logand b1 #x3F) 8))))
                      (h (1+ (logior (ash b1 -6) (ash b2 2)
                                     (ash (logand b3 #x0F) 10)))))
                 (values w h))))
            ((equalp chunk "VP8 ")
             (when (and (>= (length o) 30)
                        (bytes-at-p o 23 '(157 1 42)))
               (let ((w (logand (u16-le o 26) #x3FFF))
                     (h (logand (u16-le o 28) #x3FFF)))
                 (values w h))))))))

(defun svg-attr-value (text name)
  "The value of the attribute NAME of the first tag of TEXT that has one, or nil."
  (let ((start 0) (key (concatenate 'string name "=")))
    (loop
      (let ((i (search key text :start2 start)))
        (unless i (return nil))
        (if (and (> i 0)
                 (or (char= (char text (1- i)) #\-)
                     (alphanumericp (char text (1- i)))))
            (setf start (1+ i))
            (let ((j (+ i (length key))))
              (when (and (< j (length text)) (find (char text j) "\"'"))
                (let* ((q (char text j))
                       (end (position q text :start (1+ j))))
                  (return (and end (subseq text (1+ j) end)))))))))))

(defun svg-size (o)
  "The size an SVG declares, from its view box or width and height; nil when it declares
none. A gradient or anything else outside the drawing subset is irrelevant here."
  (let ((text (utf-8-string o)))
    (when text
      (let* ((view (svg-attr-value text "viewBox"))
             (vw (and view (svg-numbers view)))
             (ws (svg-attr-value text "width"))
             (hs (svg-attr-value text "height"))
             (w (and ws (parse-integer ws :junk-allowed t)))
             (h (and hs (parse-integer hs :junk-allowed t))))
        (cond ((and vw (= (length vw) 4) (> (third vw) 0) (> (fourth vw) 0))
               (values (third vw) (fourth vw)))
              ((and w h (> w 0) (> h 0)) (values w h)))))))

(defun image-size (octets)
  "The intrinsic size of OCTETS as (values WIDTH HEIGHT), or nil when it is unknown.
The units of an SVG's view box are its own; only their ratio is used."
  (when octets
    (dolist (reader (list #'png-size #'jpeg-size #'gif-size #'webp-size #'svg-size))
      (multiple-value-bind (w h) (funcall reader octets)
        (when (and w h) (return-from image-size (values w h)))))))
