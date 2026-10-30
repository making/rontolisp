package am.ik.rontolisp.eval;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageResolver;

/**
 * What the record of definitions ({@link SwankSources}) and the inspector
 * ({@link SwankInspector}) ask of the Common Lisp half of a SWANK server
 * ({@link CommonLispSwank}): printing, reading, symbol and package lookup. The two never
 * name the class that creates them, so the three form no reference cycle.
 */
interface SwankLisp {

	/**
	 * A value as {@code prin1} prints it in the calling thread's package.
	 * @param value the value
	 * @return the text
	 */
	String print(LispVal value);

	/**
	 * Calls a one-argument lambda, read once in {@code cl-user}, on a value.
	 * @param source the lambda's text
	 * @param argument the argument
	 * @return what it answers
	 */
	LispVal helper(String source, LispVal argument);

	/**
	 * Reads the first form of a text in the calling thread's package, resolved.
	 * @param code the text
	 * @return the form
	 */
	LispVal readResolved(String code);

	/**
	 * A function's or macro's argument list as written, else as the evaluator knows it.
	 * @param spelling the canonical spelling
	 * @return the list, or null when it names neither
	 */
	@Nullable LispVal arglist(String spelling);

	/**
	 * An argument list printed as SWANK prints one, upcased.
	 * @param arglist the list
	 * @return the text
	 */
	String arglistText(LispVal arglist);

	/**
	 * What a symbol names, as {@code describe-symbol} answers.
	 * @param name the symbol's name as typed
	 * @return the description
	 */
	String describeSymbol(String name);

	/**
	 * A symbol's classification flags, as SWANK's {@code symbol-classification-string}.
	 * @param spelling the canonical spelling
	 * @return the flags
	 */
	String classification(String spelling);

	/**
	 * Every symbol accessible in a package with its status, by member name.
	 * @param pkg the package's canonical name
	 * @return the symbols, in member name order
	 */
	Map<String, PackageResolver.Accessible> accessibleSymbols(String pkg);

	/**
	 * The package a designator names, as SWANK's {@code guess-package} reads it.
	 * @param designator the designator, or null
	 * @return the package's canonical name, or null
	 */
	@Nullable String guessPackage(@Nullable String designator);

	/**
	 * A package's name as SWANK prints it.
	 * @param pkg the package's canonical name
	 * @return the name
	 */
	String packageName(String pkg);

	/**
	 * The canonical spelling of the symbol a name names in the calling thread's package.
	 * @param text the name
	 * @return the spelling, or null when no such symbol is accessible
	 */
	@Nullable String symbolNamed(String text);

	/**
	 * The canonical spellings a symbol token may have in the calling thread's package.
	 * @param text the name
	 * @return the spellings, most likely first
	 */
	List<String> spellingsOf(String text);

	/**
	 * A member name spelled in the package another spelling is homed in.
	 * @param spelling the other spelling
	 * @param member the member name
	 * @return the spelling
	 */
	String homedLike(String spelling, String member);

}
