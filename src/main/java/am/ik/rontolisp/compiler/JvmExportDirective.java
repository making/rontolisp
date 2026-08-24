package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * A parsed {@code (rontolisp:jvm-export 'name :params '(...) :returns ... :as "name")}
 * directive — the JVM twin of {@code rontolisp:wasm-export}.
 *
 * <p>
 * The directive declares the Java-boundary types of an existing top-level {@code defun}
 * so the JVM backend can emit a thin typed wrapper method next to the untyped
 * {@code (Object...)Object} one: a {@code public static} method with a Java-legal name
 * and a primitive/{@code String}/{@code byte[]} signature a Java caller can invoke
 * directly. This class parses the surface and derives the Java method name; the bytecode
 * emission lives in the JVM backend ({@code codegen.jvm.JvmExportRuntimeBuilder}), and
 * the WASM backends skip the form (it is a no-op there, exactly as
 * {@code rontolisp:wasm-export} is a no-op on the JVM).
 *
 * <p>
 * The type designators are {@link BoundaryType}'s — the same vocabulary
 * {@code rontolisp:wasm-export} accepts, so one library source can declare both exports
 * side by side.
 *
 * @param name the Lisp function name (an existing top-level defun)
 * @param methodName the Java method name of the typed wrapper ({@code :as} value, or the
 * lower-camel-cased Lisp name when {@code :as} is absent)
 * @param paramTypes the declared parameter type designators, in order
 * @param returnType the declared return type designator ({@link BoundaryType#VOID} when
 * {@code :returns} is omitted or declared void)
 */
public record JvmExportDirective(String name, String methodName, List<BoundaryType> paramTypes,
		BoundaryType returnType) {

	/**
	 * Java-source keywords (plus the three literals) a wrapper method must not be named:
	 * each is legal in a class file but not callable from Java source, which would defeat
	 * the directive's whole purpose.
	 */
	private static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
			"catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
			"final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
			"long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
			"strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "true", "false", "null");

	/**
	 * Returns whether the given form is a {@code (rontolisp:jvm-export ...)} directive.
	 * @param form the top-level form
	 * @return {@code true} if it is a rontolisp:jvm-export directive
	 */
	public static boolean isExportForm(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym) {
			var qn = PackageRegistry.splitQualified(sym.name());
			return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.JVM_EXPORT.equals(qn.member());
		}
		return false;
	}

	/**
	 * Parses a {@code (rontolisp:jvm-export 'name :params '(...) :returns :type :as
	 * "javaName")} directive (in the canonical post-resolution shape with {@code quote}
	 * spelled out).
	 * @param form the directive form
	 * @return the parsed declaration
	 * @throws UnsupportedOperationException if the directive is malformed, names an
	 * unknown type designator, or derives a method name Java source cannot call
	 */
	public static JvmExportDirective parse(LispCons form) {
		List<LispVal> items = form.toList();
		if (items.size() < 2) {
			throw new UnsupportedOperationException("Malformed rontolisp:jvm-export: " + form.print());
		}
		String name = quotedSymbolName(items.get(1), form);
		String methodName = null;
		List<BoundaryType> params = null;
		BoundaryType returns = null;
		int i = 2;
		while (i < items.size()) {
			String keyword = keywordName(items.get(i), form);
			if (i + 1 >= items.size()) {
				throw new UnsupportedOperationException("Missing value for " + keyword + " in " + form.print());
			}
			LispVal value = items.get(i + 1);
			switch (keyword) {
				case ":AS" -> methodName = methodAlias(value, form);
				case ":PARAMS" -> params = quotedTypeList(value, form);
				case ":RETURNS" -> returns = returnDesignator(value, form);
				default -> throw new UnsupportedOperationException(
						"Unknown rontolisp:jvm-export option " + keyword + " in " + form.print());
			}
			i += 2;
		}
		if (methodName == null) {
			methodName = defaultMethodName(name, form);
		}
		return new JvmExportDirective(name, methodName, params == null ? List.of() : params,
				returns == null ? BoundaryType.VOID : returns);
	}

	/**
	 * The default Java method name of an export: the Lisp name's bare member,
	 * lower-camel-cased ({@code SCALED-SUM} — which the reader upcased — becomes
	 * {@code scaledSum}), which is the spelling a Java caller expects and the same
	 * derivation the WIT side applies in the other direction. A Lisp name whose
	 * derivation is not a valid Java identifier (e.g. one containing {@code *} or
	 * {@code %}) must be renamed with {@code :as}.
	 */
	private static String defaultMethodName(String name, LispCons form) {
		var qn = PackageRegistry.splitQualified(name);
		String member = (qn == null ? name : qn.member()).toLowerCase(Locale.ROOT);
		StringBuilder camel = new StringBuilder(member.length());
		boolean upNext = false;
		for (int i = 0; i < member.length(); i++) {
			char c = member.charAt(i);
			if (c == '-') {
				upNext = true;
			}
			else {
				camel.append(upNext ? Character.toUpperCase(c) : c);
				upNext = false;
			}
		}
		String derived = camel.toString();
		if (!isJavaMethodName(derived)) {
			throw new UnsupportedOperationException("rontolisp:jvm-export cannot derive a Java method name from '"
					+ name + "' (it would be '" + derived + "'); name one with :as \"javaName\" in " + form.print());
		}
		return derived;
	}

	/**
	 * Returns whether the given name is callable from Java source: a Java identifier that
	 * is not a keyword.
	 * @param name the candidate method name
	 * @return {@code true} when a Java caller can spell the name
	 */
	public static boolean isJavaMethodName(String name) {
		if (name.isEmpty() || JAVA_KEYWORDS.contains(name) || !Character.isJavaIdentifierStart(name.charAt(0))) {
			return false;
		}
		for (int i = 1; i < name.length(); i++) {
			if (!Character.isJavaIdentifierPart(name.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	// An :as value is a string literal naming the Java method (leniently also a quoted
	// symbol, lowercased like the default derivation).
	private static String methodAlias(LispVal value, LispCons form) {
		String alias = switch (value) {
			case LispString str -> str.value();
			case LispCons cons when cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol sym ->
				sym.name().toLowerCase(Locale.ROOT);
			default -> throw new UnsupportedOperationException(
					"rontolisp:jvm-export :as expects a string in " + form.print() + ", got: " + value.print());
		};
		if (!isJavaMethodName(alias)) {
			throw new UnsupportedOperationException(
					"rontolisp:jvm-export :as name '" + alias + "' is not a valid Java method name in " + form.print());
		}
		if (alias.startsWith("_") || "main".equals(alias)) {
			// The _ namespace is the generated runtime's (_top$0, _apply, _lambda_N,
			// ...), and main is the command entry point; a wrapper there could shadow
			// or duplicate machinery the class depends on.
			throw new UnsupportedOperationException("rontolisp:jvm-export :as name '" + alias
					+ "' is reserved by the generated class in " + form.print());
		}
		return alias;
	}

	private static String quotedSymbolName(LispVal value, LispCons form) {
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		throw new UnsupportedOperationException(
				"rontolisp:jvm-export expects a quoted function name in " + form.print());
	}

	private static String keywordName(LispVal value, LispCons form) {
		if (value instanceof LispSymbol sym && sym.isKeyword()) {
			return sym.name();
		}
		throw new UnsupportedOperationException("rontolisp:jvm-export expects keyword options in " + form.print()
				+ ", got: " + (value == null ? "nothing" : value.print()));
	}

	private static BoundaryType returnDesignator(LispVal value, LispCons form) {
		if (isVoidMarker(value)) {
			return BoundaryType.VOID;
		}
		return designator(value, form);
	}

	private static boolean isVoidMarker(LispVal value) {
		if (value instanceof LispNil) {
			return true;
		}
		if (value instanceof LispSymbol sym && ":VOID".equals(sym.name())) {
			return true;
		}
		// '() reads as (quote nil)
		return value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispNil;
	}

	private static List<BoundaryType> quotedTypeList(LispVal value, LispCons form) {
		if (value instanceof LispNil) {
			return List.of();
		}
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest) {
			List<BoundaryType> result = new ArrayList<>();
			if (rest.car() instanceof LispCons list) {
				for (LispVal element : list.toList()) {
					result.add(designator(element, form));
				}
			}
			else if (!(rest.car() instanceof LispNil)) {
				throw new UnsupportedOperationException(
						"rontolisp:jvm-export :params expects a list in " + form.print());
			}
			return List.copyOf(result);
		}
		throw new UnsupportedOperationException(
				"rontolisp:jvm-export :params expects a quoted list in " + form.print());
	}

	private static BoundaryType designator(LispVal value, LispCons form) {
		if (value instanceof LispSymbol sym && sym.isKeyword()) {
			BoundaryType type = BoundaryType.forDesignator(sym.name());
			if (type != null) {
				return type;
			}
		}
		throw new UnsupportedOperationException("Unknown rontolisp:jvm-export type designator "
				+ (value == null ? "nothing" : value.print()) + " in " + form.print() + " (expected one of "
				+ String.join(" ", BoundaryType.valueDesignators()) + ")");
	}

}
