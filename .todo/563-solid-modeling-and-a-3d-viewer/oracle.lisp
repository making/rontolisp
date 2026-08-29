;;;; oracle.lisp -- the numeric check: closed forms, transforms, a joint chain.
(require :geom "geom.lisp")

(defun p3 (v) (list (aref v 0) (aref v 1) (aref v 2)))
(defun v3 (x y z) (geom:vec3 x y z))

(let ((b (geom:box '(100 200 300))))
  (format t "box volume    = ~a (exact 6000000)~%" (geom:volume b))
  (format t "box area      = ~a (exact 220000)~%" (geom:surface-area b))
  (format t "box centroid  = ~a~%" (p3 (geom:centroid b)))
  (format t "box extent    = ~a~%" (p3 (geom:bounds-extent (geom:bounds b))))
  (geom:move b (v3 10 0 0))
  (format t "moved centre  = ~a~%" (p3 (geom:bounds-center (geom:bounds b)))))

(let ((c (geom:cylinder :radius 50 :height 100 :sides 64)))
  (format t "cylinder vol  = ~a (pi r^2 h = ~a)~%" (geom:volume c) (* 3.14159265 50 50 100)))
(let ((s (geom:sphere :radius 50 :sides 32 :stacks 24)))
  (format t "sphere vol    = ~a (4/3 pi r^3 = ~a)~%" (geom:volume s) (* 4.1887902 50 50 50)))
(let ((tr (geom:torus :radius 60 :tube 20 :sides 48 :rings 24)))
  (format t "torus vol     = ~a (2 pi^2 R r^2 = ~a)~%" (geom:volume tr) (* 2 9.8696044 60 20 20)))
(let ((cn (geom:cone :radius 50 :height 120 :sides 64)))
  (format t "cone vol      = ~a (pi r^2 h / 3 = ~a)~%" (geom:volume cn) (/ (* 3.14159265 50 50 120) 3)))

;;; a joint chain: base -> j1 -> link1 -> j2 -> link2
(let* ((base (geom:make-node))
       (j1 (geom:make-node :parent base))
       (l1 (geom:cylinder :radius 10 :height 100))
       (j2 (geom:make-node :translation (v3 0 0 100)))
       (l2 (geom:cylinder :radius 8 :height 80)))
  (geom:attach j1 l1) (geom:attach j1 j2) (geom:attach j2 l2)
  (format t "~%chain: l2 origin       = ~a~%" (p3 (geom:world-translation l2)))
  (geom:turn j2 (/ 3.14159265 2) :y)
  (format t "chain: j2 +90deg y     = ~a  x-axis now ~a~%"
          (p3 (geom:world-translation l2))
          (p3 (linalg:row (geom:world-rotation l2) 0)))
  (geom:move base (v3 0 0 500))
  (format t "chain: base +500z      = ~a~%" (p3 (geom:world-translation l2)))
  (format t "chain: l2 bounds centre= ~a~%" (p3 (geom:bounds-center (geom:bounds l2))))
  (format t "chain: union bounds    = ~a~%"
          (p3 (geom:bounds-extent (geom:bounds (list l1 l2))))))

;;; transform algebra
(let* ((a (geom:make-transform :translation (v3 1 2 3) :axis :z :angle 0.5))
       (b (geom:make-transform :translation (v3 -4 0 2) :axis :x :angle -1.1))
       (p (v3 7 -3 2))
       (round-trip (geom:transform-point (geom:invert a) (geom:transform-point a p))))
  (format t "~%invert round-trip      = ~a (expect (7 -3 2))~%" (p3 round-trip))
  (format t "compose == apply twice = ~a~%"
          (p3 (linalg:sub (geom:transform-point (geom:compose a b) p)
                          (geom:transform-point a (geom:transform-point b p))))))

;;; the mesh cache
(let ((s (geom:sphere :radius 10 :sides 32 :stacks 16)))
  (format t "~%sphere(32x16): ~a facets -> ~a triangles, ~a edge floats~%"
          (length (geom:facets-of s)) (geom:mesh-triangle-count s)
          (length (geom:wireframe s)))
  (format t "mesh is cached: ~a~%" (eq (geom:mesh s) (geom:mesh s))))
