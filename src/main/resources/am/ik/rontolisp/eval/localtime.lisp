;;;; localtime.lisp -- %local-time-offset on the WASI backends: the local time zone of
;;;; decode-universal-time, encode-universal-time and get-decoded-time.
;;;;
;;;; WASI has no time zone interface, so the module reads the zone the way the C
;;;; library does, through the environment and the file system the host grants it
;;;; (spliced by eval/LocalTimeLibrary into a Preview 1 module or a --component; a
;;;; --native output asks its runner instead, and --no-wasi answers UTC):
;;;;
;;;;  * TZ unset: the TZif file /etc/localtime.
;;;;  * TZ empty: UTC.
;;;;  * TZ ":name" or "name": the TZif file <TZDIR or /usr/share/zoneinfo>/name, or the
;;;;    file itself when the name is an absolute path; failing that, the name read as
;;;;    a POSIX rule string ("JST-9", "EST5EDT,M3.2.0,M11.1.0").
;;;;  * Nothing readable: UTC.
;;;;
;;;; A TZif file (RFC 8536) is its version 2+ block -- 64-bit transition times -- and,
;;;; past the last transition, the POSIX rule of its footer, which is what keeps a slim
;;;; file (few transitions, a rule for the rest) right in any future year; a version 1
;;;; file is its only block and its last type. Leap-second records are skipped. The
;;;; zone is read ONCE per run and kept.
;;;;
;;;; The answer is the primitive's encoding: the total offset east of UTC in seconds,
;;;; doubled, plus 1 under daylight saving time (the TZif type's isdst; under a rule,
;;;; between its two transitions). LocalTimeLibraryTest pins it to java.time.

(defvar rontolisp::*%tz-zone* nil)

(defun %local-time-offset (unix)
  (unless rontolisp::*%tz-zone*
    (setq rontolisp::*%tz-zone*
          (or (rontolisp::%tz-zone (%host-getenv "TZ"))
              (rontolisp::%tz-rule-zone (list 0)))))
  (rontolisp::%tz-offset rontolisp::*%tz-zone* unix))

;; The zone a TZ value names, or nil when it names none that can be read.
(defun rontolisp::%tz-zone (tz)
  (cond ((null tz) (rontolisp::%tz-read-file "/etc/localtime"))
        ((= (length tz) 0) nil)
        (t (let ((name (if (char= (char tz 0) #\:) (subseq tz 1) tz)))
             (and (> (length name) 0)
                  (or (rontolisp::%tz-read-file
                       (if (char= (char name 0) #\/)
                           name
                           (concatenate 'string
                            (or (%host-getenv "TZDIR") "/usr/share/zoneinfo")
                            "/" name)))
                      (let ((rule (rontolisp::%tz-posix name)))
                        (and rule (rontolisp::%tz-rule-zone rule)))))))))

;; A zone is #(transition-times type-indices types rule): the times in Unix seconds,
;; one type index per time, each type an encoded offset, the rule a parsed POSIX rule
;; for the times past the last transition (or nil).
(defun rontolisp::%tz-rule-zone (rule) (vector (vector) (vector) (vector) rule))

(defun rontolisp::%tz-offset (zone unix)
  (let* ((times (svref zone 0)) (n (length times)) (rule (svref zone 3)))
    (cond ((and rule (or (= n 0) (>= unix (svref times (- n 1)))))
           (rontolisp::%tz-rule-offset rule unix))
          ((or (= n 0) (< unix (svref times 0))) (svref (svref zone 2) 0))
          (t (let ((lo 0) (hi (- n 1)))
               ;; The last transition at or before the instant.
               (do ()
                   ((>= lo hi))
                 (let ((mid (floor (+ lo hi 1) 2)))
                   (if (<= (svref times mid) unix)
                       (setq lo mid)
                       (setq hi (- mid 1)))))
               (svref (svref zone 2) (svref (svref zone 1) lo)))))))

(defun rontolisp::%tz-read-file (path)
  (let ((in
         (open path :element-type '(unsigned-byte 8) :if-does-not-exist nil)))
    (when in
      (let* ((octets (make-array 65536 :element-type '(unsigned-byte 8)))
             (got (read-sequence octets in)))
        (close in)
        (rontolisp::%tz-parse-tzif octets got)))))

(defun rontolisp::%tz-u32 (b i)
  (+ (* (aref b i) 16777216) (* (aref b (+ i 1)) 65536) (* (aref b (+ i 2)) 256)
     (aref b (+ i 3))))

(defun rontolisp::%tz-s32 (b i)
  (let ((v (rontolisp::%tz-u32 b i)))
    (if (>= v 2147483648) (- v 4294967296) v)))

(defun rontolisp::%tz-magic-p (b n at)
  (and (<= (+ at 44) n) (= (aref b at) 84) (= (aref b (+ at 1)) 90)
       (= (aref b (+ at 2)) 105) (= (aref b (+ at 3)) 102)))

;; The size of the data block behind the header at AT, for times WIDTH octets wide.
(defun rontolisp::%tz-block-size (b at width)
  (let ((timecnt (rontolisp::%tz-u32 b (+ at 32))))
    (+ (* timecnt (+ width 1)) (* (rontolisp::%tz-u32 b (+ at 36)) 6)
       (rontolisp::%tz-u32 b (+ at 40))
       (* (rontolisp::%tz-u32 b (+ at 28)) (+ width 4))
       (rontolisp::%tz-u32 b (+ at 24)) (rontolisp::%tz-u32 b (+ at 20)))))

(defun rontolisp::%tz-parse-tzif (b n)
  (when (rontolisp::%tz-magic-p b n 0)
    (if (>= (aref b 4) 50)
        (let ((at (+ 44 (rontolisp::%tz-block-size b 0 4))))
          (and (rontolisp::%tz-magic-p b n at)
               (rontolisp::%tz-parse-block b n at 8)))
        (rontolisp::%tz-parse-block b n 0 4))))

(defun rontolisp::%tz-parse-block (b n at width)
  (let* ((timecnt (rontolisp::%tz-u32 b (+ at 32)))
         (typecnt (rontolisp::%tz-u32 b (+ at 36)))
         (data (+ at 44))
         (index-at (+ data (* timecnt width)))
         (types-at (+ index-at timecnt))
         (end (+ data (rontolisp::%tz-block-size b at width))))
    (when (and (> typecnt 0) (<= end n))
      (let ((times (make-array timecnt))
            (indices (make-array timecnt))
            (types (make-array typecnt)))
        (dotimes (i timecnt)
          (setf (svref times i)
                (if (= width 8)
                    (+ (* (rontolisp::%tz-s32 b (+ data (* i 8))) 4294967296)
                       (rontolisp::%tz-u32 b (+ data (* i 8) 4)))
                    (rontolisp::%tz-s32 b (+ data (* i 4)))))
          (setf (svref indices i) (min (aref b (+ index-at i)) (- typecnt 1))))
        (dotimes (i typecnt)
          (setf (svref types i)
                (+ (* 2 (rontolisp::%tz-s32 b (+ types-at (* i 6))))
                   (if (= (aref b (+ types-at (* i 6) 4)) 0) 0 1))))
        (vector times indices types
                (and (= width 8) (< end n) (= (aref b end) 10)
                     (rontolisp::%tz-posix
                      (rontolisp::%tz-footer b n (+ end 1)))))))))

;; The footer's rule text: the octets from START to the next newline.
(defun rontolisp::%tz-footer (b n start)
  (let ((stop start))
    (do ()
        ((or (>= stop n) (= (aref b stop) 10)))
      (setq stop (+ stop 1)))
    (let ((text (make-string (- stop start))))
      (dotimes (i (- stop start))
        (setf (char text i) (code-char (aref b (+ start i)))))
      text)))

;;; POSIX TZ rules: std offset [dst [offset] [,start[/time],end[/time]]]. An offset is
;;; WEST-positive there. A rule is (std) or (std dst start-day start-time end-day
;;; end-time), std and dst encoded like a TZif type, each day a (:m month week wday),
;;; (:j day) or (:n day) and each time seconds after local midnight.

(defun rontolisp::%tz-posix (s)
  (let* ((n (length s))
         (std-end (rontolisp::%tz-name-end s 0))
         (std (and std-end (rontolisp::%tz-hms s std-end))))
    (when std
      (let ((std-enc (* -2 (car std))))
        (if (>= (cdr std) n)
            (list std-enc)
            (let ((dst-end (rontolisp::%tz-name-end s (cdr std))))
              (when dst-end
                (let* ((dst
                        (and (< dst-end n) (char/= (char s dst-end) #\,)
                             (rontolisp::%tz-hms s dst-end)))
                       (dst-enc
                        (+ 1 (* -2 (if dst (car dst) (- (car std) 3600)))))
                       (pos (if dst (cdr dst) dst-end)))
                  (if (>= pos n)
                      ;; No rule given: the US rule, as the C library assumes.
                      (list std-enc dst-enc (list :m 3 2 0) 7200
                            (list :m 11 1 0) 7200)
                      (let* ((start (rontolisp::%tz-transition s pos))
                             (end
                              (and start
                                   (rontolisp::%tz-transition s (cdr start)))))
                        (and end (= (cdr end) n)
                             (list std-enc dst-enc (car (car start))
                                   (cdr (car start)) (car (car end))
                                   (cdr (car end))))))))))))))

;; The end of the zone abbreviation at I: three or more letters, or <...>.
(defun rontolisp::%tz-name-end (s i)
  (let ((n (length s)))
    (if (and (< i n) (char= (char s i) #\<))
        (let ((gt (position #\> s :start i))) (and gt (+ gt 1)))
        (let ((j i))
          (do ()
              ((or (>= j n) (not (alpha-char-p (char s j)))))
            (setq j (+ j 1)))
          (and (>= (- j i) 3) j)))))

;; The decimal digits at I as (value . end), or nil.
(defun rontolisp::%tz-digits (s i)
  (let ((n (length s)) (v 0) (j i))
    (do ()
        ((or (>= j n) (not (digit-char-p (char s j)))))
      (setq v (+ (* v 10) (digit-char-p (char s j))))
      (setq j (+ j 1)))
    (and (> j i) (cons v j))))

;; [+-]hh[:mm[:ss]] at I as (seconds . end), or nil.
(defun rontolisp::%tz-hms (s i)
  (let* ((n (length s))
         (sign (if (and (< i n) (char= (char s i) #\-)) -1 1))
         (at
          (if (and (< i n) (or (char= (char s i) #\-) (char= (char s i) #\+)))
              (+ i 1)
              i))
         (h (rontolisp::%tz-digits s at)))
    (when h
      (let ((secs (* 3600 (car h))) (pos (cdr h)))
        (dolist (unit '(60 1))
          (when (and (< pos n) (char= (char s pos) #\:))
            (let ((d (rontolisp::%tz-digits s (+ pos 1))))
              (when d
                (setq secs (+ secs (* unit (car d))))
                (setq pos (cdr d))))))
        (cons (* sign secs) pos)))))

;; ",day[/time]" at I as ((day . seconds) . end), or nil.
(defun rontolisp::%tz-transition (s i)
  (let* ((n (length s))
         (d
          (and (< i n) (char= (char s i) #\,) (rontolisp::%tz-day s (+ i 1)))))
    (when d
      (if (and (< (cdr d) n) (char= (char s (cdr d)) #\/))
          (let ((tm (rontolisp::%tz-hms s (+ (cdr d) 1))))
            (and tm (cons (cons (car d) (car tm)) (cdr tm))))
          (cons (cons (car d) 7200) (cdr d))))))

(defun rontolisp::%tz-day (s i)
  (let ((n (length s)))
    (cond ((>= i n) nil)
          ((char= (char s i) #\M)
           (let* ((m (rontolisp::%tz-digits s (+ i 1)))
                  (w
                   (and m (< (cdr m) n) (char= (char s (cdr m)) #\.)
                        (rontolisp::%tz-digits s (+ (cdr m) 1))))
                  (d
                   (and w (< (cdr w) n) (char= (char s (cdr w)) #\.)
                        (rontolisp::%tz-digits s (+ (cdr w) 1)))))
             (and d (<= 1 (car m) 12) (<= 1 (car w) 5) (<= (car d) 6)
                  (cons (list :m (car m) (car w) (car d)) (cdr d)))))
          ((char= (char s i) #\J)
           (let ((j (rontolisp::%tz-digits s (+ i 1))))
             (and j (<= 1 (car j) 365) (cons (list :j (car j)) (cdr j)))))
          (t (let ((j (rontolisp::%tz-digits s i)))
               (and j (<= (car j) 365) (cons (list :n (car j)) (cdr j))))))))

;; The rule's offset at a Unix instant. The transitions are computed for the year the
;; instant falls in by standard time: the start is given in standard time, the end in
;; daylight saving time; a start after the end is a southern-hemisphere rule.
(defun rontolisp::%tz-rule-offset (rule unix)
  (if (null (cdr rule))
      (car rule)
      (let* ((std (nth 0 rule))
             (dst (nth 1 rule))
             (std-east (floor std 2))
             (year (rontolisp::%tz-year (+ unix std-east)))
             (start
              (- (+ (rontolisp::%tz-rule-day (nth 2 rule) year) (nth 3 rule))
                 std-east))
             (end
              (- (+ (rontolisp::%tz-rule-day (nth 4 rule) year) (nth 5 rule))
                 (floor dst 2))))
        (if (< start end)
            (if (and (<= start unix) (< unix end)) dst std)
            (if (and (<= end unix) (< unix start)) std dst)))))

;; Local midnight of a rule's day in YEAR, in seconds since 1970-01-01.
(defun rontolisp::%tz-rule-day (spec year)
  (let ((jan1 (rontolisp::%tz-days year 1 1)))
    (* 86400
       (case (car spec)
         (:j (+ jan1 (nth 1 spec) -1
              (if (and (>= (nth 1 spec) 60) (rontolisp::%tz-leap-p year)) 1 0)))
         (:n (+ jan1 (nth 1 spec)))
         (t (let* ((month (nth 1 spec))
                   (day1 (rontolisp::%tz-days year month 1))
                   (day
                    (+ (mod (- (nth 3 spec) (mod (+ day1 4) 7)) 7)
                       (* 7 (- (nth 2 spec) 1)))))
              ;; Week 5 is the last such weekday of the month.
              (+ day1
                 (if (>= day (rontolisp::%tz-month-length year month))
                     (- day 7)
                     day))))))))

(defun rontolisp::%tz-leap-p (year)
  (and (= (mod year 4) 0) (or (/= (mod year 100) 0) (= (mod year 400) 0))))

(defun rontolisp::%tz-month-length (year month)
  (case month
    (2 (if (rontolisp::%tz-leap-p year) 29 28))
    ((4 6 9 11) 30)
    (t 31)))

;; Days from 1970-01-01 to a civil date (the prelude codec's era arithmetic).
(defun rontolisp::%tz-days (year month date)
  (let* ((y (if (<= month 2) (- year 1) year))
         (era (floor y 400))
         (yoe (- y (* era 400)))
         (mp (mod (+ month 9) 12))
         (doy (+ (floor (+ (* 153 mp) 2) 5) (- date 1)))
         (doe (+ (* yoe 365) (floor yoe 4) (- (floor yoe 100)) doy)))
    (+ (* era 146097) doe -719468)))

;; The civil year of a count of seconds since 1970-01-01.
(defun rontolisp::%tz-year (seconds)
  (let* ((z (+ (floor seconds 86400) 719468))
         (era (floor z 146097))
         (doe (- z (* era 146097)))
         (yoe
          (floor
           (- (+ doe (floor doe 36524)) (+ (floor doe 1460) (floor doe 146096)))
           365))
         (doy (- doe (+ (* 365 yoe) (floor yoe 4) (- (floor yoe 100)))))
         (mp (floor (+ (* 5 doy) 2) 153)))
    (+ yoe (* era 400) (if (< mp 10) 0 1))))
