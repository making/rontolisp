;;;; scene-model-file.lisp -- a mesh off disk, in a window.
;;;;
;;;; `geom:read-obj` / `geom:read-stl` / `geom:read-ply` / `geom:read-gltf` /
;;;; `geom:read-model` answer an ordinary `geom:solid` (a glTF, being a scene,
;;;; answers a LIST of them), so a mesh someone else authored goes into the
;;;; viewer exactly the way a `geom:box` does -- and `geom:volume`,
;;;; `geom:bounds` and the booleans all apply to it unchanged
;;;; (doc/en/guides/solid-modeling.md). Hand it a `.obj`, `.stl`, `.ply`,
;;;; `.gltf` or `.glb` and the format comes out of the file's own bytes.
;;;;
;;;; Run it (macOS, on any of the three backends that carry the binding):
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/scene-model-file.lisp
;;;;   rontolisp examples/macos/scene-model-file.lisp
;;;;   rontolisp examples/macos/scene-model-file.lisp -o Model.class --class-name Model && java Model
;;;;
;;;; Hand it a file of your own and it views that instead:
;;;;
;;;;   java -jar target/... examples/macos/scene-model-file.lisp -- ~/Downloads/bunny.obj
;;;;
;;;; With no argument it writes its own two files first, so the example needs no
;;;; asset and no download: a lathed vase goes out as a Wavefront OBJ and a
;;;; chamfered bracket as a binary STL, and both come straight back in. That
;;;; round trip is also the honest test of a reader -- the volume that comes
;;;; back has to be the volume that went out.
;;;;
;;;; Drag to orbit, shift-drag to pan, scroll to dolly.

;;; --- writing the two files ----------------------------------------------------
;;;
;;; geom READS model files and does not write them, so the writers live here.
;;; Both are a dozen lines, which is the other half of the point: the formats
;;; are simple, and what a reader has to get right is the dialects and the
;;; winding rather than the arithmetic.

(defun write-obj (path solid)
  (with-open-file (out path :direction :output :if-exists :supersede)
    (format out "# written by scene-model-file.lisp~%")
    (let ((v (geom:vertices-of solid)))
      (dotimes (i (first (linalg:shape v)))
        (format out "v ~a ~a ~a~%" (aref v i 0) (aref v i 1) (aref v i 2))))
    (dolist (facet (geom:facets-of solid))
      (write-string "f" out)
      (dolist (index facet) (format out " ~a" (+ index 1)))
      (terpri out)))
  path)

(defun write-stl (path solid)
  (let ((mesh (geom:mesh solid))
        (triangle (make-array 12 :element-type 'single-float))
        (header
         (make-array 80 :element-type '(unsigned-byte 8) :initial-element 32))
        (triangles (make-array 1 :element-type '(unsigned-byte 32)))
        (attribute (make-array 1 :element-type '(unsigned-byte 16))))
    (with-open-file (out path
                         :direction :output
                         :if-exists :supersede
                         :element-type '(unsigned-byte 8))
      (write-sequence header out)
      (setf (aref triangles 0) (geom:mesh-triangle-count solid))
      (write-sequence triangles out)
      (setf (aref attribute 0) 0)
      ;; geom:mesh is 18 floats a triangle -- three corners of position plus
      ;; normal -- and binary STL wants one normal then the three positions.
      (do ((i 0 (+ i 18)))
          ((>= i (length mesh)))
        (dotimes (k 3) (setf (aref triangle k) (aref mesh (+ i 3 k))))
        (dotimes (corner 3)
          (dotimes (k 3)
            (setf (aref triangle (+ 3 (* corner 3) k))
                  (aref mesh (+ i (* corner 6) k)))))
        (write-sequence triangle out)
        (write-sequence attribute out))))
  path)

;;; --- the two models, and the round trip ---------------------------------------

(defun vase ()
  (geom:revolution '((0 0 0) (56 0 0) (44 0 30) (30 0 78) (46 0 130) (52 0 168)
                     (40 0 176) (36 0 168) (30 0 130) (18 0 78) (26 0 26)
                     (0 0 20))
                   :sides 64))

(defun bracket ()
  ;; A plate with a slot down the middle and a bolt hole either side of it: a
  ;; shape with a hole in it, which is what a mesh format has to survive.
  (let ((plate
         (geom:difference (geom:box '(200 120 40)) (geom:box '(40 200 60)))))
    (dolist (x '(-70 70) plate)
      (let ((hole (geom:cylinder :radius 18 :height 200 :sides 32)))
        (geom:place hole :translation (geom:vec3 x 0 -100))
        (setq plate (geom:difference plate hole))))))

(defun show (solid)
  (format t "~a~%  volume ~a  area ~a  triangles ~a~%" solid (geom:volume solid)
          (geom:surface-area solid) (geom:mesh-triangle-count solid))
  solid)

(defvar *given* (uiop:command-line-arguments))

(defvar *models*
  (if *given*
      ;; Whatever was handed over: the format comes out of the file's own bytes.
      ;; A glTF answers a LIST of solids (it is a scene, not a mesh), so the
      ;; answer is spliced rather than assumed to be one solid.
      (let ((out nil))
        (dolist (path *given* (nreverse out))
          (format t "reading ~a~%" path)
          (let ((m (geom:read-model path :label path)))
            (dolist (s (if (listp m) m (list m))) (push (show s) out)))))
      (let ((vase-path "scene-model-file-vase.obj")
            (bracket-path "scene-model-file-bracket.stl"))
        (format t "no file given; writing two and reading them back~%")
        (write-obj vase-path (show (vase)))
        (write-stl bracket-path (show (bracket)))
        (list (show
               (geom:read-obj vase-path
                              :color (geom:vec3 0.86 0.72 0.50)
                              :label "vase (from OBJ)"))
              (show
               (geom:read-stl bracket-path
                              :color (geom:vec3 0.45 0.70 0.92)
                              :label "bracket (from STL)"))))))

;;; --- the viewer ---------------------------------------------------------------
;;;
;;; :solid is the shading a mesh off disk wants -- the default :both draws the
;;; wireframe over the triangles, which on a scan is a dark stipple -- and
;;; scene:fit is what makes the units not matter: a scanned mesh in metres is
;;; 0.2 across and a printable part in millimetres is 200, and the camera
;;; follows whichever it is given.

(defvar *view* (scene:viewer :title "a mesh off disk" :width 1000 :height 640))

(scene:grid *view* :extent nil)
(scene:shading *view* :solid)

(let ((x 0.0))
  (dolist (solid *models*)
    (geom:place solid :translation (geom:vec3 x 0 0))
    (setq x (+ x (* 1.4 (aref (geom:bounds-extent (geom:bounds solid)) 0))))))

(scene:add *view* *models*)
(scene:fit *view*)
(scene:wait *view*)
