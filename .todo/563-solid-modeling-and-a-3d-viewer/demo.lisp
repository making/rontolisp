;;;; demo.lisp -- SPIKE: the whole surface in one program.
(require :scene "scene.lisp")

(defun v3 (x y z) (geom:vec3 x y z))

(defvar *v* (scene:viewer :title "rontolisp solid modeling (spike)"))
(scene:grid *v* :extent 600 :spacing 50)

;;; every primitive constructor, on a shelf
(defvar *shelf*
  (list (geom:box '(120 120 120) :color (v3 0.85 0.35 0.30) :label "box")
        (geom:cylinder :radius 60 :height 140 :sides 28 :color (v3 0.35 0.75 0.85))
        (geom:cone :radius 70 :height 160 :sides 28 :color (v3 0.95 0.75 0.25))
        (geom:sphere :radius 70 :sides 28 :stacks 18 :color (v3 0.55 0.85 0.45))
        (geom:torus :radius 70 :tube 25 :sides 36 :rings 18 :color (v3 0.80 0.50 0.90))
        (geom:extrusion '((-60.0 -50.0 0.0) (60.0 -50.0 0.0) (0.0 60.0 0.0))
                        :along 120 :color (v3 0.90 0.60 0.45))))

(let ((x -420.0))
  (dolist (s *shelf*)
    (geom:move s (v3 x -280 5))
    (setq x (+ x 170.0))))

;;; a three-joint chain: joints are bare nodes, links are solids attached to them
(defvar *base* (geom:make-node :translation (v3 0 150 0)))
(defvar *j1* (geom:make-node :parent *base*))
(defvar *j2* (geom:make-node :translation (v3 0 0 220) :parent *j1*))
(defvar *j3* (geom:make-node :translation (v3 0 0 190) :parent *j2*))

(defvar *pedestal* (geom:cylinder :radius 70 :height 40 :sides 28
                                  :color (v3 0.30 0.34 0.42)))
(defvar *link1* (geom:cylinder :radius 32 :height 220 :sides 24
                               :color (v3 0.78 0.80 0.86)))
(defvar *link2* (geom:cylinder :radius 26 :height 190 :sides 24
                               :color (v3 0.78 0.80 0.86)))
(defvar *hand* (geom:box '(70 40 90) :color (v3 1.00 0.55 0.20)))

(geom:attach *base* *pedestal*)
(geom:attach *j1* *link1*)
(geom:attach *j2* *link2*)
(geom:attach *j3* *hand*)
(geom:move *hand* (v3 0 0 45))

(apply #'scene:add *v* (append *shelf* (list *pedestal* *link1* *link2* *hand*)))
(scene:fit *v*)
(scene:camera *v* :elevation 0.42 :azimuth 0.8)

;;; the joints are set absolutely each frame -- `place`, not accumulated `turn`
(defvar *t0* (/ (get-internal-real-time) 1000.0))

(scene:animate
 *v*
 (lambda ()
   (let ((tm (- (/ (get-internal-real-time) 1000.0) *t0*)))
     (geom:place *j1* :axis :z :angle (* 0.9 (sin (* 0.7 tm))))
     (geom:place *j2* :axis :y :angle (+ -0.6 (* 0.5 (sin (* 0.9 tm)))))
     (geom:place *j3* :axis :y :angle (* 0.8 (sin (* 1.3 tm)))))))

(format t "device : ~a~%"
        (objc:send (objc:send (metal:device (scene::ctx-of *v*)) "name") "UTF8String"))
(format t "solids : ~a, triangles ~a~%"
        (length (scene:contents *v*))
        (let ((n 0))
          (dolist (s (scene:contents *v*) n)
            (setq n (+ n (geom:mesh-triangle-count s))))))
(scene:wait *v*)
