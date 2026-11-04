(in-package #:cl-colors2)

(defun print-hex (color &key short (hash T) alpha destination)
  "Converts a COLOR to its hexadecimal RGB string representation.  If
SHORT is specified each component gets just one character.

A hash character (#) is prepended if HASH is true (default).

If ALPHA is provided as a float, it is included as an ALPHA component.

DESTINATION is the first argument to FORMAT, by default NIL."
  (flet ((c (x factor) (round (* x factor))))
    (let* ((rgb   (as-rgb color))
           (red   (rgb-red   rgb))
           (green (rgb-green rgb))
           (blue  (rgb-blue  rgb))
           (factor (if short 15 255)))
      (format destination (if short
                              "~@[~C~]~X~X~X~@[~X~]"
                              "~@[~C~]~2,'0X~2,'0X~2,'0X~@[~X~]")
              (and hash #\#)
              (c red   factor)
              (c green factor)
              (c blue  factor)
              (and alpha (c alpha factor))))))

;; Backwards-compatible alias:
(setf (fdefinition 'print-hex-rgb) (fdefinition 'print-hex))

(defun print-css-rgb/a (color &key alpha destination)
  "Print (to the DESTINATION) the COLOR as rgb/rgba CSS string.

When ALPHA is provided (as a floating point number), force rgba()
string. Otherwise use plain rgb().

Examples:
(print-css-rgb/a (rgb/a-to-rgb \"rgb(100, 200, 8)\"))
=> \"rgb(100, 200, 8)\"

(print-css-rgb/a (hex-to-rgb \"FFAA66\") :alpha .8)
=> \"rgba(255, 170, 102, 204)\""
  (let ((rgb (as-rgb color)))
    (format destination "rgb~@[~*a~](~,2f ~,2f ~,2f~@[ / ~,2f~])"
            alpha
            (* 255 (rgb-red rgb))
            (* 255 (rgb-green rgb))
            (* 255 (rgb-blue rgb))
            (when alpha
              (* 255 alpha)))))

(defun print-css-hsl (color &key destination)
  "Print (to the DESTINATION) the COLOR as hsl() CSS string."
  (let ((hsl (as-hsl color)))
    (format destination
            "hsl(~,2f ~,2f% ~,2f%)"
            (hsl-hue hsl)
            (* 100 (hsl-saturation hsl))
            (* 100 (hsl-lightness hsl)))))

(defun print-css-hsv (color &key destination)
  "Print (to the DESTINATION) the COLOR as hsv() CSS string."
  (let ((hsv (as-hsv color)))
    (format destination
            "hsv(~,2f ~,2f% ~,2f%)"
            (hsv-hue hsv)
            (* 100 (hsv-saturation hsv))
            (* 100 (hsv-value hsv)))))
