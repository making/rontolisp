;; A rontolisp library the JVM ecosystem can consume (doc/en/guides/jvm-library.md).
;;
;; Compile it as a library class -- the -o path is the Java package, --no-main
;; drops the command entry point:
;;
;;   rontolisp kernels-library.lisp -o com/example/Kernels.class --no-main
;;
;; The class then carries the typed methods the directives below declare:
;;
;;   package com.example;
;;   public class Kernels {
;;     public static double scaledSum(double a, double b);
;;     public static long   fact(long n);
;;     public static String greet(String name);
;;     public static double norm2(RontoFloatArray x);
;;     public static RontoFloatArray axpy(double a, RontoFloatArray x, RontoFloatArray y);
;;     public static double cell(RontoFloatArray m, int i, int j);
;;   }
;;
;; The three array kernels cross on RontoFloatArray, the packed float-array handle
;; the compiler writes beside this class (am/ik/rontolisp/runtime/). It holds the
;; packed representation across calls, so a chain of kernels copies nothing -- see
;; bench/ for what the alternative costs.
;;
;; JvmExportExampleTest compiles this file and calls each method from Java.

(defvar *scale* 2.0)

(defun scaled-sum (a b) (* *scale* (+ a b)))

(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))

(defun greet (name) (concatenate 'string "hello, " name))

(defun norm2 (x) (sqrt (vec:dot x x)))

(defun axpy (a x y) (vec:add (vec:scale x a) y))

(defun cell (m i j) (aref m i j))

(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)

(rontolisp:jvm-export 'fact :params '(:s64) :returns :s64)

(rontolisp:jvm-export 'greet :params '(:string) :returns :string)

(rontolisp:jvm-export 'norm2 :params '(:float-vector) :returns :float)

(rontolisp:jvm-export 'axpy
                      :params '(:float :float-vector :float-vector)
                      :returns :float-vector)

(rontolisp:jvm-export 'cell :params '(:float-matrix :s32 :s32) :returns :float)
