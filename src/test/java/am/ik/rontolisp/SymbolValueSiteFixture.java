package am.ik.rontolisp;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A program with {@value #SPECIALS} specials, a {@code progv}, and functions and
 * top-level forms holding several {@code symbol-value} sites each, literal and computed.
 * In a progv-using program {@code symbol-value} reads dynamic-first on the compile paths
 * ({@code LispMacroExpander.dynamicFirstSymbolValue}); until 2026-10-04 every site
 * spelled the dispatch over the WHOLE special set inline, ~10 KB of JVM bytecode a site
 * here, so {@code svs-eight} and the eight-site top-level form overflowed the JVM's 64 KB
 * method limit. A literal name now folds and a computed one calls one shared dispatch.
 * Shared by the backend suites, so every backend is held to one expected text.
 */
public final class SymbolValueSiteFixture {

	private SymbolValueSiteFixture() {
	}

	/** How many specials the program declares. */
	public static final int SPECIALS = 300;

	private static final String SITES = """
			(defun svs-eight (a b c d)
			  (list (symbol-value a) (symbol-value b) (symbol-value c) (symbol-value d)
			        (symbol-value d) (symbol-value c) (symbol-value b) (symbol-value a)))
			(defun svs-literal () (list (symbol-value '*svs-0*) (symbol-value '*svs-299*) (symbol-value ':svs-key)))
			(defun svs-unspecial () (list (symbol-value 'svs-free) (symbol-value (car (list 'svs-free)))))
			(print (svs-eight '*svs-0* '*svs-150* '*svs-299* :svs-key))
			(print (progv '(*svs-0* *svs-299*) '(:a :b)
			         (setq *svs-299* :c)
			         (list (svs-eight '*svs-0* '*svs-1* '*svs-299* nil) (svs-literal))))
			(print (let ((*svs-150* :let) (names (list '*svs-149* '*svs-150* '*svs-151*)))
			         (list (symbol-value (first names)) (symbol-value (second names)) (symbol-value (third names))
			               (symbol-value '*svs-149*) (symbol-value '*svs-150*) (symbol-value '*svs-151*)
			               (symbol-value (first names)) (symbol-value (second names)) (symbol-value (third names)))))
			(print (progv '(svs-free) '(:free) (svs-unspecial)))
			(print (list (mapcar #'symbol-value '(*svs-1* *svs-298*)) (svs-literal)))
			""";

	/** The program. */
	public static final String SOURCE = IntStream.range(0, SPECIALS)
		.mapToObj(i -> "(defvar *svs-" + i + "* " + i + ")")
		.collect(Collectors.joining("\n", "", "\n")) + SITES;

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(0 150 299 :SVS-KEY :SVS-KEY 299 150 0)",
			"((:A 1 :C NIL NIL :C 1 :A) (:A :C :SVS-KEY))", "(149 :LET 151 149 :LET 151 149 :LET 151)", "(:FREE :FREE)",
			"((1 298) (0 299 :SVS-KEY))");

}
