;; Glue for the SICP amb-driver legs (.todo/856, SicpCorpusE2eTest#ambDrivers).
;; No corpus text below: everything here is written for the harness, following
;; SICP 4.1.2/4.1.3/4.3.3. Loaded AFTER the support files and
;; chapter4/section3/subsection3/16_driver_loop_amb.scm (minus its trailing
;; (driver-loop) call), which is the only corpus core file; the final
;; (driver-loop) call below replaces the stripped corpus call.

;; The expression-syntax layer of SICP 4.1.2: no corpus file defines it.
(define (self-evaluating? exp)
  (or (number? exp) (string? exp)))
(define (variable? exp) (symbol? exp))
(define (quoted? exp) (tagged-list? exp 'quote))
(define (text-of-quotation exp) (cadr exp))
(define (assignment? exp) (tagged-list? exp 'set!))
(define (assignment-variable exp) (cadr exp))
(define (assignment-value exp) (caddr exp))
(define (definition? exp) (tagged-list? exp 'define))
(define (definition-variable exp)
  (if (symbol? (cadr exp)) (cadr exp) (caadr exp)))
(define (definition-value exp)
  (if (symbol? (cadr exp))
      (caddr exp)
      (make-lambda (cdadr exp) (cddr exp))))
(define (lambda? exp) (tagged-list? exp 'lambda))
(define (lambda-parameters exp) (cadr exp))
(define (lambda-body exp) (cddr exp))
(define (make-lambda parameters body)
  (cons 'lambda (cons parameters body)))
(define (if? exp) (tagged-list? exp 'if))
(define (if-predicate exp) (cadr exp))
(define (if-consequent exp) (caddr exp))
(define (if-alternative exp)
  (if (not (null? (cdddr exp))) (cadddr exp) 'false))
(define (begin? exp) (tagged-list? exp 'begin))
(define (begin-actions exp) (cdr exp))
(define (last-exp? seq) (null? (cdr seq)))
(define (first-exp seq) (car seq))
(define (rest-exps seq) (cdr seq))
(define (cond? exp) (tagged-list? exp 'cond))
(define (cond-clauses exp) (cdr exp))
(define (cond-predicate clause) (car clause))
(define (cond-actions clause) (cdr clause))
(define (cond-else-clause? clause)
  (eq? (cond-predicate clause) 'else))
(define (cond->if exp) (expand-clauses (cond-clauses exp)))
(define (expand-clauses clauses)
  (if (null? clauses)
      'false
      (let ((first (car clauses)) (rest (cdr clauses)))
        (if (cond-else-clause? first)
            (if (null? rest)
                (sequence->exp (cond-actions first))
                (error "ELSE clause isn't last -- COND->IF" clauses))
            (make-if (cond-predicate first)
                     (sequence->exp (cond-actions first))
                     (expand-clauses rest))))))
(define (make-if predicate consequent alternative)
  (list 'if predicate consequent alternative))
(define (sequence->exp seq)
  (cond ((null? seq) seq)
        ((last-exp? seq) (first-exp seq))
        (else (make-begin seq))))
(define (make-begin seq) (cons 'begin seq))
(define (application? exp) (pair? exp))
(define (operator exp) (car exp))
(define (operands exp) (cdr exp))

;; let as derived syntax (SICP exercise 4.6): the corpus evaluator has no let
;; clause but the samples use let, so the advice below rewrites it away.
(define (let? exp) (tagged-list? exp 'let))
(define (let-bindings exp) (cadr exp))
(define (let-body exp) (cddr exp))
(define (let-vars exp) (map car (let-bindings exp)))
(define (let-exps exp) (map cadr (let-bindings exp)))
(define (let->combination exp)
  (cons (make-lambda (let-vars exp) (let-body exp))
        (let-exps exp)))

;; define-variable! of SICP 4.1.3: the corpus calls it (setup-environment,
;; analyze-definition) but never defines it.
(define (define-variable! var val env)
  (let ((frame (first-frame env)))
    (define (scan vars vals)
      (cond ((null? vars)
             (add-binding-to-frame! var val frame))
            ((eq? var (car vars)) (set-car! vals val))
            (else (scan (cdr vars) (cdr vals)))))
    (scan (frame-variables frame) (frame-values frame))))

;; The two analysis procedures the corpus calls but never defines (SICP 4.3.3).
(define (analyze-quoted exp)
  (let ((qval (text-of-quotation exp)))
    (lambda (env succeed fail) (succeed qval fail))))
(define (analyze-sequence exps)
  (define (sequentially a b)
    (lambda (env succeed fail)
      (a env (lambda (a-value fail2) (b env succeed fail2)) fail)))
  (define (loop first-proc rest-procs)
    (if (null? rest-procs)
        first-proc
        (loop (sequentially first-proc (car rest-procs))
              (cdr rest-procs))))
  (let ((procs (map analyze exps)))
    (if (null? procs) (error "Empty sequence -- ANALYZE"))
    (loop (car procs) (cdr procs))))

;; The amb dispatch the corpus leaves unwired: advise analyze so an (amb ...)
;; form delegates to the analyze-amb the corpus does ship.
(define corpus-analyze analyze)
(define (analyze exp)
  (cond ((amb? exp) (analyze-amb exp))
        ((let? exp) (analyze (let->combination exp)))
        (else (corpus-analyze exp))))

;; The corpus applies primitives through apply-in-underlying-scheme, which the
;; language does not provide; the host apply is the same operation.
(define (apply-primitive-procedure proc args)
  (apply (primitive-implementation proc) args))

;; The driver loop's prompts (SICP 4.3.3).
(define (prompt-for-input string) (newline) (display string))
(define (announce-output string) (newline) (display string))

;; The book driver loop has no EOF clause -- it only knows try-again -- so the
;; harness shadows it with one that ends the run when stdin runs out (every leg
;; stdin ends after its sample). The try-again protocol is unchanged.
(define (driver-loop)
  (define (internal-loop try-again)
    (prompt-for-input input-prompt)
    (let ((input (read)))
      (if (eof-object? input)
          'driver-terminated
          (if (eq? input 'try-again)
              (try-again)
              (begin
                (newline)
                (display ";;; Starting a new problem ")
                (ambeval input
                         the-global-environment
                         (lambda (val next-alternative)
                           (announce-output output-prompt)
                           (user-print val)
                           (internal-loop next-alternative))
                         (lambda ()
                           (announce-output
                            ";;; There are no more values of")
                           (user-print input)
                           (driver-loop))))))))
  (internal-loop
   (lambda ()
     (newline)
     (display ";;; There is no current problem")
     (driver-loop))))

;; The global environment plus the primitives the stream-free samples need
;; beyond the corpus list (car cdr cons null? display read + - *): each is a
;; host procedure wrapped as the evaluator's primitive representation.
(define the-global-environment (setup-environment))
(define (register-primitive! name proc)
  (define-variable! name (list 'primitive proc) the-global-environment))
(register-primitive! 'not not)
(register-primitive! 'list list)
(register-primitive! 'member member)
(register-primitive! 'memq memq)
(register-primitive! '= =)
(register-primitive! '< <)
(register-primitive! '> >)
(register-primitive! '<= <=)
(register-primitive! '>= >=)
(register-primitive! 'abs abs)
(register-primitive! 'eq? eq?)
(register-primitive! 'sqrt sqrt)
(register-primitive! 'integer? integer?)
(register-primitive! 'remainder remainder)
(register-primitive! 'newline newline)

(driver-loop)
