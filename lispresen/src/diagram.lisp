;;;; diagram.lisp -- the drawings of (flow ...), the charts (bars ...), (columns ...),
;;;; (lines ...) and (pie ...), and (cells ...).
;;;;
;;;; A drawing is a list of shapes in canvas px from its top left corner. Every shape is a
;;;; filled area, never a stroke, so every coordinate is an integer and the box a browser
;;;; reports for a shape is the box the callouts avoid here. Every measure of a diagram
;;;; derives from its label size, which steps down as a table's does.

(in-package :lispresen)

(defparameter *diagram-steps* '(48 44 40 36))
(defparameter *diagram-floor* 40)
(defparameter *dg-line*
  4
  "The thickness of every line of a diagram: borders, dividers, arrows.")
(defparameter *box-radius* 12)

(defun dg-lh (s) (ceiling (* s 14) 10))
(defun dg-pad-x (s) (floor (* s 3) 5))
(defun dg-pad-y (s) (floor s 3))
(defun flow-gap (s)
  "Between the boxes of a row."
  (ceiling (* s 3) 2))
(defun flow-row-gap (s)
  "Between rows, where the arrow turns back."
  (* s 2))
(defun arrow-margin (s)
  "Between an arrow and the box it leaves or enters."
  (floor s 6))
(defun head-length (s) (floor (* s 2) 5))
(defun head-half (s) (floor s 4))
(defun dot-radius (s) (floor s 8))

;;; WIDTH and HEIGHT in px; SHAPES, painted in order, are
;;;   (:rect X Y W H CLASS RX)   a rectangle: CLASS dg-b a border, dg-f the face inside
;;;                              it, dg-e part of an arrow, dg-sN a mark of a chart's
;;;                              Nth series, dg-rN the ring around its marker, dg-g a
;;;                              gridline, dg-z the zero line, dg-bg a gap between marks
;;;   (:poly CLASS X1 Y1 ...)    a polygon: an arrowhead, the slash of nil, a marker, a
;;;                              stretch of a line chart's line
;;;   (:dot X Y R [CLASS])       where a pointer starts (dg-e), or a circle of a chart
;;;   (:slice CLASS BOX R RI LARGE X1 Y1 X2 Y2 X3 Y3 X4 Y4)
;;;                              a slice of a pie: its edge from X1, Y1 to X2, Y2, R px
;;;                              from the center, LARGE 1 past half a turn, then X3, Y3
;;;                              and X4, Y4 on the hole's edge RI px from the center, or
;;;                              the center for RI 0; BOX the rectangle it covers
;;;   (:box X Y W H I PAD)       the label of a flow's Ith box, PAD px above its lines
;;;   (:edge-label X Y W H J)    the Jth arrow label of a flow
;;;   (:category X Y W H J ALIGN) the label of a chart's Jth row
;;;   (:legend X Y W H I)        the name of a chart's Ith series
;;;   (:value X Y W H TEXT ALIGN I)
;;;                              a value of the Ith series, nil for a stack's total
;;;   (:tick X Y W H TEXT ALIGN) a tick's label on a chart's scale
;;;   (:atom X Y W H TEXT)       an atom of cells, as printed
;;; TARGETS lists, for a chart, the box a callout pointing at a part of it points at:
;;; ((:mark I J) . BOX) the mark of the Ith series' value in the Jth row, ((:category J)
;;; . BOX) the Jth row, ((:series I) . BOX) the Ith series.
(defstruct drawing width height shapes targets)

(defun shape-layer (shape)
  (case (car shape)
    (:rect (cond ((equal (sixth shape) "dg-b") 0)
                 ((equal (sixth shape) "dg-f") 1)
                 (t 2)))
    ((:poly :dot :slice) 2)
    (t 3)))

(defun shape-box (shape)
  "The rectangle SHAPE covers, as (LEFT TOP RIGHT BOTTOM)."
  (case (car shape)
    (:poly (let ((xs (loop for (x) on (cddr shape) by #'cddr collect x))
                 (ys (loop for (nil y) on (cddr shape) by #'cddr collect y)))
             (list (reduce #'min xs) (reduce #'min ys) (reduce #'max xs)
                   (reduce #'max ys))))
    (:dot (destructuring-bind (x y r &optional class) (cdr shape)
            (declare (ignore class))
            (list (- x r) (- y r) (+ x r) (+ y r))))
    (:slice (copy-list (third shape)))
    (t (destructuring-bind (x y w h) (subseq shape 1 5)
         (list x y (+ x w) (+ y h))))))

(defun shift-shape (shape dx dy)
  (flet ((points (list)
           (loop for (x y) on list by #'cddr
                 collect (+ x dx)
                 collect (+ y dy))))
    (case (car shape)
      (:poly (list* :poly (second shape) (points (cddr shape))))
      (:slice (destructuring-bind (class box r ri large &rest ps) (cdr shape)
                (list* :slice class (points box) r ri large (points ps))))
      (t (list* (first shape) (+ (second shape) dx) (+ (third shape) dy)
                (cdddr shape))))))

(defun finish-drawing (shapes left width height)
  "The drawing of SHAPES moved right by -LEFT, painted borders first and labels last."
  (make-drawing :width width
                :height height
                :shapes (stable-sort (mapcar (lambda (shape)
                                               (shift-shape shape (- left) 0))
                                             shapes) #'<
                                     :key #'shape-layer)))

(defun arrow-right (x1 x2 y s)
  "The shapes of an arrow along Y from X1 to its tip at X2."
  (let ((a (head-length s)) (b (head-half s)) (half (floor *dg-line* 2)))
    (list (list :rect x1 (- y half) (max 0 (- x2 a x1)) *dg-line* "dg-e" 0)
          (list :poly "dg-e" (- x2 a) (- y b) x2 y (- x2 a) (+ y b)))))

(defun arrow-down (x y1 y2 s)
  "The shapes of an arrow along X from Y1 down to its tip at Y2."
  (let ((a (head-length s)) (b (head-half s)) (half (floor *dg-line* 2)))
    (list (list :rect (- x half) y1 *dg-line* (max 0 (- y2 a y1)) "dg-e" 0)
          (list :poly "dg-e" (- x b) (- y2 a) x y2 (+ x b) (- y2 a)))))

(defun framed-box (x y w h radius)
  "A border and the face inside it."
  (let ((l *dg-line*))
    (list (list :rect x y w h "dg-b" radius)
          (list :rect (+ x l) (+ y l) (- w (* 2 l)) (- h (* 2 l)) "dg-f"
                (max 0 (- radius l))))))

;;; --- flow ----------------------------------------------------------------------------
;;;
;;; A flow that is one chain going right is set in rows, as text is; any other flow is a
;;; layered drawing: every arrow points one way along the layers, which stand left to
;;; right (or top to bottom for :down), and each arrow runs out of a box's far side,
;;; along a track in the gap to the next layer, and into the next box's near side. All of
;;; it is integer arithmetic, so every backend draws the same picture.

(defun flow-text-width (run s cap)
  "The width a flow label's text takes at S px: its own, wrapped at its phrases when wider
than CAP px."
  (max (run-min-width run s) (min (run-full-width run s) cap)))

;;; How a flow is drawn at any size, worked out once from its graph. CHAIN lists the boxes
;;; in order, LABELS the label of each arrow between them (an index or nil), when the flow
;;; is one chain going right. Otherwise the flow is layered: KINDS holds each node, (:box
;;; I), (:label J) for the label of an arrow, or (:dummy) where an arrow crosses a layer;
;;; LAYERS lists the nodes of each layer in order; LINKS are (FROM TO ARROW) between
;;; nodes of neighboring layers, ARROW true when the link ends at a box.
(defstruct flow-plan chain labels kinds layers links)

(defun flow-chain (n edges)
  "The N boxes in order when EDGES join them into one chain, else nil."
  (when (= (length edges) (1- n))
    (let ((next (make-array n :initial-element nil))
          (entered (make-array n :initial-element nil))
          (order nil))
      (dolist (e edges)
        (when (or (aref next (first e)) (aref entered (second e)))
          (return-from flow-chain nil))
        (setf (aref next (first e)) (second e)
              (aref entered (second e)) t))
      (do ((v (position nil entered) (aref next v))) ((null v))
        (push v order))
      (and (= (length order) n) (nreverse order)))))

(defun topological-order (m preds succs)
  "The M nodes in an order where each comes after its PREDS, the lowest ready index first."
  (let ((left (make-array m :initial-element 0)) (ready nil) (order nil))
    (dotimes (v m)
      (setf (aref left v) (length (aref preds v)))
      (when (zerop (aref left v)) (push v ready)))
    (setf ready (nreverse ready))
    (loop while ready
          do
            (let ((v (pop ready)))
              (push v order)
              (dolist (w (aref succs v))
                (when (zerop (decf (aref left w)))
                  (setf ready (sort (cons w ready) #'<))))))
    (nreverse order)))

(defun flow-layers (m preds succs)
  "The layer of each of M nodes: each after all its predecessors (longest path), a chain
hanging from a source moved up to its target, then each node moved within the layers its
neighbors leave it towards the side with more of them, until none moves: shorter arrows,
and a side input next to its target."
  (let ((order (topological-order m preds succs))
        (layer (make-array m :initial-element 0)))
    (dolist (v order)
      (dolist (p (aref preds v))
        (setf (aref layer v) (max (aref layer v) (1+ (aref layer p))))))
    ;; A chain hanging from a source (a side input, an arrow's label after it) moves as
    ;; far on as its end allows, last node first.
    (let ((hanging (make-array m :initial-element nil)))
      (dolist (v order)
        (let ((ps (aref preds v)))
          (setf (aref hanging v)
                (and (aref succs v)
                     (or (null ps)
                         (and (null (cdr ps)) (aref hanging (car ps))
                              (null (cdr (aref succs (car ps))))))))))
      (dolist (v (reverse order))
        (when (aref hanging v)
          (setf (aref layer v)
                (1- (reduce #'min (mapcar (lambda (w) (aref layer w)) (aref succs v))))))))
    (loop
      (let ((moved nil))
        (dolist (v order)
          (let* ((in (length (aref preds v)))
                 (out (length (aref succs v)))
                 (target
                  (cond ((< in out)
                         (1- (reduce #'min (mapcar (lambda (w) (aref layer w))
                                                   (aref succs v)))))
                        ((> in out)
                         (if (aref preds v)
                             (1+ (reduce #'max (mapcar (lambda (p) (aref layer p))
                                                       (aref preds v))))
                             0))
                        (t (aref layer v)))))
            (when (/= target (aref layer v))
              (setf (aref layer v) target
                    moved t))))
        (unless moved (return))))
    layer))

(defun link-crossings (links pos)
  "How many pairs of LINKS between two layers cross, at the positions POS."
  (let ((c 0))
    (loop for tail on links
          do
            (let ((e (car tail)))
              (dolist (f (cdr tail))
                (when (< (* (- (aref pos (first e)) (aref pos (first f)))
                            (- (aref pos (second e)) (aref pos (second f))))
                         0)
                  (incf c)))))
    c))

(defun order-layers (layers gap-links preds succs pos)
  "LAYERS (a vector of node lists) reordered for few crossings: barycenter sweeps down
and up, keeping the best order seen, then swaps of neighbors while one removes a crossing.
POS is each node's index in its layer, kept up to date."
  (let* ((n (length layers))
         (best (copy-seq layers))
         (best-c 0))
    (labels ((place (k)
               (let ((i 0))
                 (dolist (v (aref layers k)) (setf (aref pos v) i) (incf i))))
             (total ()
               (let ((c 0))
                 (dotimes (k (1- n)) (incf c (link-crossings (aref gap-links k) pos)))
                 c))
             (around (k)
               (+ (if (> k 0) (link-crossings (aref gap-links (1- k)) pos) 0)
                  (if (< k (1- n)) (link-crossings (aref gap-links k) pos) 0)))
             (barycenter (v nbrs)
               (let ((ns (aref nbrs v)))
                 (if ns
                     (/ (reduce #'+ (mapcar (lambda (u) (aref pos u)) ns)) (length ns))
                     (aref pos v))))
             (resort (k nbrs)
               (setf (aref layers k)
                     (stable-sort (copy-list (aref layers k)) #'<
                                  :key (lambda (v) (barycenter v nbrs))))
               (place k)))
      (dotimes (k n) (place k))
      (setf best-c (total))
      (dotimes (pass 12)
        (when (> best-c 0)
          (if (evenp pass)
              (loop for k from 1 below n do (resort k preds))
              (loop for k from (- n 2) downto 0 do (resort k succs)))
          (let ((c (total)))
            (when (< c best-c) (setf best (copy-seq layers) best-c c)))))
      (setf layers best)
      (dotimes (k n) (place k))
      (loop repeat 8
            do
              (let ((improved nil))
                (dotimes (k n)
                  (loop for tail on (aref layers k)
                        while (cdr tail)
                        do
                          (let ((before (around k)) (a (first tail)) (b (second tail)))
                            (rotatef (aref pos a) (aref pos b))
                            (if (< (around k) before)
                                (setf (first tail) b (second tail) a improved t)
                                (rotatef (aref pos a) (aref pos b))))))
                (unless improved (return))))
      layers)))

(defun flow-plan-of (b)
  "The plan of the flow B, worked out on first use."
  (let ((g (diagram-datum b)))
    (or (flow-graph-plan g)
        (setf (flow-graph-plan g) (make-plan g (length (diagram-labels b)))))))

(defun make-plan (g n)
  "The plan of the flow graph G of N boxes: a chain going right wraps in rows, any other
flow is layered."
  (let ((chain (and (eq (flow-graph-direction g) :right)
                    (flow-chain n (flow-graph-edges g)))))
    (if chain
        (make-flow-plan :chain chain
                        :labels (loop for (v w) on chain
                                      while w
                                      collect (third (find-if (lambda (e)
                                                                (and (= (first e) v)
                                                                     (= (second e) w)))
                                                              (flow-graph-edges g)))))
        (layered-plan g n))))

(defun layered-plan (g n)
  "The layered plan of the flow graph G of N boxes. An arrow's label is a node of its own
between the arrow's ends, so the layers make room for it as for a box."
  (let* ((m (+ n (length (flow-graph-edge-labels g))))
         (edges nil))
    (dolist (e (flow-graph-edges g))
      (destructuring-bind (from to label) e
        (if label
            (progn
              (push (list from (+ n label)) edges)
              (push (list (+ n label) to) edges))
            (push (list from to) edges))))
    (setf edges (nreverse edges))
    (let ((preds (make-array m :initial-element nil))
          (succs (make-array m :initial-element nil)))
      (dolist (e (reverse edges))
        (push (first e) (aref preds (second e)))
        (push (second e) (aref succs (first e))))
      (let* ((layer (flow-layers m preds succs))
             (depth (1+ (reduce #'max (coerce layer 'list))))
             (kinds (loop for v from 0 below m
                          collect (if (< v n) (list :box v) (list :label (- v n)))))
             (layer-of (coerce layer 'list))
             (dummies nil)
             (links nil)
             (next m))
        ;; An arrow across several layers passes a dummy node in each layer between.
        (dolist (e edges)
          (destructuring-bind (u v) e
            (let ((prev u))
              (loop for k from (1+ (aref layer u)) below (aref layer v)
                    do
                      (push (list prev next nil) links)
                      (push k dummies)
                      (setf prev next)
                      (incf next))
              (push (list prev v (< v n)) links))))
        (setf links (nreverse links)
              kinds (append kinds (make-list (length dummies) :initial-element '(:dummy)))
              layer-of (append layer-of (reverse dummies)))
        (let* ((count next)
               (layers (make-array depth :initial-element nil))
               (gap-links (make-array (max 1 depth) :initial-element nil))
               (lpreds (make-array count :initial-element nil))
               (lsuccs (make-array count :initial-element nil))
               (pos (make-array count :initial-element 0)))
          (loop for v from (1- count) downto 0
                for k = (nth v layer-of)
                do (push v (aref layers k)))
          (dolist (l (reverse links))
            (push l (aref gap-links (nth (first l) layer-of)))
            (push (first l) (aref lpreds (second l)))
            (push (second l) (aref lsuccs (first l))))
          (make-flow-plan :kinds (coerce kinds 'simple-vector)
                          :layers (coerce (order-layers layers gap-links lpreds lsuccs pos)
                                          'list)
                          :links links))))))

;;; --- flow: a chain in rows -----------------------------------------------------------

(defun greedy-rows (widths gaps limit)
  "How many boxes each row takes when boxes WIDTHS px wide, the Ith GAPS px from the next,
fill rows at most LIMIT px wide in turn; a box wider than LIMIT takes a row of its own."
  (let ((rows nil) (count 0) (used 0))
    (loop for w in widths
          for gap in (cons 0 gaps)
          do
            (if (and (> count 0) (> (+ used gap w) limit))
                (progn
                  (push count rows)
                  (setf count 1 used w))
                (setf used (if (> count 0) (+ used gap w) w) count (1+ count))))
    (nreverse (cons count rows))))

(defun flow-rows (widths gaps avail)
  "How many boxes each row takes: as few rows as fit AVAIL px, as even in width as that
allows (the narrowest limit that needs no more rows)."
  (let* ((n (length (greedy-rows widths gaps avail)))
         (lo (reduce #'max widths))
         (hi (max lo avail)))
    (loop while (< lo hi)
          do
            (let ((mid (floor (+ lo hi) 2)))
              (if (<= (length (greedy-rows widths gaps mid)) n)
                  (setf hi mid)
                  (setf lo (1+ mid)))))
    (greedy-rows widths gaps lo)))

(defun cut-lines (shapes boxes)
  "SHAPES with every line (a dg-e rectangle) cut where it passes under one of BOXES, (LEFT
TOP RIGHT BOTTOM) each: an arrow's label interrupts its line."
  (labels ((line (x y w h) (and (> w 0) (> h 0) (list (list :rect x y w h "dg-e" 0))))
           (cut (shape l top r bottom)
             (destructuring-bind (x y w h) (subseq shape 1 5)
               (cond ((or (>= x r) (<= (+ x w) l) (>= y bottom) (<= (+ y h) top))
                      (list shape))
                     ((>= w h)
                      (append (line x y (- l x) h) (line r y (- (+ x w) r) h)))
                     (t
                      (append (line x y w (- top y))
                              (line x bottom w (- (+ y h) bottom))))))))
    (dolist (box boxes)
      (setf shapes
            (loop for shape in shapes
                  append (if (and (eq (car shape) :rect) (equal (sixth shape) "dg-e"))
                             (apply #'cut shape box)
                             (list shape))))))
  shapes)

(defun label-shape (x y w h j m)
  "The label J of an arrow, its text W x H px from X, Y, and the box M px larger all round
that its line leaves free."
  (values (list :edge-label x y w h j)
          (list (- x m) (- y m) (+ x w m) (+ y h m))))

(defun rows-drawing (b s avail)
  "The chain flow B at label size S in AVAIL px: its boxes left to right, one height, in
as few rows as fit, each row's last box joined to the next row's first by an arrow that
turns back below it. An arrow's label stands on its line, which makes room for it."
  (let* ((plan (flow-plan-of b))
         (chain (flow-plan-chain plan))
         (runs (diagram-runs b))
         (eruns (flow-graph-edge-runs (diagram-datum b)))
         (px (dg-pad-x s))
         (lh (dg-lh s))
         (gap (flow-gap s))
         (m (arrow-margin s))
         (head (head-length s))
         (half (floor *dg-line* 2))
         (cap (- (floor (- avail (* 2 gap)) 3) (* 2 px)))
         (texts (mapcar (lambda (i) (flow-text-width (nth i runs) s cap)) chain))
         (lines (mapcar (lambda (i w) (run-lines (nth i runs) s w)) chain texts))
         ;; Each arrow's label, (J WIDTH HEIGHT), or nil.
         (arrows (mapcar (lambda (j)
                           (and j
                                (let* ((run (nth j eruns))
                                       (w (flow-text-width run s cap)))
                                  (list j w (* lh (run-lines run s w))))))
                         (flow-plan-labels plan)))
         (h (reduce #'max
                    (cons (+ (* (reduce #'max lines) lh) (* 2 (dg-pad-y s)))
                          (mapcar (lambda (l) (if l (+ (third l) (* 2 m)) 0)) arrows))))
         ;; No box is narrower than it is tall.
         (widths (mapcar (lambda (w) (max h (+ w (* 2 px)))) texts))
         (gaps (mapcar (lambda (l)
                         (if l (max gap (+ (second l) (* 4 m) head (* 2 s))) gap))
                       arrows))
         (shapes nil)
         (cuts nil)
         (boxes nil)
         (width 0)
         (i 0)
         (y 0)
         (turn nil))
    (flet ((add (list) (setf shapes (revappend list shapes)))
           (label (x y l)
             (multiple-value-bind (shape cut)
                 (label-shape x y (second l) (third l) (first l) m)
               (push shape shapes)
               (push cut cuts))))
      (dolist (count (flow-rows widths gaps avail))
        (let ((x 0)
              (row-gap (flow-row-gap s))
              (turn-label (and (> i 0) (nth (1- i) arrows))))
          (when turn-label
            (setf row-gap (max row-gap (+ (third turn-label) (* 4 m) (* 2 head) (* 2 s)))))
          (when turn
            ;; Down from the last box of the row above, back along the gap, down into
            ;; this row's first box, which stands lower when the arrow carries a label.
            (incf y row-gap))
          (dotimes (j count)
            (let ((w (nth i widths)) (cy (+ y (floor h 2))))
              (cond ((> j 0)
                     (let ((g (nth (1- i) gaps)) (l (nth (1- i) arrows)))
                       (add (arrow-right (+ x m) (- (+ x g) m) cy s))
                       (when l
                         (label (+ x m (floor (- g (* 2 m) head (second l)) 2))
                                (- cy (floor (third l) 2)) l))
                       (incf x g)))
                    (turn
                     (destructuring-bind (bx by) turn
                       (let ((mid (+ by (floor row-gap 2)))
                             (fx (+ x (floor w 2))))
                         (add
                          (list (list :rect (- bx half) (+ by m) *dg-line*
                                      (- (+ mid half) by m) "dg-e" 0)
                                (list :rect (- (min bx fx) half) (- mid half)
                                      (+ (abs (- bx fx)) *dg-line*) *dg-line*
                                      "dg-e" 0)))
                         (add (arrow-down fx (- mid half) (- y m) s))
                         (when turn-label
                           (label (- (floor (+ bx fx) 2) (floor (second turn-label) 2))
                                  (- mid (floor (third turn-label) 2)) turn-label))))))
              (add (framed-box x y w h *box-radius*))
              (push (list :box x y w h (nth i chain)
                          (floor (- h (* (nth i lines) lh)) 2))
                    boxes)
              (incf x w)
              (incf i)))
          (setf width (max width x)
                turn (list (- x (floor (nth (1- i) widths) 2)) (+ y h)))
          (incf y h)))
      (finish-drawing (append (cut-lines (nreverse shapes) cuts)
                              (sort boxes #'< :key #'sixth))
                      0 width y))))

;;; --- flow: layers -------------------------------------------------------------------

(defun track-pitch (s)
  "Between the tracks of one gap, where arrows run across it."
  (floor (* s 3) 4))

(defun node-pad (kind s)
  "The room a node keeps from its neighbors in a layer: a box s/2, a label or a dummy s/4."
  (if (eq (car kind) :box) (floor s 2) (floor s 4)))

(defun link-weight (a b single)
  "How strongly a link pulls its ends into line: more between dummies, so an arrow across
layers runs straight, and most for a SINGLE link, the only one out of its start and into
its end, which is then made straight."
  (cond (single 64)
        ((and (eq (car a) :dummy) (eq (car b) :dummy)) 8)
        ((or (eq (car a) :dummy) (eq (car b) :dummy)) 2)
        (t 1)))

(defun stack-offsets (nodes br pad)
  "The least distance from the center of the first of NODES to each one's, at breadths BR
and pads PAD."
  (let ((o 0) (out nil))
    (loop for (a b) on nodes
          do
            (push o out)
            (when b
              (incf o (+ (- (aref br a) (floor (aref br a) 2)) (floor (aref br b) 2)
                         (aref pad a) (aref pad b)))))
    (nreverse out)))

(defun pool-centers (targets weights offsets)
  "The integer centers nearest TARGETS (in the weighted least squares sense) that keep
each the OFFSETS apart: pool adjacent violators over the targets less the offsets."
  (let ((blocks nil))
    (loop for target in targets
          for w in weights
          for o in offsets
          do
            (let ((blk (list (* w (- target o)) w 1)))
              (loop while (and blocks
                               (> (/ (first (car blocks)) (second (car blocks)))
                                  (/ (first blk) (second blk))))
                    do
                      (let ((top (pop blocks)))
                        (setf blk (mapcar #'+ top blk))))
              (push blk blocks)))
    (let ((rs nil))
      (dolist (blk (reverse blocks))
        (let ((r (floor (first blk) (second blk))))
          (dotimes (k (third blk)) (push r rs))))
      (mapcar #'+ (nreverse rs) offsets))))

(defun cross-positions (layers links kinds br pad near)
  "The center of each node across the layers: each layer packed and centered, then moved
in turn, ten sweeps, to the weighted mean of its neighbors' centers as nearly as its
spacing allows, so a box sits between the boxes it joins and arrows run straight."
  (let* ((n (length kinds))
         (q (make-array n :initial-element 0))
         (nbrs (make-array n :initial-element nil))
         (offsets (mapcar (lambda (nodes) (stack-offsets nodes br pad)) layers))
         (extents (loop for nodes in layers
                        for os in offsets
                        collect (let ((a (car nodes)) (z (car (last nodes))))
                                  (+ (car (last os)) (floor (aref br a) 2)
                                     (- (aref br z) (floor (aref br z) 2))))))
         (widest (reduce #'max extents)))
    (dolist (l (reverse links))
      (let ((w (link-weight (aref kinds (first l)) (aref kinds (second l))
                            (single-link-p l links))))
        (push (cons (second l) w) (aref nbrs (first l)))
        (push (cons (first l) w) (aref nbrs (second l)))))
    (loop for nodes in layers
          for os in offsets
          for e in extents
          do
            (let ((top (+ (floor (- widest e) 2) (floor (aref br (car nodes)) 2))))
              (loop for v in nodes
                    for o in os
                    do (setf (aref q v) (+ top o)))))
    (dotimes (sweep 10)
      (loop for nodes in (if (evenp sweep) layers (reverse layers))
            for os in (if (evenp sweep) offsets (reverse offsets))
            do
              (let ((targets nil) (weights nil))
                (dolist (v nodes)
                  (let ((sw 0) (sq 0))
                    (dolist (nb (aref nbrs v))
                      (incf sw (cdr nb))
                      (incf sq (* (cdr nb) (aref q (car nb)))))
                    (if (zerop sw)
                        (progn (push (aref q v) targets) (push 1 weights))
                        (progn (push (/ sq sw) targets) (push sw weights)))))
                (loop for v in nodes
                      for c in (pool-centers (nreverse targets) (nreverse weights) os)
                      do (setf (aref q v) c)))))
    ;; A link less than NEAR px out of line, which would read as a slip, is put in line,
    ;; its start moved to its end or its end to its start, where the neighbors in that
    ;; layer leave room; then so is every single link, the last first, so that a chain
    ;; lines up with its end.
    (let ((prev (make-array n :initial-element nil))
          (next (make-array n :initial-element nil)))
      (loop for nodes in layers
            for os in offsets
            do
              (loop for (a v) on nodes
                    for (oa ov) on os
                    while v
                    do
                      (setf (aref next a) (cons v (- ov oa))
                            (aref prev v) (cons a (- ov oa)))))
      (flet ((fits (v target)
               (let ((a (aref prev v)) (b (aref next v)))
                 (and (or (null a) (>= (- target (cdr a)) (aref q (car a))))
                      (or (null b) (<= (+ target (cdr b)) (aref q (car b))))))))
        (dolist (single '(nil t t))
          (dolist (l (if single (reverse links) links))
            (when (if single
                      (single-link-p l links)
                      (< (abs (- (aref q (first l)) (aref q (second l)))) near))
              (destructuring-bind (u v) (subseq l 0 2)
                (cond ((= (aref q u) (aref q v)))
                      ((fits u (aref q v)) (setf (aref q u) (aref q v)))
                      ((fits v (aref q u)) (setf (aref q v) (aref q u))))))))))
    (let ((low (loop for v from 0 below n minimize (- (aref q v) (floor (aref br v) 2)))))
      (dotimes (v n) (decf (aref q v) low)))
    q))

(defun single-link-p (l links)
  "True when the link L is the only one out of its start and the only one into its end."
  (and (= (count (first l) links :key #'first) 1)
       (= (count (second l) links :key #'second) 1)))

;;; A run of arrows through one gap: from the nodes LEFTS of the layer before it, along
;;; one track, into the nodes RIGHTS of the layer after it, the box of each with ARROW
;;; (V . ARROW); LO and HI the span of its track, TRACK the track's number from 1.
(defstruct lane lefts rights lo hi track)

(defun gap-lanes (links q)
  "The lanes of the LINKS through one gap: the arrows out of a node with several
targets share its lane, as do the arrows into a node from nodes with one target each."
  (let ((out (lambda (u) (count u links :key #'first))) (lanes nil))
    (dolist (l links)
      (destructuring-bind (u v arrow) l
        (let* ((key (if (>= (funcall out u) 2) (list :from u) (list :to v)))
               (entry (assoc key lanes :test #'equal)))
          (unless entry
            (setf entry (cons key (make-lane)))
            (push entry lanes))
          (let ((lane (cdr entry)))
            (pushnew u (lane-lefts lane))
            (unless (assoc v (lane-rights lane))
              (push (cons v arrow) (lane-rights lane)))))))
    (let ((out nil))
      (dolist (entry lanes)
        (let* ((lane (cdr entry))
               (qs (append (mapcar (lambda (u) (aref q u)) (lane-lefts lane))
                           (mapcar (lambda (r) (aref q (car r))) (lane-rights lane)))))
          (setf (lane-lefts lane) (reverse (lane-lefts lane))
                (lane-rights lane) (reverse (lane-rights lane))
                (lane-lo lane) (reduce #'min qs)
                (lane-hi lane) (reduce #'max qs))
          (push lane out)))
      out)))

(defun lane-cost (a b q)
  "What putting lane A's track before lane B's costs: a crossing for each run of one
across the other's track, and far more for a run along a run of the other, which would
join arrows that do not meet."
  (let ((c 0) (a-in (mapcar (lambda (r) (aref q (car r))) (lane-rights a))))
    (dolist (u (lane-lefts b))
      (let ((y (aref q u)))
        (cond ((member y a-in) (incf c 1000))
              ((< (lane-lo a) y (lane-hi a)) (incf c)))))
    (dolist (y a-in)
      (when (< (lane-lo b) y (lane-hi b)) (incf c)))
    c))

(defun order-lanes (lanes q)
  "LANES in track order: each inserted where it costs least, then neighbors swapped while
that costs less."
  (let ((order nil))
    (dolist (g (stable-sort (copy-list lanes) #'<
                            :key (lambda (g) (+ (lane-lo g) (lane-hi g)))))
      (let ((best 0) (best-cost nil))
        (loop for i from 0 to (length order)
              do
                (let ((cost 0))
                  (loop for h in order
                        for j from 0
                        do (incf cost (if (< j i) (lane-cost h g q) (lane-cost g h q))))
                  (when (or (null best-cost) (<= cost best-cost))
                    (setf best i best-cost cost))))
        (setf order (append (subseq order 0 best) (list g) (nthcdr best order)))))
    (loop repeat (length order)
          do
            (let ((swapped nil))
              (loop for tail on order
                    while (cdr tail)
                    do
                      (let ((a (first tail)) (b (second tail)))
                        (when (< (lane-cost b a q) (lane-cost a b q))
                          (setf (first tail) b (second tail) a swapped t))))
              (unless swapped (return))))
    order))

(defun assign-tracks (lanes q)
  "Numbers the tracks of LANES that need one, those not straight across: in their order,
each on the first track past every earlier lane its span meets. Returns the tracks used."
  (let ((placed nil) (top 0))
    (dolist (g (order-lanes (remove-if (lambda (g) (= (lane-lo g) (lane-hi g))) lanes) q))
      (let ((track 1))
        (dolist (h placed)
          (when (and (<= (lane-lo h) (lane-hi g)) (<= (lane-lo g) (lane-hi h)))
            (setf track (max track (1+ (lane-track h))))))
        (setf (lane-track g) track
              top (max top track))
        (push g placed)))
    top))

(defun layered-drawing (b s avail)
  "The layered flow B at label size S in AVAIL px."
  (let* ((g (diagram-datum b))
         (plan (flow-plan-of b))
         (down (eq (flow-graph-direction g) :down))
         (kinds (flow-plan-kinds plan))
         (layers (flow-plan-layers plan))
         (links (flow-plan-links plan))
         (n (length kinds))
         (runs (diagram-runs b))
         (eruns (flow-graph-edge-runs g))
         (px (dg-pad-x s))
         (lh (dg-lh s))
         (m (arrow-margin s))
         (head (head-length s))
         (half (floor *dg-line* 2))
         (pitch (track-pitch s))
         (sizes
          ;; The text width and height of each box and label.
          (let* ((wide (loop for nodes in layers
                             maximize (loop for v in nodes
                                            count (not (eq (car (aref kinds v)) :dummy)))))
                 (share (if down wide (length layers)))
                 (cap (- (floor (- avail (* (1- share) (if down s (flow-gap s)))) share)
                         (* 2 px))))
            (map 'vector
                 (lambda (kind)
                   (let ((run (case (car kind)
                                (:box (nth (second kind) runs))
                                (:label (nth (second kind) eruns)))))
                     (and run
                          (let ((w (flow-text-width run s cap)))
                            (cons w (* lh (run-lines run s w)))))))
                 kinds)))
         (h (+ (* 2 (dg-pad-y s))
               (loop for v from 0 below n
                     when (eq (car (aref kinds v)) :box)
                       maximize (cdr (aref sizes v)))))
         (len (make-array n :initial-element 0))
         (br (make-array n :initial-element *dg-line*))
         (pad (make-array n :initial-element 0)))
    (dotimes (v n)
      (let ((kind (aref kinds v)) (size (aref sizes v)))
        (setf (aref pad v) (node-pad kind s))
        (case (car kind)
          (:box (let ((w (max h (+ (car size) (* 2 px)))))
                  (setf (aref len v) (if down h w) (aref br v) (if down w h))))
          (:label (setf (aref len v) (+ (if down (cdr size) (car size)) (* 2 m))
                        (aref br v) (if down (car size) (cdr size)))))))
    (let* ((q (cross-positions layers links kinds br pad (floor s 2)))
           (depth (length layers))
           (layer-len (mapcar (lambda (nodes)
                                (reduce #'max (mapcar (lambda (v) (aref len v)) nodes)))
                              layers))
           (gap-lanes (loop for nodes in layers
                            repeat (1- depth)
                            collect (gap-lanes (remove-if-not
                                                (lambda (l) (member (first l) nodes))
                                                links)
                                               q)))
           ;; Each gap's tracks and width.
           (gaps (mapcar (lambda (lanes)
                           (let ((tracks (assign-tracks lanes q)))
                             (cons tracks
                                   (max (flow-gap s)
                                        (if (zerop tracks)
                                            0
                                            (+ (* 2 m) head (* (1+ tracks) pitch)))))))
                         gap-lanes))
           (starts (let ((p 0) (out nil))
                     (loop for l in layer-len
                           for gp in (append gaps (list nil))
                           do (push p out) (when gp (incf p (+ l (cdr gp)))))
                     (nreverse out)))
           (extent (+ (car (last starts)) (car (last layer-len))))
           (breadth (loop for v from 0 below n
                          maximize (+ (- (aref q v) (floor (aref br v) 2)) (aref br v))))
           (layer-of (make-array n :initial-element 0))
           (start (make-array n :initial-element 0))
           (shapes nil)
           (segments nil)
           (cuts nil)
           (boxes nil))
      (loop for nodes in layers
            for k from 0
            do (dolist (v nodes)
                 (setf (aref layer-of v) k
                       (aref start v) (+ (nth k starts)
                                         (floor (- (nth k layer-len) (aref len v)) 2)))))
      (labels ((rect (p q lp lq)
                 (if down
                     (list :rect q p lq lp "dg-e" 0)
                     (list :rect p q lp lq "dg-e" 0)))
               (line (p1 p2 at) (push (list (min p1 p2) at (max p1 p2) at) segments))
               (run (at q1 q2) (push (list at (min q1 q2) at (max q1 q2)) segments))
               (xy (p q) (if down (list q p) (list p q)))
               (tip (p at)
                 (let ((b (head-half s)))
                   (push (append (list :poly "dg-e") (xy (- p head) (- at b)) (xy p at)
                                 (xy (- p head) (+ at b)))
                         shapes)))
               (box-p (v) (eq (car (aref kinds v)) :box))
               (exit (v)
                 (if (box-p v)
                     (+ (aref start v) (aref len v) m)
                     (let ((k (aref layer-of v))) (+ (nth k starts) (nth k layer-len)))))
               (entry (v arrow)
                 (cond (arrow (- (aref start v) m head))
                       ((box-p v) (- (aref start v) m))
                       (t (nth (aref layer-of v) starts)))))
        ;; Boxes, labels, and the line through each label and dummy.
        (dotimes (v n)
          (let ((kind (aref kinds v)) (p (aref start v)) (c (aref q v))
                (k (aref layer-of v)) (b (aref br v)) (l (aref len v)))
            (case (car kind)
              (:box
               (destructuring-bind (x y) (xy p (- c (floor b 2)))
                 (let ((w (if down b l)) (bh (if down l b)))
                   (setf shapes (revappend (framed-box x y w bh *box-radius*) shapes))
                   (push (list :box x y w bh (second kind)
                               (floor (- h (cdr (aref sizes v))) 2))
                         boxes))))
              (t
               (line (nth k starts) (+ (nth k starts) (nth k layer-len)) c)
               (when (eq (car kind) :label)
                 (let ((size (aref sizes v)))
                   (destructuring-bind (x y)
                       (if down
                           (list (- c (floor (car size) 2)) (+ p m))
                           (list (+ p m) (- c (floor (cdr size) 2))))
                     (multiple-value-bind (shape cut)
                         (label-shape x y (car size) (cdr size) (second kind) m)
                       (push shape shapes)
                       (push cut cuts)))))))))
        ;; The arrows of each gap, along their lanes.
        (loop for lanes in gap-lanes
              for (tracks . width) in gaps
              for k from 0
              do
                (let* ((from (+ (nth k starts) (nth k layer-len)))
                       (step (floor (- width (* 2 m) head) (1+ tracks)))
                       (tipped nil))
                  (dolist (lane lanes)
                    (let ((at (and (lane-track lane)
                                   (+ from m (* (lane-track lane) step)))))
                      (dolist (u (lane-lefts lane))
                        (if at
                            (line (exit u) at (aref q u))
                            (dolist (r (lane-rights lane))
                              (line (exit u) (entry (car r) (cdr r)) (aref q u)))))
                      (when at
                        (run at (lane-lo lane) (lane-hi lane))
                        (dolist (r (lane-rights lane))
                          (line at (entry (car r) (cdr r)) (aref q (car r)))))
                      (dolist (r (lane-rights lane))
                        (when (and (cdr r) (not (member (car r) tipped)))
                          (push (car r) tipped)
                          (tip (- (aref start (car r)) m) (aref q (car r)))))))))
        ;; A dot where lines join, so that two lines crossing do not read as joined;
        ;; then each line as one rectangle.
        (setf segments (nreverse segments))
        (dolist (point (junctions segments))
          (push (append (list :dot) (apply #'xy point) (list (dot-radius s))) shapes))
        (dolist (seg (loop with merged = segments
                           for next = (merge-segments merged)
                           until (= (length next) (length merged))
                           do (setf merged next)
                           finally (return next)))
          (destructuring-bind (p1 q1 p2 q2) seg
            (push (rect (- p1 half) (- q1 half) (+ (- p2 p1) *dg-line*)
                        (+ (- q2 q1) *dg-line*))
                  shapes)))
        (destructuring-bind (width height) (xy extent breadth)
          (finish-drawing (append (cut-lines (nreverse shapes) cuts)
                                  (sort boxes #'< :key #'sixth))
                          0 width height))))))

(defun on-segment (point seg)
  "How many line ends SEG, (P1 Q1 P2 Q2) along one axis, has at POINT: 1 at an end, 2
passing through it, else 0."
  (destructuring-bind (p q) point
    (destructuring-bind (p1 q1 p2 q2) seg
      (cond ((or (and (= p p1) (= q q1)) (and (= p p2) (= q q2))) 1)
            ((and (<= p1 p p2) (<= q1 q q2)) 2)
            (t 0)))))

(defun junctions (segments)
  "The ends of SEGMENTS where three or more line ends meet: where lines join, as against
where they cross."
  (let ((out nil))
    (dolist (seg segments)
      (dolist (point (list (subseq seg 0 2) (subseq seg 2 4)))
        (when (and (not (member point out :test #'equal))
                   (>= (reduce #'+ (mapcar (lambda (g) (on-segment point g)) segments))
                       3))
          (push point out))))
    (nreverse out)))

(defun merge-segments (segments)
  "SEGMENTS with those along one line that overlap or touch joined into one, in the order
of their first."
  (let ((out nil))
    (dolist (seg segments)
      (let ((same (find-if (lambda (o)
                             (if (= (second seg) (fourth seg))
                                 (and (= (second o) (fourth o) (second seg))
                                      (<= (first o) (third seg))
                                      (<= (first seg) (third o)))
                                 (and (= (first o) (third o) (first seg))
                                      (/= (second o) (fourth o))
                                      (<= (second o) (fourth seg))
                                      (<= (second seg) (fourth o)))))
                           out)))
        (if same
            (setf (first same) (min (first same) (first seg))
                  (second same) (min (second same) (second seg))
                  (third same) (max (third same) (third seg))
                  (fourth same) (max (fourth same) (fourth seg)))
            (push (copy-list seg) out))))
    (nreverse out)))

(defun flow-drawing (b s avail)
  "The flow B at label size S in AVAIL px."
  (if (flow-plan-chain (flow-plan-of b))
      (rows-drawing b s avail)
      (layered-drawing b s avail)))

(defun flow-hint (b)
  "What the too-wide error suggests for the flow B."
  (cond ((flow-plan-chain (flow-plan-of b)) "shorten its widest label")
        ((eq (flow-graph-direction (diagram-datum b)) :right)
         "shorten its labels or draw it :direction :down")
        (t "shorten its labels or draw it :direction :right")))

;;; --- charts --------------------------------------------------------------------------
;;;
;;; A chart is drawn in integer and rational arithmetic, as a flow is: a value's place is
;;; its rational share of the scale floored to a px, every tick an exact multiple of a
;;; step of 1, 2 or 5 x 10^n, and a pie's edges come from a fixed-point sine, so every
;;; backend draws the same picture. Series take the theme's chart colors in order, the
;;; accent first; text keeps the text colors.

(defparameter *bar-room*
  4
  "The least room for the longest bar, in label sizes: a bar chart with less is too
wide.")
(defparameter *dg-hair*
  2
  "The thickness of a gridline and of the zero line, and the gap between two filled marks.")
(defparameter *chart-line* 6 "The thickness of a line chart's lines.")
(defparameter *chrome-classes*
  '("dg-g" "dg-z" "dg-bg")
  "The classes of the shapes of a chart a callout may cover: gridlines, the zero line and
the gaps between marks.")

(defun plot-height (s)
  "The height of the plot of columns or lines, and the diameter of a pie."
  (* 5 (dg-lh s)))
(defun marker-radius (s) (floor s 4))
(defun swatch-size (s) (floor (* s 2) 3))
(defun series-count (chart) (max 1 (length (chart-series chart))))
(defun series-class (i) (format nil "dg-s~d" (1+ i)))
(defun ring-class (i) (format nil "dg-r~d" (1+ i)))

(defun shape-series (shape)
  "The index of the series a shape of a chart belongs to, by its class, or nil."
  (let ((class (case (car shape)
                 (:rect (sixth shape))
                 (:dot (fifth shape))
                 ((:poly :slice) (second shape)))))
    (and (stringp class) (= (length class) 5)
         (member (subseq class 0 4) '("dg-s" "dg-r") :test #'string=)
         (1- (digit-char-p (char class 4))))))

(defun chrome-p (shape)
  "True for a gridline, the zero line or a gap: drawn, but no obstacle to a callout."
  (and (member (car shape) '(:rect :poly))
       (member (if (eq (car shape) :rect) (sixth shape) (second shape)) *chrome-classes*
               :test #'equal)))

(defun text-width (text s) (run-full-width (make-text-run (list text)) s))

(defun text-decimals (text)
  "How many digits a value written as TEXT has after its decimal point."
  (let ((dot (position #\. text))) (if dot (- (length text) dot 1) 0)))

(defun group-digits (digits)
  "The decimal string DIGITS with a comma before each group of three from the right."
  (with-output-to-string (out)
    (let ((n (length digits)))
      (dotimes (i n)
        (when (and (> i 0) (zerop (mod (- n i) 3))) (write-char #\, out))
        (write-char (char digits i) out)))))

(defun decimal-text (v decimals grouped)
  "The rational V in decimal with DECIMALS digits after the point, rounded, its thousands
grouped with commas when GROUPED."
  (let* ((scale (expt 10 decimals)) (n (round (* (abs v) scale))))
    (multiple-value-bind (whole frac) (floor n scale)
      (let ((w (format nil "~d" whole)) (f (format nil "~d" frac)))
        (concatenate 'string (if (and (< v 0) (> n 0)) "-" "")
                     (if grouped (group-digits w) w)
                     (if (> decimals 0)
                         (concatenate 'string "."
                                      (make-string (- decimals (length f))
                                                   :initial-element #\0)
                                      f)
                         ""))))))

(defun nice-scale (lo hi intervals)
  "The bounds and the step of a scale over LO to HI (LO < HI) in at most INTERVALS (2 or
more) steps: the smallest step of 1, 2 or 5 x 10^n that does, the bounds its multiples
around LO and HI."
  (let ((raw (/ (- hi lo) intervals)) (p 1))
    (loop while (> p raw) do (setf p (/ p 10)))
    (loop while (<= (* 10 p) raw) do (setf p (* 10 p)))
    (dolist (m '(1 2 5 10 20 50 100))
      (let ((c (* m p)))
        (when (<= (- (ceiling hi c) (floor lo c)) intervals)
          (return (values (* c (floor lo c)) (* c (ceiling hi c)) c)))))))

(defun axis-ticks (chart lo hi intervals s)
  "The scale of CHART over LO to HI in at most INTERVALS steps: its bounds and its ticks,
each (VALUE TEXT WIDTH), the text with the unit and as many decimals as the step has."
  (multiple-value-bind (a z c) (nice-scale lo hi intervals)
    (let ((d (loop for d from 0 when (integerp (* c (expt 10 d))) return d)))
      (values a z
              (loop for k from (round a c) to (round z c)
                    collect (let ((text (concatenate 'string
                                                     (decimal-text (* k c) d
                                                                   (chart-grouped chart))
                                                     (chart-unit chart))))
                              (list (* k c) text (text-width text s))))))))

(defun chart-extent (chart)
  "The least and the greatest value CHART's marks reach: its values, or under :stack each
row's sums of its negative and of its positive values."
  (let ((reached
         (if (chart-stack chart)
             (loop for vs in (chart-values chart)
                   collect (reduce #'+ (remove-if-not #'minusp vs))
                   collect (reduce #'+ (remove-if-not #'plusp vs)))
             (reduce #'append (chart-values chart)))))
    (values (reduce #'min reached) (reduce #'max reached))))

(defun bar-domain (chart)
  "The values the bars of CHART span: from zero, which every bar starts at, to the
farthest; 0 to 1 when every value is zero."
  (multiple-value-bind (lo hi) (chart-extent chart)
    (let ((lo (min 0 lo)) (hi (max 0 hi)))
      (if (= lo hi) (values 0 1) (values lo hi)))))

(defun line-domain (chart)
  "The values the scale of the line chart CHART spans: its least to its greatest value,
reaching on to zero unless that would leave the lines less than half the plot."
  (multiple-value-bind (lo hi) (chart-extent chart)
    (cond ((and (> lo 0) (<= (* 2 lo) hi)) (setf lo 0))
          ((and (< hi 0) (>= (* 2 hi) lo)) (setf hi 0)))
    (when (= lo hi) (setf lo (min 0 lo) hi (max 0 hi)))
    (if (= lo hi) (values 0 1) (values lo hi))))

(defun chart-bars (chart)
  "The bars of CHART, each (ROW SERIES LANE FROM TO), and the value written at each one's
end, (ROW SERIES LANE AT TEXT), its unit included: a bar per value in the series' lane of
the row, or under :stack one lane of segments, the negative values' below zero and the
positive ones' above, with the row's total (SERIES nil) at the end of the side it comes
to."
  (let ((bars nil) (labels nil) (unit (chart-unit chart)))
    (loop for vs in (chart-values chart)
          for ts in (chart-texts chart)
          for j from 0
          do
            (if (chart-stack chart)
                (let ((pos 0) (neg 0))
                  (loop for v in vs
                        for i from 0
                        do
                          (cond ((> v 0) (push (list j i 0 pos (incf pos v)) bars))
                                ((< v 0) (push (list j i 0 neg (incf neg v)) bars))))
                  (push (list j nil 0 (if (< (+ pos neg) 0) neg pos)
                              (concatenate 'string
                                           (decimal-text (+ pos neg)
                                                         (reduce #'max
                                                                 (mapcar #'text-decimals ts))
                                                         (chart-grouped chart))
                                           unit))
                        labels))
                (loop for v in vs
                      for text in ts
                      for i from 0
                      do
                        (push (list j i i 0 v) bars)
                        (push (list j i i v (concatenate 'string text unit)) labels))))
    (values (nreverse bars) (nreverse labels))))

(defun bar-span (from to pos stack)
  "The px from and to of a bar from the value FROM to TO along the scale POS, at least
*DG-LINE* px long away from zero when it stands for a value other than zero, unless it
is a segment of a stack."
  (let ((a (funcall pos from)) (z (funcall pos to)))
    (when (and (not stack) (/= from to) (< (abs (- z a)) *dg-line*))
      (setf z (if (> z a) (+ a *dg-line*) (- a *dg-line*))))
    (values (min a z) (max a z))))

(defun marker (i x y r class)
  "The marker of a line chart's Ith series, R px from X, Y: a circle, a square, a
diamond, a triangle up or down or a plus, so that the series differ in shape as in
color."
  (let* ((e (+ r (floor r 4))) (q (floor (* e 3) 4)) (a (1+ (floor e 3))))
    (case (mod i 6)
      (0 (list :dot x y r class))
      (1 (list :rect (- x (1- r)) (- y (1- r)) (* 2 (1- r)) (* 2 (1- r)) class 0))
      (2 (list :poly class x (- y e) (+ x e) y x (+ y e) (- x e) y))
      (3 (list :poly class x (- y e) (+ x e) (+ y q) (- x e) (+ y q)))
      (4 (list :poly class x (+ y e) (+ x e) (- y q) (- x e) (- y q)))
      (t
       (list :poly class (- x a) (- y e) (+ x a) (- y e) (+ x a) (- y a) (+ x e) (- y a)
             (+ x e) (+ y a) (+ x a) (+ y a) (+ x a) (+ y e) (- x a) (+ y e) (- x a)
             (+ y a) (- x e) (+ y a) (- x e) (- y a) (- x a) (- y a))))))

(defun ringed-marker (i x y s)
  "The marker of the Ith series at X, Y, over a ring of the page's color that parts it
from the lines it stands on."
  (let ((r (marker-radius s)))
    (list (marker i x y (+ r 3) (ring-class i)) (marker i x y r (series-class i)))))

(defun line-segment (x1 y1 x2 y2 class)
  "A line from X1, Y1 to X2, Y2 (X1 < X2) as a filled band *CHART-LINE* px thick: a
parallelogram cut upright at both ends, its upright half height widened with the slope so
that it is as thick across at any slope."
  (let* ((dx (- x2 x1))
         (dy (- y2 y1))
         (h (ceiling (* (floor *chart-line* 2) (isqrt (+ (* dx dx) (* dy dy)))) dx)))
    (list :poly class x1 (- y1 h) x2 (- y2 h) x2 (+ y2 h) x1 (+ y1 h))))

(defun box-of (shape) (shape-box shape))

(defun chart-legend (chart kind s avail)
  "The legend of CHART at S px in AVAIL px, from the top left: per series a swatch (on a
line chart a stretch of its line through its marker) and its name, wrapped at its
phrases when wider than the room, left to right in as few rows as fit. Returns its
shapes, its height and each name's box as ((:series I) . BOX); nothing for one series."
  (let ((runs (chart-series-runs chart)))
    (if (null runs)
        (values nil 0 nil)
        (let* ((lh (dg-lh s))
               (sw (if (eq kind :lines) s (swatch-size s)))
               (sp (floor s 3))
               (cap (max 1 (- avail sw sp)))
               (tws (mapcar (lambda (run) (flow-text-width run s cap)) runs))
               (lines (mapcar (lambda (run w) (run-lines run s w)) runs tws))
               (widths (mapcar (lambda (w) (+ sw sp w)) tws))
               (shapes nil)
               (targets nil)
               (y 0)
               (i 0))
          (dolist (count (greedy-rows widths
                                      (make-list (1- (length widths)) :initial-element s)
                                      avail))
            (let ((x 0) (cy (+ y (floor lh 2))))
              (dotimes (k count)
                (if (eq kind :lines)
                    (progn
                      (push (list :rect x (- cy (floor *chart-line* 2)) sw *chart-line*
                                  (series-class i) 0)
                            shapes)
                      (dolist (m (ringed-marker i (+ x (floor sw 2)) cy s)) (push m shapes)))
                    (push (list :rect x (- cy (floor sw 2)) sw sw (series-class i) 0)
                          shapes))
                (let ((label (list :legend (+ x sw sp) y (nth i tws)
                                   (* lh (nth i lines)) i)))
                  (push label shapes)
                  (push (cons (list :series i) (box-of label)) targets))
                (incf x (+ (nth i widths) s))
                (incf i))
              (incf y (* lh (reduce #'max (subseq lines (- i count) i))))))
          (values (nreverse shapes) (+ y (floor s 2)) (nreverse targets))))))

(defun finish-chart (shapes width height targets)
  (let ((d (finish-drawing shapes 0 width height)))
    (setf (drawing-targets d) targets)
    d))

(defun category-targets (n rows targets labels)
  "TARGETS with the target of each of ROWS categories: on a chart of one (N) series its
mark, else its label, whose boxes LABELS lists by row."
  (append targets
          (loop for j below rows
                collect (cons (list :category j)
                              (if (= n 1)
                                  (cdr (assoc (list :mark 0 j) targets :test #'equal))
                                  (nth j labels))))))

(defun bar-label-width (runs s avail)
  "The width of a bar chart's label column at S px in AVAIL px: its widest label set on
one line, or wrapped at its phrases when that is wider than a third of the chart."
  (reduce #'max
          (mapcar (lambda (run)
                    (max (run-min-width run s)
                         (min (run-full-width run s) (floor avail 3))))
                  runs)))

(defun bars-drawing (b s avail)
  "The bar chart B at label size S in AVAIL px: a row per category, its label on the left
(wrapped at its phrases when long), a bar per series as long as its value's share of the
scale from zero, which stands where the most negative bar leaves room, and the value at
each bar's end; under :axis a scale along the bottom instead of the values. The chart
spans AVAIL; it is wider when the longest bar would have less than its least room."
  (let* ((chart (diagram-datum b))
         (runs (diagram-runs b))
         (n (series-count chart))
         (stack (chart-stack chart))
         (grouped (and (> n 1) (not stack)))
         (axis (chart-axis chart))
         (gap (dg-pad-x s))
         (lh (dg-lh s))
         (pad (floor s 4))
         (thick (if grouped s lh))
         (band (if grouped (+ (* n s) (* (1- n) *dg-hair*)) lh))
         (label-w (bar-label-width runs s avail))
         (shapes nil)
         (texts nil)
         (targets nil))
    (multiple-value-bind (legend top legend-targets) (chart-legend chart :bars s avail)
      (multiple-value-bind (bars marks) (chart-bars chart)
        (multiple-value-bind (lo hi) (bar-domain chart)
          (let* ((vws (mapcar (lambda (m) (text-width (fifth m) s)) marks))
                 (left-w (reduce #'max
                                 (loop for m in marks
                                       for w in vws
                                       when (< (fourth m) 0)
                                         collect w)
                                 :initial-value 0))
                 (right-w (reduce #'max
                                  (loop for m in marks
                                        for w in vws
                                        when (>= (fourth m) 0)
                                          collect w)
                                  :initial-value 0))
                 (start (+ label-w gap (if (and (not axis) (> left-w 0)) (+ left-w gap) 0)))
                 (right (if (and (not axis) (> right-w 0)) (+ gap right-w) 0))
                 (ticks nil))
            (when axis
              ;; As many ticks as their labels leave room for, five steps at most.
              (loop for m from 5 downto 2
                    do
                      (multiple-value-bind (a z ts) (axis-ticks chart lo hi m s)
                        (let* ((first-half (ceiling (third (first ts)) 2))
                               (last-half (ceiling (third (car (last ts))) 2))
                               (from (max (+ label-w gap) first-half))
                               (room (- avail from last-half)))
                          (when (or (= m 2)
                                    (>= (* room (- (first (second ts)) (first (first ts))))
                                        (* (- z a)
                                           (+ s (reduce #'max (mapcar #'third ts))))))
                            (setf lo a hi z ticks ts start from right last-half)
                            (return))))))
            (let* ((room (max (* *bar-room* s) (- avail start right)))
                   (pos (lambda (v) (+ start (floor (* (- v lo) room) (- hi lo)))))
                   (y top)
                   (row-tops nil))
              (loop for run in runs
                    for j from 0
                    do
                      (let* ((lines (run-lines run s label-w))
                             (h (max (* lines lh) band))
                             (band-top (+ y pad (floor (- h band) 2)))
                             (label (list :category 0 (+ y pad (floor (- h (* lines lh)) 2))
                                          label-w (* lines lh) j "left"))
                             (ends nil))
                        (push y row-tops)
                        (push label texts)
                        (push (cons (list :label j) (box-of label)) targets)
                        (dolist (bar bars)
                          (destructuring-bind (row i lane from to) bar
                            (when (= row j)
                              (multiple-value-bind (x1 x2) (bar-span from to pos stack)
                                (let ((rect (list :rect x1 (+ band-top (* lane (+ thick *dg-hair*)))
                                                  (- x2 x1) thick (series-class i) 0)))
                                  (when (> x2 x1)
                                    (push rect shapes)
                                    (when (and stack (/= from 0))
                                      (push (list :rect (- (funcall pos from) (floor *dg-hair* 2))
                                                  (third rect) *dg-hair* thick "dg-bg" 0)
                                            shapes)))
                                  (push (cons (list :mark i j) (box-of rect)) targets)
                                  (push (list i (if (< to 0) x1 x2)) ends))))))
                        (unless axis
                          (loop for m in marks
                                for w in vws
                                do
                                  (destructuring-bind (row i lane at text) m
                                    (when (= row j)
                                      (let* ((end (if (and i (not stack))
                                                      (second (assoc i ends))
                                                      (funcall pos at)))
                                             (value
                                              (list :value
                                                    (if (< at 0) (- end gap w) (+ end gap))
                                                    (+ band-top (* lane (+ thick *dg-hair*)))
                                                    w thick text (if (< at 0) "right" "left")
                                                    i)))
                                        (push value texts)
                                        (when (and i (not stack))
                                          ;; The tail points at the value, at the bar's end.
                                          (push (cons (list :mark i j) (box-of value))
                                                targets)))))))
                        (incf y (+ h (* 2 pad)))))
              (let ((rows-top top) (rows-bottom y) (grid nil))
                (dolist (tk ticks)
                  (destructuring-bind (v text w) tk
                    (let ((x (funcall pos v)))
                      (unless (zerop v)
                        (push (list :rect (- x (floor *dg-hair* 2)) rows-top *dg-hair*
                                    (- rows-bottom rows-top) "dg-g" 0)
                              grid))
                      (push (list :tick (- x (floor w 2)) rows-bottom w lh text "center")
                            texts))))
                (finish-chart (append (nreverse grid) (reverse shapes)
                                      (list (list :rect (- (funcall pos 0) (floor *dg-hair* 2))
                                                  rows-top *dg-hair* (- rows-bottom rows-top)
                                                  "dg-z" 0))
                                      legend (nreverse texts))
                              (+ start room right)
                              (if axis (+ rows-bottom lh) rows-bottom)
                              (category-targets n (length runs)
                                                (append legend-targets targets)
                                                (loop for j below (length runs)
                                                      collect (cdr (assoc (list :label j)
                                                                          targets
                                                                          :test #'equal)))))))))))))

(defun plot-drawing (b s avail)
  "The column or line chart B at label size S in AVAIL px: the categories side by side
along the bottom, their labels under them wrapped at their phrases, over them a column
per series as tall as its value's share of the scale from zero, with the value at its
end, or a point per series joined by its line, along a scale with ticks and gridlines on
the left (always on a line chart, under :axis on columns). The chart spans AVAIL; it is
wider when a category's label or values do not fit its share."
  (let* ((chart (diagram-datum b))
         (runs (diagram-runs b))
         (rows (length runs))
         (line (eq (diagram-kind b) :lines))
         (n (series-count chart))
         (stack (chart-stack chart))
         (lanes (if (and (> n 1) (not stack) (not line)) n 1))
         (axis (or line (chart-axis chart)))
         (gap (dg-pad-x s))
         (lh (dg-lh s))
         (pad (floor s 4))
         (p (plot-height s))
         (shapes nil)
         (texts nil)
         (targets nil)
         (labels nil))
    (multiple-value-bind (legend top legend-targets) (chart-legend chart (diagram-kind b) s
                                                                   avail)
      (multiple-value-bind (bars marks) (if line (values nil nil) (chart-bars chart))
        (multiple-value-bind (lo hi) (if line (line-domain chart) (bar-domain chart))
          (let* ((ticks nil)
                 (axis-w 0)
                 (vws (if axis nil (mapcar (lambda (m) (text-width (fifth m) s)) marks)))
                 (vmax (reduce #'max vws :initial-value 0))
                 (max-c (max (* 2 lh) (if (> lanes 1) vmax 0))))
            (when axis
              (multiple-value-bind (a z ts) (axis-ticks chart lo hi 4 s)
                (setf lo a hi z ticks ts
                      axis-w (+ gap (reduce #'max (mapcar #'third ts))))))
            (let* ((min-slot
                    (max s
                         (reduce #'max (mapcar (lambda (run) (+ gap (run-min-width run s)))
                                               runs))
                         (cond (axis 0)
                               ((= lanes 1) (+ gap vmax))
                               (t (ceiling (* 3 (+ (* lanes vmax)
                                                   (* (1- lanes) *dg-hair*)))
                                           2)))))
                   (w (max (- avail axis-w) (* rows min-slot)))
                   (c (min max-c
                           (floor (- (floor (* (floor w rows) 2) 3) (* (1- lanes) *dg-hair*))
                                  lanes)))
                   (group (+ (* lanes c) (* (1- lanes) *dg-hair*)))
                   (head (cond (axis (floor lh 2))
                               ((some (lambda (m) (>= (fourth m) 0)) marks) lh)
                               (t 0)))
                   (plot-top (+ top head))
                   (plot-bottom (+ plot-top p))
                   (foot (if (and (not axis) (some (lambda (m) (< (fourth m) 0)) marks)) lh 0))
                   (band-top (+ plot-bottom foot pad))
                   (band 0)
                   (pos (lambda (v) (+ plot-top (floor (* (- hi v) p) (- hi lo)))))
                   (slot (lambda (j) (+ axis-w (floor (* j w) rows))))
                   (grid nil)
                   (points (make-array (list n rows))))
              ;; The scale: a gridline and a label per tick.
              (dolist (tk ticks)
                (destructuring-bind (v text tw) tk
                  (declare (ignore tw))
                  (let ((y (funcall pos v)))
                    (unless (zerop v)
                      (push (list :rect axis-w (- y (floor *dg-hair* 2)) w *dg-hair* "dg-g" 0)
                            grid))
                    (push (list :tick 0 (- y (floor lh 2)) (- axis-w gap) lh text "right")
                          texts))))
              (dotimes (j rows)
                (let* ((x0 (funcall slot j))
                       (sw (- (funcall slot (1+ j)) x0))
                       (gx (+ x0 (floor (- sw group) 2)))
                       (lw (- sw gap))
                       (lines (run-lines (nth j runs) s lw))
                       (label (list :category (+ x0 (floor gap 2)) band-top lw (* lines lh) j
                                    "center"))
                       (ends nil))
                  (setf band (max band (* lines lh)))
                  (push label texts)
                  (push (box-of label) labels)
                  (if line
                      (loop for v in (nth j (chart-values chart))
                            for i from 0
                            do (setf (aref points i j)
                                     (list (+ x0 (floor sw 2)) (funcall pos v))))
                      (progn
                        (dolist (bar bars)
                          (destructuring-bind (row i lane from to) bar
                            (when (= row j)
                              (multiple-value-bind (y1 y2)
                                  (bar-span from to pos stack)
                                (let ((rect (list :rect (+ gx (* lane (+ c *dg-hair*))) y1 c
                                                  (- y2 y1) (series-class i) 0)))
                                  (when (> y2 y1)
                                    (push rect shapes)
                                    (when (and stack (/= from 0))
                                      (push (list :rect (second rect)
                                                  (- (funcall pos from) (floor *dg-hair* 2))
                                                  c *dg-hair* "dg-bg" 0)
                                            shapes)))
                                  (push (cons (list :mark i j) (box-of rect)) targets)
                                  (push (list i (if (< to 0) y2 y1)) ends))))))
                        (unless axis
                          (loop for m in marks
                                for vw in vws
                                do
                                  (destructuring-bind (row i lane at text) m
                                    (when (= row j)
                                      (let* ((end (if (and i (not stack))
                                                      (second (assoc i ends))
                                                      (funcall pos at)))
                                             (cx (+ gx (* lane (+ c *dg-hair*)) (floor c 2)))
                                             (value (list :value (- cx (floor vw 2))
                                                          (if (< at 0) end (- end lh)) vw lh
                                                          text "center" i)))
                                        (push value texts)
                                        (when (and i (not stack))
                                          ;; The tail points at the value, at the bar's end.
                                          (push (cons (list :mark i j) (box-of value))
                                                targets)))))))))))
              ;; Each series' line, and its markers over it.
              (when line
                (dotimes (i n)
                  (dotimes (j (1- rows))
                    (destructuring-bind (x1 y1) (aref points i j)
                      (destructuring-bind (x2 y2) (aref points i (1+ j))
                        (push (line-segment x1 y1 x2 y2 (series-class i)) shapes))))
                  (dotimes (j rows)
                    (destructuring-bind (x y) (aref points i j)
                      (destructuring-bind (ring m) (ringed-marker i x y s)
                        (push ring shapes)
                        (push m shapes)
                        (push (cons (list :mark i j) (box-of ring)) targets))))))
              (finish-chart (append (nreverse grid) (reverse shapes)
                                    (and (<= lo 0 hi)
                                         (list (list :rect axis-w
                                                     (- (funcall pos 0) (floor *dg-hair* 2))
                                                     w *dg-hair* "dg-z" 0)))
                                    legend (nreverse texts))
                            (+ axis-w w) (+ band-top band)
                            (category-targets n rows (append legend-targets targets)
                                              (nreverse labels))))))))))

;;; A pie's edges: the point a share of a turn clockwise from the top, from a sine and
;;; cosine summed in fixed point (x 10^18) from their Taylor series, every term an
;;; integer, so no float is computed.

(defparameter *trig-one* (expt 10 18))
(defparameter *trig-pi* 3141592653589793238 "Pi x 10^18.")

(defun small-sin-cos (a)
  "The sine and cosine x 10^18 of the angle A x 10^-18 radians, 0 <= A <= pi/4."
  (let ((a2 (floor (* a a) *trig-one*)) (sin 0) (cos 0))
    (loop for term = a then (floor (* term a2) (* *trig-one* k (1+ k)))
          for k from 2 by 2
          for sign = 1 then (- sign)
          while (> term 0)
          do (incf sin (* sign term)))
    (loop for term = *trig-one* then (floor (* term a2) (* *trig-one* (1- k) k))
          for k from 2 by 2
          for sign = 1 then (- sign)
          while (> term 0)
          do (incf cos (* sign term)))
    (values sin cos)))

(defun turn-point (f r)
  "The offset (DX DY) from a circle's center of the point of its edge, R px from the
center, the share F of a turn clockwise from the top: R sin and -R cos of 2 pi F,
rounded, exact at every eighth of a turn's quarter points."
  (let* ((e (* 8 (- f (floor f))))
         (q (floor e))
         (u (- e q))
         (k (if (evenp q) (floor q 2) (floor (1+ q) 2)))
         (psi (floor (* (if (evenp q) u (- 1 u)) *trig-pi*) 4)))
    (multiple-value-bind (sn cs) (small-sin-cos psi)
      (when (oddp q) (setf sn (- sn)))
      (let ((sin (case (mod k 4) (0 sn) (1 cs) (2 (- sn)) (t (- cs))))
            (cos (case (mod k 4) (0 cs) (1 (- sn)) (2 (- cs)) (t sn))))
        (list (round (* r sin) *trig-one*) (- (round (* r cos) *trig-one*)))))))

(defun slice-shape (cx cy r ri f0 f1 class)
  "The slice of a pie centered at CX, CY, R px across, from the share F0 of a turn to F1;
a ring's slice when RI, its hole's radius, is above 0. Its box takes in the edge's
extreme points within it, as a browser's box of the path does."
  (flet ((at (f radius)
           (destructuring-bind (dx dy) (turn-point f radius) (list (+ cx dx) (+ cy dy)))))
    (let* ((p1 (at f0 r))
           (p2 (at f1 r))
           (p3 (if (> ri 0) (at f1 ri) (list cx cy)))
           (p4 (if (> ri 0) (at f0 ri) (list cx cy)))
           (extremes (loop for j from 1 to 3
                           when (< f0 (/ j 4) f1)
                             collect (at (/ j 4) r)))
           (all (append (list p1 p2 p3 p4) extremes))
           (xs (mapcar #'first all))
           (ys (mapcar #'second all)))
      (append (list :slice class
                    (list (reduce #'min xs) (reduce #'min ys) (reduce #'max xs)
                          (reduce #'max ys))
                    r ri (if (> (- f1 f0) 1/2) 1 0))
              p1 p2 p3 p4))))

(defun pie-drawing (b s avail)
  "The pie chart B at label size S in AVAIL px: the pie, its slices clockwise from the top
in the order written, each its value's share of the whole, beside a legend of a swatch,
the label (wrapped at its phrases when long) and the value per slice, as tall as the pie
or the pie as tall as it; a ring under :donut."
  (let* ((chart (diagram-datum b))
         (runs (diagram-runs b))
         (rows (length runs))
         (lh (dg-lh s))
         (gap (dg-pad-x s))
         (pad (floor s 4))
         (sw (swatch-size s))
         (sp (floor s 3))
         (vals (mapcar #'first (chart-values chart)))
         (texts (mapcar (lambda (ts) (concatenate 'string (first ts) (chart-unit chart)))
                        (chart-texts chart)))
         (vws (mapcar (lambda (text) (text-width text s)) texts))
         (vw (reduce #'max vws))
         ;; The legend's height, which the pie matches when that is taller, at the label
         ;; width the room left beside the least pie allows.
         (lw (reduce #'max (mapcar (lambda (run)
                                     (flow-text-width run s
                                                      (- avail (plot-height s) (* 2 s) sw sp
                                                         gap vw)))
                                   runs)))
         (lines (mapcar (lambda (run) (run-lines run s lw)) runs))
         (legend-h (reduce #'+ (mapcar (lambda (l) (+ (* l lh) (* 2 pad))) lines)))
         (d (max (plot-height s) legend-h))
         (r (floor d 2))
         (ri (if (chart-donut chart) (floor (* r 3) 5) 0))
         (fixed (+ d (* 2 s) sw sp gap vw))
         (height d)
         (cx r)
         (cy (+ (floor (- height d) 2) r))
         (total (reduce #'+ vals))
         (shown (count-if #'plusp vals))
         (shapes nil)
         (labels nil)
         (targets nil)
         (lx (+ d (* 2 s)))
         (y (floor (- height legend-h) 2))
         (f 0))
    (loop for v in vals
          for text in texts
          for w in vws
          for l in lines
          for j from 0
          do
            (let ((g (+ f (/ v total))) (class (series-class j)))
              (when (plusp v)
                (let ((mark (if (= shown 1)
                                (list :dot cx cy r class)
                                (slice-shape cx cy r ri f g class))))
                  (push mark shapes)
                  (when (and (= shown 1) (> ri 0))
                    (push (list :dot cx cy ri "dg-bg") shapes))
                  (push (cons (list :mark 0 j) (box-of mark)) targets)))
              (let ((top (+ y pad)))
                (push (list :rect lx (+ top (floor (- lh sw) 2)) sw sw class 0) shapes)
                (push (list :category (+ lx sw sp) top lw (* l lh) j "left") labels)
                (let ((value (list :value (+ lx sw sp lw gap) top vw lh text "right" nil)))
                  (push value labels)
                  (unless (plusp v) (push (cons (list :mark 0 j) (box-of value)) targets))))
              (incf y (+ (* l lh) (* 2 pad)))
              (setf f g)))
    ;; A gap of the page's color where two slices meet.
    (when (> shown 1)
      (let ((f 0))
        (dolist (v vals)
          (when (plusp v)
            (destructuring-bind (dx dy) (turn-point f r)
              (destructuring-bind (ix iy) (if (> ri 0) (turn-point f ri) (list 0 0))
                (let ((px (round (- dy) r)) (py (round dx r)))
                  (push (list :poly "dg-bg" (+ cx ix px) (+ cy iy py) (+ cx dx px)
                              (+ cy dy py) (- (+ cx dx) px) (- (+ cy dy) py)
                              (- (+ cx ix) px) (- (+ cy iy) py))
                        shapes)))))
          (incf f (/ v total)))))
    (finish-chart (append (reverse shapes) (nreverse labels)) (+ fixed lw) height
                  (category-targets 1 rows targets nil))))

(defun chart-description (b)
  "The data of the chart B as text, which names its figure: each row's label and value,
by series on a chart of several."
  (let* ((chart (diagram-datum b))
         (unit (chart-unit chart))
         (labels (mapcar (lambda (nodes) (values (flatten-inlines nodes nil)))
                         (diagram-labels b)))
         (series (mapcar (lambda (nodes) (values (flatten-inlines nodes nil)))
                         (chart-series chart))))
    (flet ((row-texts (i)
             (loop for label in labels
                   for ts in (chart-texts chart)
                   collect (concatenate 'string label " " (nth i ts) unit))))
      (if series
          (format nil "~{~a~^; ~}"
                  (loop for name in series
                        for i from 0
                        collect (format nil "~a: ~{~a~^, ~}" name (row-texts i))))
          (format nil "~{~a~^, ~}" (row-texts 0))))))

;;; --- cells ---------------------------------------------------------------------------

(defun atom-label (x)
  "How cells writes the atom X: symbols as written (the reader keeps their case),
integers, ratios, strings and characters. Nil for any other atom, whose printing (a
float's above all) is not the same on every backend."
  (cond ((symbolp x)
         (concatenate 'string (if (keywordp x) ":" "") (symbol-name x)))
        ((integerp x) (format nil "~d" x))
        ((rationalp x) (format nil "~d/~d" (numerator x) (denominator x)))
        ((stringp x)
         (with-output-to-string (out)
           (write-char (code-char 34) out)
           (loop for c across x
                 do
                   (when (member (char-code c) '(34 92))
                     (write-char (code-char 92) out)) (write-char c out))
           (write-char (code-char 34) out)))
        ((characterp x)
         (concatenate 'string "#" (string (code-char 92))
                      (cond ((char= x #\Space) "Space")
                            ((char= x #\Newline) "Newline")
                            ((graphic-char-p x) (string x))
                            (t (format nil "U+~4,'0x" (char-code x))))))
        (t nil)))

(defun datum-atoms (x)
  "The labels of the atoms of the datum X but nil, cars before cdrs: the order cells
draws them in."
  (let ((out nil))
    (labels ((walk (x)
               (loop while (consp x)
                     do
                       (if (consp (car x))
                           (walk (car x))
                           (when (car x) (push (atom-label (car x)) out)))
                       (setf x (cdr x)))
               (when x (push (atom-label x) out))))
      (walk x))
    (nreverse out)))

(defun print-datum (x)
  "The datum X as Lisp prints it, with the atoms as cells writes them."
  (with-output-to-string (out)
    (labels ((pr (x)
               (if (not (consp x))
                   (write-string (atom-label x) out)
                   (progn
                     (write-char #\( out)
                     (loop
                       (pr (car x))
                       (setf x (cdr x))
                       (cond ((null x) (return))
                             ((consp x) (write-char #\Space out))
                             (t
                              (write-string " . " out)
                              (pr x)
                              (return))))
                     (write-char #\) out)))))
      (pr x))))

(defun slash (x y w h)
  "The slash of nil across the face X, Y, W x H, bottom left to top right."
  (let ((k (floor (* *dg-line* 3) 2)))
    (list :poly "dg-e" x (+ y h) (+ x k) (+ y h) (+ x w) y (- (+ x w) k) y)))

(defun cells-layout (x s)
  "The shapes of the list X at S px, its first cell's left at 0 and its row at the top, and
the left and right edges and the rows they take. Each cell of the cdr chain stands in one
row, a car's drawing below its cell: an atom as its label, a list as its own row of cells.
Cells move right as far as the drawings below them need."
  (let* ((q (dg-lh s))
         (link s)
         (sep s)
         (pitch (+ q s))
         (half (floor q 2))
         (l *dg-line*)
         (face (- q l (floor l 2)))
         (cars nil)
         (tail nil))
    (loop while (consp x) do (push (car x) cars) (setf x (cdr x)))
    (setf tail x cars (nreverse cars))
    ;; The drawing below each cell, as (SHAPES LEFT RIGHT ROWS) from the cell's left.
    (let* ((subs
            (mapcar (lambda (a)
                      (cond ((null a) nil)
                            ((consp a) (multiple-value-list (cells-layout a s)))
                            (t (let* ((text (atom-label a))
                                      (w (mono-width text s))
                                      (left (- half (floor w 2))))
                                 (list (list (list :atom left 0 w q text)) left
                                       (+ left w) 1))))) cars))
           (xs nil)
           (frontier nil))
      ;; Each cell's left: past the one before it, and its drawing past the drawings
      ;; before it.
      (let ((cx 0))
        (loop for sub in subs
              for first = t then nil
              do (unless first (incf cx (+ (* 2 q) link)))
                (when (and sub frontier)
                  (setf cx (max cx (- (+ frontier sep) (second sub)))))
                (when sub (setf frontier (+ cx (third sub)))) (push cx xs)))
      (setf xs (nreverse xs))
      (let ((shapes nil) (left 0) (right 0) (rows 1))
        (flet ((add (list) (setf shapes (revappend list shapes))))
          (loop for (cx next) on xs
                for sub in subs
                for a in cars
                do
                ;; A border, and a face for the car and one for the cdr either side
                ;; of the divider.
                  (add
                   (list (list :rect cx 0 (* 2 q) q "dg-b" 0)
                         (list :rect (+ cx l) l face (- q (* 2 l)) "dg-f" 0)
                         (list :rect (+ cx q (floor l 2)) l face (- q (* 2 l))
                               "dg-f" 0)))
                  (setf right (max right (+ cx (* 2 q))))
                ;; The car: a pointer down to its drawing, or nil's slash.
                  (if (null a)
                      (add (list (slash (+ cx l) l face (- q (* 2 l)))))
                      (destructuring-bind (sub-shapes sl sr srows) sub
                        (add (list (list :dot (+ cx half) half (dot-radius s))))
                        (add (arrow-down (+ cx half) half pitch s))
                        (add
                         (mapcar (lambda (shape) (shift-shape shape cx pitch))
                                 sub-shapes))
                        (setf left (min left (+ cx sl)) right
                              (max right (+ cx sr)) rows
                              (max rows (1+ srows)))))
                ;; The cdr: a pointer right to the next cell or to a dotted pair's
                ;; atom, or nil's slash.
                  (let ((dx (+ cx q half)))
                    (cond (next
                           (add (list (list :dot dx half (dot-radius s))))
                           (add (arrow-right dx next half s)))
                          (tail
                           (let* ((text (atom-label tail))
                                  (w (mono-width text s))
                                  (tx (+ cx (* 2 q) link (arrow-margin s))))
                             (add (list (list :dot dx half (dot-radius s))))
                             (add
                              (arrow-right dx (- tx (arrow-margin s)) half s))
                             (add (list (list :atom tx 0 w q text)))
                             (setf right (max right (+ tx w)))))
                          (t (add
                              (list
                               (slash (+ cx q (floor l 2)) l face
                                      (- q (* 2 l))))))))))
        (values (nreverse shapes) left right rows)))))

(defun cells-drawing (b s)
  "The cells B at label size S."
  (multiple-value-bind (shapes left right rows)
      (cells-layout (diagram-datum b) s)
    (finish-drawing shapes left (- right left) (- (* rows (+ (dg-lh s) s)) s))))

(defun draw-diagram (b s avail)
  "The diagram B at label size S in AVAIL px; wider than AVAIL when it does not fit."
  (case (diagram-kind b)
    (:flow (flow-drawing b s avail))
    (:bars (bars-drawing b s avail))
    ((:columns :lines) (plot-drawing b s avail))
    (:pie (pie-drawing b s avail))
    (t (cells-drawing b s))))

(defun diagram-bounds (b s)
  "The narrowest width the diagram B takes at S px, and its width on one row; nil for the
second of a chart that spans its region, which takes whatever width it is given."
  (case (diagram-kind b)
    ((:flow :pie)
     (values (drawing-width (draw-diagram b s 0))
             (drawing-width (draw-diagram b s 1000000))))
    ((:bars :columns :lines) (values (drawing-width (draw-diagram b s 0)) nil))
    (t (let ((w (drawing-width (cells-drawing b s)))) (values w w)))))
