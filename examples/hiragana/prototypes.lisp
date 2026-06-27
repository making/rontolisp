;;;; prototypes.lisp -- the reference glyphs (the "training alphabet"), one per
;;;; hiragana class.  Each glyph is sixteen rows of sixteen characters,
;;;; '#' = ink, '.' = blank.  This file is used only by the OFFLINE trainer
;;;; (interpreter/JVM), so it may use string/char operations the WASM inference
;;;; build never sees.
;;;;
;;;; The bitmaps are rendered from a real font ("Hiragino Maru Gothic ProN")
;;;; at 16x16 and binarized, so they actually look like あいうえお; the same
;;;; bitmaps are shown as references on the browser page (index.html's GLYPHS
;;;; must stay identical), so the user draws to match them.  Class order here
;;;; defines the output-unit order and must match *romaji* / *labels*.

(defparameter *romaji* (list "a" "i" "u" "e" "o"))

;; あ
(defparameter *glyph-a* (list
  "................"
  ".....##........."
  ".....##....##..."
  "..###########..."
  ".....##........."
  ".....##..##....."
  ".....#######...."
  "....###..#.##..."
  "...####.##..##.."
  "..##.##.#....##."
  ".##...###....##."
  ".##...##....##.."
  ".##..###....##.."
  "..######.####..."
  "........###....."
  "................"))

;; い
(defparameter *glyph-i* (list
  "................"
  ".#.............."
  ".##............."
  ".##.......##...."
  ".##........##..."
  ".##.........#..."
  ".##.........##.."
  ".##.........##.."
  ".##..........##."
  ".##...##.....##."
  ".##...##.....##."
  "..##..##.....##."
  "..#####........."
  "...###.........."
  "....#..........."
  "................"))

;; う
(defparameter *glyph-u* (list
  "................"
  "....#####......."
  ".....########..."
  "................"
  "................"
  "....########...."
  "..#######.###..."
  "............##.."
  "............##.."
  "............##.."
  "............##.."
  "...........##..."
  ".........###...."
  ".....######....."
  ".....###........"
  "................"))

;; え
(defparameter *glyph-e* (list
  "................"
  "....####........"
  "....########...."
  "................"
  "................"
  "..#########....."
  "..###....##....."
  "........##......"
  "......###......."
  ".....####......."
  "....######......"
  "...###...#......"
  "..##.....#......"
  ".##......######."
  ".#........####.."
  "................"))

;; お
(defparameter *glyph-o* (list
  "................"
  ".....#.........."
  ".....#.....#...."
  ".....#....###..."
  ".#########..##.."
  "..####.......##."
  ".....#.........."
  ".....#...#......"
  "....#########..."
  "...###......##.."
  "..##.#......##.."
  ".##..#......##.."
  ".##..#..#...##.."
  ".#####..#####..."
  "..####...###...."
  "................"))

(defparameter *glyphs* (list *glyph-a* *glyph-i* *glyph-u* *glyph-e* *glyph-o*))

;; Convert one glyph (list of equal-length rows) into a flat list of 0.0 / 1.0,
;; row-major.  Size-agnostic: it reads the grid width off the row strings.
(defun glyph->list (rows)
  (let ((acc nil))
    (dolist (row rows)
      (dotimes (j (length row))
        (setq acc (cons (if (char= (char row j) #\#) 1.0 0.0) acc))))
    (reverse acc)))
