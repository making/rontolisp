# robot-arm.lisp — 3D inverse kinematics with minimum-jerk motion, in Lisp

Click (or tap) anywhere on the page: the arm reaches for that point in 3D —
the three-finger gripper opens for the flight and closes on the target when
it arrives. Drag to orbit the camera, scroll to zoom. Every part of the
controller — and the renderer — runs in Lisp, compiled to WebAssembly:

- **Inverse kinematics** — three solvers, switchable live from the HUD:
  - **The Jacobian method with damped least squares** (the default): every
    joint is a ball joint contributing three columns `a_k x (p_tip - p_i)`
    to the position Jacobian `J`, and each iteration solves the damped
    normal equations `dθ = J^T (J J^T + λ²I)^-1 e` — `J J^T` built with
    `linalg:matmul` / `linalg:transpose`, the 3x3 system solved exactly by
    `linalg:solve` (Gaussian elimination), the rotations applied linearized
    and the link lengths re-normalized. This is the classic numerical IK
    used in robotics, and it is real matrix computation every frame.
  - **[FABRIK](http://www.andreasaristidou.com/FABRIK.html)** (Forward And
    Backward Reaching Inverse Kinematics), the geometric contrast: pin the
    hand to the target and walk the chain back to the base, pin the base
    and walk forward, repeat — no matrices at all, just distance
    re-normalization.
  - **The analytic (closed-form) method + forward kinematics**, the
    industrial-robot contrast: base yaw from `atan2`, the elbow from the
    law of cosines — the textbook 2R solution, exact in one shot, no
    iteration. Closed forms only exist for specific structures, so the
    chain is split into two rigid groups at the joint that best balances
    their lengths and the extra joints ride frozen — you can *see* that
    limitation in the pose. The joint positions then come from **forward
    kinematics**: a chain of 4x4 homogeneous transforms
    `yaw(q0) . rotz(q1) . transx(s) . rotz(q2) . transx(s)` combined with
    `linalg:matmul`, each joint read off the translation column.

  Toggle between them mid-move and watch the poses differ: DLS spreads the
  motion smoothly across all joints, FABRIK drags the chain geometrically,
  and the analytic arm moves like a rigid two-segment machine.
- **Minimum-jerk interpolation** — the commanded hand position travels from
  where it is to where you clicked along the profile
  `s(u) = 10u^3 - 15u^4 + 6u^5`, the unique 5th-order polynomial with zero
  velocity and zero acceleration at both ends (Flash & Hogan 1985, the
  classic model of smooth human reaching motion). Clicks are clamped into
  the reachable shell, and a click mid-flight restarts the profile from the
  currently commanded point, so the hand never jumps.
- **The gripper** — the hand is a palm and three two-phalanx fingers spaced
  120 degrees around the tool axis. The chain FABRIK solves includes one
  extra rigid "tool" link from the wrist to the grasp point, so the solver
  itself decides the approach direction and pins the point *between the
  fingertips* — not the wrist — to your click. The grip eases open when a
  move starts and closes on arrival, with finger angles chosen so the
  closed tips meet exactly at that grasp point.
- **The 3-D renderer** — Lisp tessellates the machine every frame (tapered
  cylinders for the links, the fingers and the pedestal, spheres for the
  joints, cones for the RGB = XYZ axis arrows at the origin), computes the
  world-space normals and the click-ray unprojection, and draws two passes:
  lit triangles, then additive glow sprites (the target ring and the hand's
  trail) that read depth but do not write it. The orbit camera's matrices
  are ordinary rank-2 `(4 4)` arrays in textbook row/column convention, and
  `VP = P x V` is one `linalg:matmul` — the upload transposes into WebGL's
  column-major order as it stages the 16 floats.

JavaScript is the same host boundary as [`webgl-galaxy`](../webgl-galaxy)
and [`webgl-heat3d`](../webgl-heat3d): one-line WebGL2 bindings over a
handle table plus two staging `Float32Array`s (the same bulk-float idea as
[`webgl-cube`](../webgl-cube)), pointer gestures forwarded as exported-
function calls, and the HUD — no kinematics, no matrices, no rendering
logic of its own.

**Live demo:** <https://making.github.io/rontolisp/webgl-robot-arm/> (this
directory is published as a subpath of the GitHub Pages site by
`.github/workflows/pages.yaml`).

## What's in here

| File              | Purpose                                                                        |
| ----------------- | ------------------------------------------------------------------------------ |
| `robot-arm.lisp`  | The program: trajectory, gripper, tessellator, camera, shaders — and the `(load ...)`s below. |
| `ik-jacobian.lisp` | Solver 1: damped-least-squares Jacobian IK (`linalg:matmul`/`transpose`/`solve`). |
| `ik-fabrik.lisp`  | Solver 2: FABRIK, the geometric method (no matrices).                          |
| `ik-analytic.lisp` | Solver 3: the closed form (atan2 + law of cosines) + matrix forward kinematics. |
| `index.html`      | The host page: one-line WebGL2 bindings, pointer gestures + the HUD.           |
| `robot-arm.wasm`  | The compiled `--no-wasi` reactor (checked in).                                 |
| `build.sh`        | Recompiles `robot-arm.lisp` to `robot-arm.wasm`.                               |

Each solver lives in its own file; `robot-arm.lisp` pulls them in with
literal top-level `(load "ik-....lisp")` forms, which the compiler splices
in **at compile time** (paths resolve relative to the loading file), so the
`.wasm` sees the definitions natively — the same mechanism the
[Minesweeper example](../minesweeper) uses to share its core between the
browser and Swing builds.

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

The joint positions live in rank-1 Lisp arrays, the precomputed unit sphere
and the camera matrices in rank-2 ones (multiplied with the `linalg`
package). A click arrives as clip coordinates; Lisp turns it
into a ray from the eye and intersects it with the plane through the orbit
centre facing the camera, so "click where you see" works from any viewpoint.
The links selector (3-6) re-initializes the chain; every link count tapers
geometrically to the same total reach. The HUD's "to target" meter polls the
exported `ikError` (the distance from the hand to the goal, which counts
down to 0.000 as each move completes).

## Building and running

```bash
# from the repo root, once:
./mvnw clean package

# recompile the .wasm after editing robot-arm.lisp:
examples/webgl-robot-arm/build.sh

# serve and open (any static file server works):
jwebserver -p 8000 --directory "$PWD/examples/webgl-robot-arm"
open http://localhost:8000/
```

The page needs a browser with WebAssembly GC support (Chrome 119+,
Firefox 120+, Safari 18.2+, Edge 119+).

## Notes

- The module is compiled with `--no-wasi`, so its *only* imports are the host
  functions declared in `robot-arm.lisp` — the import object is the whole
  embedding API. `--optimize` tree-shakes the runtime down to what the
  program reaches (the array runtime and the reachable `linalg` definitions
  ship with it, as in `webgl-heat3d`).
- Input is delivered through exported functions (`pointer`, `orbit`, `zoom`),
  not imports: the page classifies the gesture (a sub-4-pixel press is a
  click, anything longer is an orbit drag) and pushes it in; the camera
  itself — yaw, pitch, radius, matrices — lives in Lisp. Exports in, imports
  out — the module never polls the host for input.
- Functions cross the WASM boundary with at most 7 parameters, so vertex
  colors ride a latched "current color" (`set-color`) and tube radii a
  latched pair (`set-radii`) instead of extra arguments.
- The axis arrows and the pedestal never move, so they are tessellated once
  into the front of the vertex buffer at `init`; each frame re-uploads only
  the arm's vertices behind them.
- On the interpreter and JVM backends the `rontolisp:wasm-import` directives
  define stubs that signal an error when called, so this program is
  WASM-only by nature (there is no host to draw with elsewhere).
