;; Runs when test-op asks, not when the system loads.
(classes-tool::note :later-file-ran (if (boundp '*classes-plain*) *classes-plain* :unbound))
