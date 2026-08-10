# robot-arm.lisp — 3D inverse kinematics with minimum-jerk motion, in Lisp

Click anywhere on the page: the arm reaches for that point in 3D, the
three-finger gripper opening for the flight and closing on arrival. Drag to
orbit, scroll to zoom. The controller and the renderer both run in Lisp,
compiled to WebAssembly.

**Live demo:** <https://making.github.io/rontolisp/webgl-robot-arm/>

## Three IK solvers, switchable live from the HUD

- **Jacobian with damped least squares** (the default): every joint is a ball
  joint contributing three columns `a_k x (p_tip - p_i)` to the position
  Jacobian `J`, and each iteration solves `dθ = J^T (J J^T + λ²I)^-1 e` — `J J^T`
  built with `linalg:matmul`/`transpose`, the 3×3 system solved exactly by
  `linalg:solve`. The classic numerical IK of robotics, and real matrix
  computation every frame.
- **[FABRIK](http://www.andreasaristidou.com/FABRIK.html)**, the geometric
  contrast: pin the hand to the target and walk the chain back to the base, pin
  the base and walk forward, repeat — no matrices, just distance
  re-normalization.
- **The analytic closed form + forward kinematics**, the industrial-robot
  contrast: base yaw from `atan2`, the elbow from the law of cosines, exact in
  one shot. Closed forms only exist for specific structures, so the chain is
  split into two rigid groups and the extra joints ride frozen — you can *see*
  that limitation in the pose. Joint positions then come from a chain of 4×4
  homogeneous transforms combined with `linalg:matmul`.

Toggle between them mid-move and the poses differ: DLS spreads the motion
across all joints, FABRIK drags the chain geometrically, and the analytic arm
moves like a rigid two-segment machine.

## The rest of the program

- **Minimum-jerk interpolation** — the commanded hand position travels along
  `s(u) = 10u³ - 15u⁴ + 6u⁵`, the unique 5th-order polynomial with zero velocity
  and acceleration at both ends (Flash & Hogan 1985, the classic model of human
  reaching). Clicks are clamped into the reachable shell, and a click mid-flight
  restarts the profile from the currently commanded point, so the hand never
  jumps.
- **The gripper** — a palm and three two-phalanx fingers 120° apart. The solved
  chain includes one rigid "tool" link from the wrist to the grasp point, so the
  solver picks the approach direction and pins the point *between the
  fingertips* — not the wrist — to your click.
- **The renderer** — Lisp tessellates the machine every frame (tapered cylinders
  for links and fingers, spheres for joints, cones for the RGB = XYZ axis
  arrows), computes world-space normals and the click-ray unprojection, and
  draws two passes: lit triangles, then additive glow sprites that read depth
  but do not write it. `VP = P × V` is one `linalg:matmul`, transposed into
  WebGL's column-major order as it stages the 16 floats.

JavaScript is the same host boundary as [`webgl-galaxy`](../webgl-galaxy): one-line
WebGL2 bindings over a handle table, two staging `Float32Array`s, pointer
gestures forwarded as exported-function calls, and the HUD — no kinematics, no
matrices, no rendering logic of its own.

## What's in here

| File | Purpose |
| --- | --- |
| `robot-arm.lisp` | The program: trajectory, gripper, tessellator, camera, shaders |
| `ik-jacobian.lisp` | Solver 1: damped-least-squares Jacobian IK |
| `ik-fabrik.lisp` | Solver 2: FABRIK, the geometric method (no matrices) |
| `ik-analytic.lisp` | Solver 3: the closed form + matrix forward kinematics |
| `index.html` | The host page: WebGL2 bindings, pointer gestures, the HUD |
| `robot-arm.wasm` | The compiled `--no-wasi` reactor (checked in) |
| `build.sh` | Recompiles `robot-arm.lisp` |

Each solver lives in its own file, pulled in by literal top-level
`(load "ik-....lisp")` forms the compiler splices in **at compile time** (paths
resolve relative to the loading file) — the same mechanism the
[Minesweeper example](../minesweeper) uses to share its core between the browser
and Swing builds. The WebGL2 boundary comes in the same way from the shared `gl`
package, [`../webgl-common/gl.lisp`](../webgl-common); the page's matching
bindings are generated from the same `gl.wit`.

## The controller is three small functions

```lisp
;; the minimum-jerk position profile: zero velocity/acceleration at both ends
(defun min-jerk (u)
  (* u u u (+ 10.0 (* u (+ -15.0 (* u 6.0))))))

;; each frame: advance the commanded hand position along the profile ...
(setq *tx* (+ *sx* (* s (- *gx* *sx*))))

;; ... and solve the chain onto it -- the damped-least-squares step is one
;; linalg expression: dtheta = J^T (J J^T + lambda^2 I)^-1 e
(let ((a (linalg:matmul jac (linalg:transpose jac))))
  (dotimes (k 3) (setf (aref a k k) (+ (aref a k k) +dls-lambda2+)))
  (linalg:matmul (linalg:transpose jac) (linalg:solve a e)))
```

A click arrives as clip coordinates; Lisp turns it into a ray from the eye and
intersects it with the plane through the orbit centre facing the camera, so
"click where you see" works from any viewpoint. The links selector (3-6)
re-initializes the chain, every link count tapering to the same total reach.

## Building and running

```bash
./mvnw clean package                        # from the repo root, once
examples/browser/webgl-robot-arm/build.sh   # recompile the .wasm

# the page imports ../webgl-common/gl-imports.js, so serve examples/browser:
jwebserver -p 8000 --directory "$PWD/examples/browser"
open http://localhost:8000/webgl-robot-arm/
```

Needs a browser with WebAssembly GC (Chrome 119+, Firefox 120+, Safari 18.2+).

## Notes

- `--no-wasi` means the module's *only* imports are the host functions declared
  in the program and the shared `gl` package — the import object is the whole
  embedding API. `--optimize` tree-shakes the runtime down to what is reached;
  the shipped module imports 44 functions, the most of any of these demos.
- Input is delivered through exported functions (`pointer`, `orbit`, `zoom`),
  not imports: the page classifies the gesture and pushes it in, and the camera
  itself lives in Lisp. Exports in, imports out — the module never polls the
  host.
- Functions cross the WASM boundary with at most 7 parameters, so vertex colors
  ride a latched `set-color` and tube radii a latched `set-radii`.
- The axis arrows and the pedestal never move, so they are tessellated once into
  the front of the vertex buffer at `init`.
- On the interpreter and JVM the `wasm-import` directives define stubs that
  signal, so this program is WASM-only by nature — there is no host to draw with
  elsewhere.
