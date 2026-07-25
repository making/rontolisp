package am.ik.rontolisp;

import java.util.Collection;
import java.util.List;

/**
 * Shared logic for the {@code rontolisp:list-functions} / {@code rontolisp:list-macros} /
 * {@code rontolisp:list-special-forms} introspection functions, used by the interpreter
 * and both compilers so all three backends produce identical listings.
 */
public final class PackageIntrospection {

	private PackageIntrospection() {
	}

	/**
	 * The functions owned by the {@code rontolisp} package, sorted alphabetically.
	 */
	public static final List<String> RONTOLISP_FUNCTION_NAMES = List.of(LispNames.AWAIT, LispNames.CATCH,
			LispNames.FETCH, LispNames.FINALLY, LispNames.HTTP_HANDLER, LispNames.JSON_PARSE, LispNames.JSON_STRINGIFY,
			LispNames.LIST_FUNCTIONS, LispNames.LIST_MACROS, LispNames.LIST_SPECIAL_FORMS, LispNames.QUERY_PARAM,
			LispNames.QUERY_PARAMS, LispNames.RANDOM_BYTES, LispNames.TCP_ACCEPT, LispNames.TCP_CONNECT,
			LispNames.TCP_LISTEN, LispNames.TCP_LOCAL_ADDRESS, LispNames.TCP_LOCAL_PORT, LispNames.TCP_PEER_ADDRESS,
			LispNames.TCP_PEER_PORT, LispNames.THEN, LispNames.THEN_STAR, LispNames.TLS_CONNECT, LispNames.TLS_LISTEN,
			LispNames.TLS_LISTEN_PEM, LispNames.URL_DECODE, LispNames.URL_ENCODE, LispNames.URL_PATH,
			LispNames.URL_QUERY, LispNames.VERSION, LispNames.WIT_ERROR_PAYLOAD, LispNames.WIT_PROVIDE);

	/**
	 * The functions owned by the {@code java} interop package, sorted alphabetically.
	 */
	public static final List<String> JAVA_FUNCTION_NAMES = List.of(LispNames.JAVA_CALL, LispNames.JAVA_FIELD,
			LispNames.JAVA_NEW, LispNames.JAVA_PROXY, LispNames.JAVA_STATIC);

	/**
	 * Filters a collection of function-namespace names down to the user-defined
	 * {@code cl-user} functions: package-qualified names, {@code %}-prefixed internals
	 * and {@code cl} symbols (including names shadowing them) are excluded.
	 * @param names the candidate names (global function-namespace keys or Pass-1 defun
	 * names)
	 * @return the user function names, sorted alphabetically
	 */
	public static List<String> userFunctionNames(Collection<String> names) {
		return names.stream()
			.filter(name -> name.indexOf(':') < 0 && !name.startsWith("%") && !PackageRegistry.isClSymbol(name))
			.sorted()
			.toList();
	}

	/**
	 * Returns the listing of the given introspection function for the given package. The
	 * {@code cl} listings come from the categorized sets in {@link PackageRegistry}; the
	 * {@code cl-user} function listing is derived from the given candidates (the global
	 * function namespace at runtime, or the Pass-1 defun names at compile time). Any
	 * other package name is treated as a user-defined ({@code defpackage}) package: its
	 * function listing is the candidates qualified with that package prefix (the resolver
	 * validates that the package exists before the listing is computed, so an unknown
	 * name simply yields an empty listing here).
	 * @param member the introspection function name ({@code list-functions},
	 * {@code list-macros} or {@code list-special-forms})
	 * @param pkg the package name (without a leading colon)
	 * @param userFunctionCandidates the candidate names for the {@code cl-user} and
	 * user-package function listings
	 * @return the names, sorted alphabetically
	 */
	public static List<String> listNames(String member, String pkg, Collection<String> userFunctionCandidates) {
		return switch (pkg) {
			case LispNames.CL_PKG -> switch (member) {
				case LispNames.LIST_FUNCTIONS -> PackageRegistry.clFunctionNames();
				case LispNames.LIST_MACROS -> PackageRegistry.clMacroNames();
				case LispNames.LIST_SPECIAL_FORMS -> PackageRegistry.clSpecialFormNames();
				default -> throw new IllegalArgumentException("Unknown introspection function: " + member);
			};
			case LispNames.CL_USER_PKG ->
				LispNames.LIST_FUNCTIONS.equals(member) ? userFunctionNames(userFunctionCandidates) : List.of();
			case LispNames.RONTOLISP_PKG ->
				LispNames.LIST_FUNCTIONS.equals(member) ? RONTOLISP_FUNCTION_NAMES : List.of();
			case LispNames.JAVA_PKG -> LispNames.LIST_FUNCTIONS.equals(member) ? JAVA_FUNCTION_NAMES : List.of();
			default ->
				LispNames.LIST_FUNCTIONS.equals(member) ? packageFunctionNames(pkg, userFunctionCandidates) : List.of();
		};
	}

	/**
	 * Filters a collection of function-namespace names down to the members of a
	 * user-defined package: the names qualified as {@code pkg:name} or {@code pkg::name}
	 * (the canonical spellings produced by the resolver), kept in their qualified form.
	 * @param pkg the package name
	 * @param names the candidate names (global function-namespace keys or Pass-1 defun
	 * names)
	 * @return the qualified member names, sorted alphabetically
	 */
	public static List<String> packageFunctionNames(String pkg, Collection<String> names) {
		String prefix = pkg + ":";
		return names.stream().filter(name -> name.startsWith(prefix)).sorted().toList();
	}

	/**
	 * Builds a Lisp list of symbols from the given names.
	 * @param names the symbol names, in list order
	 * @return the list of symbols (nil when empty)
	 */
	public static LispVal symbolList(List<String> names) {
		LispVal result = LispNil.INSTANCE;
		for (int i = names.size() - 1; i >= 0; i--) {
			result = new LispCons(new LispSymbol(names.get(i)), result);
		}
		return result;
	}

}
