package am.ik.rontolisp;

/**
 * {@code ~r} without a radix parameter, shared by the backend suites and mirrored by the
 * `format-r-without-a-radix-spells-the-number` ci-spec case: an English cardinal,
 * {@code ~:r} an ordinal, {@code ~@r} Roman numerals, {@code ~:@r} old Roman numerals,
 * with sbcl's range limits (|n| below 10^66, Roman 1..3999, old Roman 1..4999) signalled
 * as its {@code simple-error} text, and every other parameter ignored. Each directive is
 * rendered through a LITERAL control string and through a RUNTIME one, which are
 * different implementations (the static expansion declines these directives, so both end
 * in the runtime renderer, and the rows say a future static one has to match it).
 */
public final class FormatSpelledNumbersFixture {

	private FormatSpelledNumbersFixture() {
	}

	/** The program; each row prints one list. */
	public static final String PROGRAM = """
			(defun fs-text (thunk)
			  (handler-case (funcall thunk) (error (e) (list :error (princ-to-string e)))))
			(defun fs-c0 (n) (fs-text (lambda () (format nil "~r" n))))
			(defun fs-c1 (n) (fs-text (lambda () (format nil "~:r" n))))
			(defun fs-c2 (n) (fs-text (lambda () (format nil "~@r" n))))
			(defun fs-c3 (n) (fs-text (lambda () (format nil "~:@r" n))))
			(defun fs-rt (c n) (fs-text (lambda () (format nil c n))))
			(defvar *fs-english*
			  (list 0 1 3 12 14 20 21 40 80 100 101 111 1000 1001 1100 12000 1234567 -1 -1234567
			        (expt 10 21) (expt 10 63) (1- (expt 10 66)) (expt 10 66) (- (expt 10 66))
			        (+ 1 (expt 10 66))))
			(defvar *fs-roman* (list 0 1 4 9 14 49 90 400 1994 3999 4000 4999 5000 -1))
			(print (mapcar #'fs-c0 *fs-english*))
			(print (mapcar #'fs-c1 *fs-english*))
			(print (mapcar #'fs-c2 *fs-roman*))
			(print (mapcar #'fs-c3 *fs-roman*))
			(print (mapcar (lambda (n) (fs-rt "~r" n)) *fs-english*))
			(print (mapcar (lambda (n) (fs-rt "~:r" n)) *fs-english*))
			(print (mapcar (lambda (n) (fs-rt "~@r" n)) *fs-roman*))
			(print (mapcar (lambda (n) (fs-rt "~:@r" n)) *fs-roman*))
			(print (list (fs-rt "[~,10r]" 12) (fs-rt "[~,10,'*:r]" 12) (fs-rt "[~,,'*,'x,2@r]" 14)
			             (fs-rt "~8r" 100) (fs-rt "[~10,5r]" 8)))
			(print (fs-text (lambda () (format nil "~v@r|~v:r|~vr" nil 4 nil 4 nil 4))))
			(print (fs-text (lambda () (format nil "~{~r~^ ~}|~@r|~:@r" (list 3 4 5) 9 9))))
			""";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n",
			"(\"zero\" \"one\" \"three\" \"twelve\" \"fourteen\" \"twenty\" \"twenty-one\" \"forty\" \"eighty\" \"one hundred\" \"one hundred one\" \"one hundred eleven\" \"one thousand\" \"one thousand one\" \"one thousand one hundred\" \"twelve thousand\" \"one million two hundred thirty-four thousand five hundred sixty-seven\" \"negative one\" \"negative one million two hundred thirty-four thousand five hundred sixty-seven\" \"one sextillion\" \"one vigintillion\" \"nine hundred ninety-nine vigintillion nine hundred ninety-nine novemdecillion nine hundred ninety-nine octodecillion nine hundred ninety-nine septendecillion nine hundred ninety-nine sexdecillion nine hundred ninety-nine quindecillion nine hundred ninety-nine quattuordecillion nine hundred ninety-nine tredecillion nine hundred ninety-nine duodecillion nine hundred ninety-nine undecillion nine hundred ninety-nine decillion nine hundred ninety-nine nonillion nine hundred ninety-nine octillion nine hundred ninety-nine septillion nine hundred ninety-nine sextillion nine hundred ninety-nine quintillion nine hundred ninety-nine quadrillion nine hundred ninety-nine trillion nine hundred ninety-nine billion nine hundred ninety-nine million nine hundred ninety-nine thousand nine hundred ninety-nine\" (:ERROR \"Number too large to print in English: 1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000\") (:ERROR \"Number too large to print in English: -1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000\") (:ERROR \"Number too large to print in English: 1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,001\"))",
			"(\"zeroth\" \"first\" \"third\" \"twelfth\" \"fourteenth\" \"twentieth\" \"twenty-first\" \"fortieth\" \"eightieth\" \"one hundredth\" \"one hundred first\" \"one hundred eleventh\" \"one thousandth\" \"one thousand first\" \"one thousand one hundredth\" \"twelve thousandth\" \"one million two hundred thirty-four thousand five hundred sixty-seventh\" \"negative first\" \"negative one million two hundred thirty-four thousand five hundred sixty-seventh\" \"one sextillionth\" \"one vigintillionth\" \"nine hundred ninety-nine vigintillion nine hundred ninety-nine novemdecillion nine hundred ninety-nine octodecillion nine hundred ninety-nine septendecillion nine hundred ninety-nine sexdecillion nine hundred ninety-nine quindecillion nine hundred ninety-nine quattuordecillion nine hundred ninety-nine tredecillion nine hundred ninety-nine duodecillion nine hundred ninety-nine undecillion nine hundred ninety-nine decillion nine hundred ninety-nine nonillion nine hundred ninety-nine octillion nine hundred ninety-nine septillion nine hundred ninety-nine sextillion nine hundred ninety-nine quintillion nine hundred ninety-nine quadrillion nine hundred ninety-nine trillion nine hundred ninety-nine billion nine hundred ninety-nine million nine hundred ninety-nine thousand nine hundred ninety-ninth\" (:ERROR \"Number too large to print in English: 1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000\") (:ERROR \"Number too large to print in English: 1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000\") (:ERROR \"Number too large to print in English: 1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000\"))",
			"((:ERROR \"Number too large to print in Roman numerals: 0\") \"I\" \"IV\" \"IX\" \"XIV\" \"XLIX\" \"XC\" \"CD\" \"MCMXCIV\" \"MMMCMXCIX\" (:ERROR \"Number too large to print in Roman numerals: 4,000\") (:ERROR \"Number too large to print in Roman numerals: 4,999\") (:ERROR \"Number too large to print in Roman numerals: 5,000\") (:ERROR \"Number too large to print in Roman numerals: -1\"))",
			"((:ERROR \"Number too large to print in old Roman numerals: 0\") \"I\" \"IIII\" \"VIIII\" \"XIIII\" \"XXXXVIIII\" \"LXXXX\" \"CCCC\" \"MDCCCCLXXXXIIII\" \"MMMDCCCCLXXXXVIIII\" \"MMMM\" \"MMMMDCCCCLXXXXVIIII\" (:ERROR \"Number too large to print in old Roman numerals: 5,000\") (:ERROR \"Number too large to print in old Roman numerals: -1\"))",
			"(\"zero\" \"one\" \"three\" \"twelve\" \"fourteen\" \"twenty\" \"twenty-one\" \"forty\" \"eighty\" \"one hundred\" \"one hundred one\" \"one hundred eleven\" \"one thousand\" \"one thousand one\" \"one thousand one hundred\" \"twelve thousand\" \"one million two hundred thirty-four thousand five hundred sixty-seven\" \"negative one\" \"negative one million two hundred thirty-four thousand five hundred sixty-seven\" \"one sextillion\" \"one vigintillion\" \"nine hundred ninety-nine vigintillion nine hundred ninety-nine novemdecillion nine hundred ninety-nine octodecillion nine hundred ninety-nine septendecillion nine hundred ninety-nine sexdecillion nine hundred ninety-nine quindecillion nine hundred ninety-nine quattuordecillion nine hundred ninety-nine tredecillion nine hundred ninety-nine duodecillion nine hundred ninety-nine undecillion nine hundred ninety-nine decillion nine hundred ninety-nine nonillion nine hundred ninety-nine octillion nine hundred ninety-nine septillion nine hundred ninety-nine sextillion nine hundred ninety-nine quintillion nine hundred ninety-nine quadrillion nine hundred ninety-nine trillion nine hundred ninety-nine billion nine hundred ninety-nine million nine hundred ninety-nine thousand nine hundred ninety-nine\" (:ERROR \"Number too large to print in English: 1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000\") (:ERROR \"Number too large to print in English: -1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000\") (:ERROR \"Number too large to print in English: 1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,001\"))",
			"(\"zeroth\" \"first\" \"third\" \"twelfth\" \"fourteenth\" \"twentieth\" \"twenty-first\" \"fortieth\" \"eightieth\" \"one hundredth\" \"one hundred first\" \"one hundred eleventh\" \"one thousandth\" \"one thousand first\" \"one thousand one hundredth\" \"twelve thousandth\" \"one million two hundred thirty-four thousand five hundred sixty-seventh\" \"negative first\" \"negative one million two hundred thirty-four thousand five hundred sixty-seventh\" \"one sextillionth\" \"one vigintillionth\" \"nine hundred ninety-nine vigintillion nine hundred ninety-nine novemdecillion nine hundred ninety-nine octodecillion nine hundred ninety-nine septendecillion nine hundred ninety-nine sexdecillion nine hundred ninety-nine quindecillion nine hundred ninety-nine quattuordecillion nine hundred ninety-nine tredecillion nine hundred ninety-nine duodecillion nine hundred ninety-nine undecillion nine hundred ninety-nine decillion nine hundred ninety-nine nonillion nine hundred ninety-nine octillion nine hundred ninety-nine septillion nine hundred ninety-nine sextillion nine hundred ninety-nine quintillion nine hundred ninety-nine quadrillion nine hundred ninety-nine trillion nine hundred ninety-nine billion nine hundred ninety-nine million nine hundred ninety-nine thousand nine hundred ninety-ninth\" (:ERROR \"Number too large to print in English: 1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000\") (:ERROR \"Number too large to print in English: 1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000\") (:ERROR \"Number too large to print in English: 1,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000\"))",
			"((:ERROR \"Number too large to print in Roman numerals: 0\") \"I\" \"IV\" \"IX\" \"XIV\" \"XLIX\" \"XC\" \"CD\" \"MCMXCIV\" \"MMMCMXCIX\" (:ERROR \"Number too large to print in Roman numerals: 4,000\") (:ERROR \"Number too large to print in Roman numerals: 4,999\") (:ERROR \"Number too large to print in Roman numerals: 5,000\") (:ERROR \"Number too large to print in Roman numerals: -1\"))",
			"((:ERROR \"Number too large to print in old Roman numerals: 0\") \"I\" \"IIII\" \"VIIII\" \"XIIII\" \"XXXXVIIII\" \"LXXXX\" \"CCCC\" \"MDCCCCLXXXXIIII\" \"MMMDCCCCLXXXXVIIII\" \"MMMM\" \"MMMMDCCCCLXXXXVIIII\" (:ERROR \"Number too large to print in old Roman numerals: 5,000\") (:ERROR \"Number too large to print in old Roman numerals: -1\"))",
			"(\"[twelve]\" \"[twelfth]\" \"[XIV]\" \"144\" \"[    8]\")", "\"IV|fourth|four\"",
			"\"three four five|IX|VIIII\"");

}
