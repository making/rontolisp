(println (letfn [(f [x] (if (zero? x) 1 (* x (f (dec x)))))] (f 5)))
