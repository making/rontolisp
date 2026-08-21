;; Phase 4b's precision question: HOW FAR does a device transcendental sit from the
;; scalar defun, per member and per width? This program prints the answers; it does not
;; compute the difference, because the whole point is that the two sides are two RUNS.
;;
;; Run it twice, once with the flag and once without, and diff the two files:
;;
;;   JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
;;   java -jar $JAR elementwise-precision.lisp -o Prec.class --simd   # keep the name path-free
;;   java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED Prec > cpu.txt
;;   java -jar $JAR elementwise-precision.lisp -o PrecG.class --simd --gpu
;;   java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED PrecG > gpu.txt
;;   python3 - cpu.txt gpu.txt <<'PY'
;;   import sys, math
;;   rows = lambda p: [l.split() for l in open(p)]
;;   worst = {}
;;   for c, g in zip(rows(sys.argv[1]), rows(sys.argv[2])):
;;       key, x, y = (c[0], c[1]), float(c[3]), float(g[3])
;;       if x != y:
;;           rel = abs(x - y) / max(abs(x), 1e-300)
;;           eps = 2.0 ** -52 if c[1] == 'f64' else 2.0 ** -23
;;           if rel > worst.get(key, (0,))[0]:
;;               worst[key] = (rel, rel / eps)
;;   for (m, w), (rel, ulps) in worst.items():
;;       print(f"{m:6s} {w} {rel:.3g} ({ulps:.1f} ulps)")
;;   PY
;;
;; The .kb/gpu.md table is that script's output on a GB10, over the JVM class output.
;; The interpreter answers the same values -- it is the same kernel and the same defun --
;; but the class is what the numbers were taken on, and a flag comparison is only ever
;; valid within one backend.
;;
;; Three things the shape of this program is deciding:
;;
;; - n is 16384, the element threshold exactly. Below it the device declines and the two
;;   runs are byte-identical, which is a DIFFERENT assertion (LinalgGpuDeclineTest pins
;;   it); at it, every accepted member is on the device and nothing else has changed.
;; - each member gets a linspace over a domain it is DEFINED on and where it is not
;;   flat. asin/acos stop short of +-1 (the derivative is infinite there, so the last
;;   ulps say more about the linspace than about either library) and log starts above 0.
;; - 400 samples of the 16384, spread by a stride that is coprime with nothing in
;;   particular -- the values printed are a sample, but the ARRAY is full size, so the
;;   device saw the same work it sees in a real program.
;;
;; Not a GPU program in the sense the Java probes are: it runs anywhere, and on a machine
;; with no device the two files are identical, which is itself the check that the flag is
;; the only variable.
(defconstant +n+ 16384)

(defconstant +stride+ 41)

(defun emit (name width a)
  (let ((values (linalg:to-list a))
        (i 0))
    (dolist (x values)
      (when (zerop (mod i +stride+))
        (format t "~a ~a ~d ~a~%" name width i x))
      (setq i (1+ i)))))

(defmacro probe (name lo hi member)
  `(progn
     (emit ,name "f64" (,member (linalg:linspace ,lo ,hi +n+)))
     (emit ,name "f32" (,member (linalg:linspace (float ,lo 1.0) (float ,hi 1.0) +n+
                                                 :element-type 'single-float)))))

(probe "exp" -5.0 5.0 linalg:exp)
(probe "log" 0.01 10.0 linalg:log)
(probe "tanh" -5.0 5.0 linalg:tanh)
(probe "sin" -3.0 3.0 linalg:sin)
(probe "cos" -3.0 3.0 linalg:cos)
(probe "tan" -1.5 1.5 linalg:tan)
(probe "asin" -0.99 0.99 linalg:asin)
(probe "acos" -0.99 0.99 linalg:acos)
(probe "atan" -5.0 5.0 linalg:atan)
(probe "sinh" -5.0 5.0 linalg:sinh)
(probe "cosh" -5.0 5.0 linalg:cosh)
(probe "erf" -3.0 3.0 linalg:erf)
