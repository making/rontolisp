;;;; scene-solids.lisp -- every geom primitive on a shelf, in a window.
;;;;
;;;; The shortest demonstration that the model and the picture are one image:
;;;; `geom` builds the solids, `scene` opens a window over Metal and draws them,
;;;; and nothing is required and nothing is copied -- both packages ship inside
;;;; the interpreter (doc/en/guides/solid-modeling.md).
;;;;
;;;; Drag to orbit, shift-drag to pan, scroll to dolly, and resize the window.
;;;; The gauge is `geom:volume`, printed to the terminal beside each solid: a
;;;; tessellated primitive is INSCRIBED in its smooth ideal, so every number
;;;; approaches the closed form from below.
;;;;
;;;; Run it (macOS, on any of the three backends that carry the binding):
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/scene-solids.lisp
;;;;   rontolisp examples/macos/scene-solids.lisp
;;;;   rontolisp examples/macos/scene-solids.lisp -o Solids.class --class-name Solids && java Solids

(defvar *view* (scene:viewer :title "geom solids" :width 1040 :height 660))

;;; --- the shelf ---------------------------------------------------------------
;;;
;;; Each primitive is a noun constructor taking keywords, and every one of them
;;; answers a SOLID -- which is a node, so `geom:place` poses it and the viewer
;;; needs no per-primitive knowledge at all.

(defvar *shelf*
  (list (geom:box '(120 120 120) :color (geom:vec3 0.90 0.45 0.40) :label "box")
        (geom:cylinder :radius 55
                       :height 140
                       :sides 48
                       :color (geom:vec3 0.45 0.75 0.95)
                       :label "cylinder")
        (geom:cone :radius 60
                   :height 150
                   :sides 48
                   :color (geom:vec3 0.95 0.80 0.35)
                   :label "cone")
        (geom:sphere :radius 66
                     :sides 40
                     :stacks 28
                     :color (geom:vec3 0.55 0.90 0.55)
                     :label "sphere")
        (geom:torus :radius 70
                    :tube 24
                    :sides 48
                    :rings 24
                    :color (geom:vec3 0.80 0.55 0.95)
                    :label "torus")
        ;; the general prism: a closed profile swept along a vector
        (geom:extrusion '((-60.0 -40.0 0.0) (60.0 -40.0 0.0) (0.0 60.0 0.0))
                        :along 130
                        :color (geom:vec3 0.95 0.60 0.75)
                        :label "prism")
        ;; a profile turned about z, capped where it leaves the axis
        (geom:revolution '((0.0 0.0 -70.0) (60.0 0.0 -40.0) (26.0 0.0 10.0)
                           (44.0 0.0 60.0) (0.0 0.0 78.0))
                         :sides 48
                         :color (geom:vec3 0.60 0.85 0.90)
                         :label "vase")))

(let ((x -540.0))
  (dolist (s *shelf*)
    (geom:place s :translation (geom:vec3 x 0 90))
    (format t "~a: volume ~,1f~%" (geom:label-of s) (geom:volume s))
    (setq x (+ x 180.0))))

(apply #'scene:add (cons *view* *shelf*))

;;; --- the view ----------------------------------------------------------------

(scene:grid *view* :extent 700 :spacing 50)
(scene:axes *view* :both) ; the world frame AND each solid's own
(scene:camera *view* :azimuth 1.05 :elevation 0.38)
(scene:fit *view*)
(scene:refresh *view*)
(scene:wait *view*)
