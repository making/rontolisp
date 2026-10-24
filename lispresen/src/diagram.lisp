;;;; diagram.lisp -- the drawings of (flow ...), (bars ...) and (cells ...).
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
;;;                              it, dg-e part of an arrow
;;;   (:poly CLASS X1 Y1 ...)    a polygon: an arrowhead, or the slash of nil
;;;   (:dot X Y R)               where a pointer starts
;;;   (:box X Y W H I PAD)       the label of a flow's Ith box, PAD px above its lines
;;;   (:edge-label X Y W H J)    the Jth arrow label of a flow
;;;   (:bar-label X Y W H I)     the label of a bar chart's Ith bar
;;;   (:value X Y W H TEXT)      a bar's value, at the bar's end
;;;   (:atom X Y W H TEXT)       an atom of cells, as printed
(defstruct drawing width height shapes)

(defun shape-layer (shape)
  (case (car shape)
    (:rect (cond ((equal (sixth shape) "dg-b") 0)
                 ((equal (sixth shape) "dg-f") 1)
                 (t 2)))
    ((:poly :dot) 2)
    (t 3)))

(defun shape-box (shape)
  "The rectangle SHAPE covers, as (LEFT TOP RIGHT BOTTOM)."
  (case (car shape)
    (:poly (let ((xs (loop for (x) on (cddr shape) by #'cddr collect x))
                 (ys (loop for (nil y) on (cddr shape) by #'cddr collect y)))
             (list (reduce #'min xs) (reduce #'min ys) (reduce #'max xs)
                   (reduce #'max ys))))
    (:dot (destructuring-bind (x y r) (cdr shape)
            (list (- x r) (- y r) (+ x r) (+ y r))))
    (t (destructuring-bind (x y w h) (subseq shape 1 5)
         (list x y (+ x w) (+ y h))))))

(defun shift-shape (shape dx dy)
  (if (eq (car shape) :poly)
      (list* :poly (second shape)
             (loop for (x y) on (cddr shape) by #'cddr
                   collect (+ x dx)
                   collect (+ y dy)))
      (list* (first shape) (+ (second shape) dx) (+ (third shape) dy)
             (cdddr shape))))

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

;;; --- bars ----------------------------------------------------------------------------

(defparameter *bar-room*
  4
  "The least room for the longest bar, in label sizes: a bar chart with less is too
wide.")

(defun bar-label-width (runs s avail)
  "The width of a bar chart's label column at S px in AVAIL px: its widest label set on
one line, or wrapped at its phrases when that is wider than a third of the chart."
  (reduce #'max
          (mapcar (lambda (run)
                    (max (run-min-width run s)
                         (min (run-full-width run s) (floor avail 3))))
                  runs)))

(defun bar-value-widths (b s)
  (mapcar (lambda (v) (run-full-width (make-text-run (list (second v))) s))
          (diagram-datum b)))

(defun bars-drawing (b s avail)
  "The bar chart B at label size S in AVAIL px: a row per bar, its label on the left
(wrapped at its phrases when long), the bar as long as its value's share of the largest,
the value at its end. The chart spans AVAIL; it is wider when the longest bar would have
less than its least room."
  (let* ((runs (diagram-runs b))
         (gap (dg-pad-x s))
         (lh (dg-lh s))
         (pad (floor s 4))
         (label-w (bar-label-width runs s avail))
         (value-ws (bar-value-widths b s))
         (value-w (reduce #'max value-ws))
         (room (max (* *bar-room* s) (- avail label-w value-w (* 2 gap))))
         (top (reduce #'max (mapcar #'first (diagram-datum b))))
         (x (+ label-w gap))
         (shapes nil)
         (y 0))
    (loop for run in runs
          for (value text) in (diagram-datum b)
          for vw in value-ws
          for i from 0
          do
            (let* ((lines (run-lines run s label-w))
                   (by (+ y pad (floor (* (1- lines) lh) 2)))
                   (len
                    (if (zerop value)
                        0
                        (max *dg-line* (floor (* value room) top)))))
              (push (list :bar-label 0 (+ y pad) label-w (* lines lh) i) shapes)
              (when (> len 0) (push (list :rect x by len lh "dg-b" 0) shapes))
              (push (list :value (+ x len gap) by vw lh text) shapes)
              (incf y (+ (* lines lh) (* 2 pad)))))
    (finish-drawing (nreverse shapes) 0 (+ x room gap value-w) y)))

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
    (t (cells-drawing b s))))

(defun diagram-bounds (b s)
  "The narrowest width the diagram B takes at S px, and its width on one row; nil for the
second of a bar chart, which takes whatever width it is given."
  (case (diagram-kind b)
    (:flow
     (values (drawing-width (flow-drawing b s 0))
             (drawing-width (flow-drawing b s 1000000))))
    (:bars
     (values (+ (reduce #'max
                        (mapcar (lambda (run) (run-min-width run s))
                                (diagram-runs b)))
                (* 2 (dg-pad-x s)) (reduce #'max (bar-value-widths b s))
                (* *bar-room* s))
             nil))
    (t (let ((w (drawing-width (cells-drawing b s)))) (values w w)))))
