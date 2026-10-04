package am.ik.rontolisp.compiler;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The argument counts of the built-ins OUTSIDE the wrapper catalog
 * ({@link BuiltinFunctionWrappers}) that the interpreter implements natively and each
 * compiled backend lowers by an operator compiler of its own or by a spliced library
 * defun: {@code (boundp)}, {@code (rontolisp:tcp-connect "h")}, {@code (export 'x p 3)}.
 * {@link BuiltinCallArity} judges a direct call of one by this table exactly as it judges
 * a catalog built-in's, on all four backends.
 *
 * <p>
 * Each row is the counts the INTERPRETER's implementation accepts -- not the standard
 * lambda list where the two differ, because a shape wider than the implementation would
 * pass a count to a lowering that cannot take it. Keyword arguments count as unbounded,
 * the keyword-tail check staying the operator's. A PAIRED row takes its minimum or its
 * minimum plus one keyword/value pair ({@code tls-connect}: 2 or 4; {@code close}: 1 or
 * 3, the pair {@code :abort v}, whose keyword {@link BuiltinCallArity#wrongCountSignal}
 * checks), the one shape a lambda list cannot spell.
 *
 * <p>
 * The report names the operator the way the interpreter's implementation always has: the
 * member name of a {@code rontolisp:} operator ({@code TCP-CONNECT expects 2 arguments,
 * got 1}). Internal {@code %} operators are not listed: they are forms the expansions
 * emit with a fixed shape, not functions a program calls. Kept in step with the
 * implementations by {@code LispEvaluatorTest} and {@code NativeCallArityCompileTest}. No
 * dependency on {@link BuiltinCallArity}, so the catalog's
 * {@link BuiltinFunctionWrappers#arityOperator} can read it without a class cycle.
 */
final class NativeCallShapes {

	/** {@link Row#max} of an operator that takes any number past its minimum. */
	static final int UNBOUNDED = -1;

	/**
	 * One native built-in's counts.
	 *
	 * @param name the operator's canonical name (qualified for a {@code rontolisp:} one)
	 * @param min the fewest arguments
	 * @param max the most, or {@link #UNBOUNDED}
	 * @param paired whether the counts are exactly {@code min} and {@code max}, the
	 * surplus being one keyword/value pair
	 */
	record Row(String name, int min, int max, boolean paired) {
	}

	private static final Map<String, Row> ROWS = buildRows();

	private NativeCallShapes() {
	}

	/**
	 * The row of a native built-in.
	 * @param name the operator's canonical name
	 * @return the row, or {@code null} when the name is no native built-in listed here
	 */
	static @Nullable Row of(String name) {
		return ROWS.get(name);
	}

	/**
	 * {@return every listed name}
	 */
	static Set<String> names() {
		return ROWS.keySet();
	}

	/**
	 * The operator a wrong-count report names for a listed built-in: the member name of a
	 * package-qualified one, the name itself otherwise.
	 * @param name the operator's canonical name
	 * @return the operator, or {@code null} when the name is not listed
	 */
	static @Nullable String operator(String name) {
		if (!ROWS.containsKey(name)) {
			return null;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	private static Map<String, Row> buildRows() {
		Map<String, Row> rows = new HashMap<>();
		// COMMON-LISP functions.
		for (String name : new String[] { LispNames.ARRAY_DIMENSIONS, LispNames.ARRAYP, LispNames.CHAR_NAME,
				LispNames.DELETE_PACKAGE, LispNames.EVAL, LispNames.FDEFINITION, LispNames.FMAKUNBOUND,
				LispNames.GET_OUTPUT_STREAM_STRING, LispNames.HASH_TABLE_REHASH_SIZE,
				LispNames.HASH_TABLE_REHASH_THRESHOLD, LispNames.HASH_TABLE_SIZE, LispNames.HASH_TABLE_TEST,
				LispNames.OPEN_STREAM_P, LispNames.PACKAGE_NICKNAMES, LispNames.PACKAGE_SHADOWING_SYMBOLS,
				LispNames.PROVIDE, LispNames.RATIONALP, LispNames.SYMBOL_FUNCTION, LispNames.SYMBOL_PACKAGE }) {
			add(rows, name, 1, 1);
		}
		for (String name : new String[] { LispNames.GET_INTERNAL_REAL_TIME, LispNames.GET_INTERNAL_RUN_TIME,
				LispNames.GET_UNIVERSAL_TIME }) {
			add(rows, name, 0, 0);
		}
		for (String name : new String[] { LispNames.EXPORT, LispNames.IMPORT, LispNames.MACRO_FUNCTION,
				LispNames.MACROEXPAND, LispNames.MACROEXPAND_1, LispNames.REQUIRE, LispNames.SHADOW,
				LispNames.SHADOWING_IMPORT, LispNames.UNEXPORT, LispNames.UNINTERN, LispNames.UNUSE_PACKAGE,
				LispNames.USE_PACKAGE }) {
			add(rows, name, 1, 2);
		}
		add(rows, LispNames.ALLOCATE_INSTANCE, 1, UNBOUNDED);
		add(rows, LispNames.COMPILE, 2, 2);
		add(rows, LispNames.FIND_CLASS, 1, 3);
		add(rows, LispNames.LOAD, 1, UNBOUNDED);
		add(rows, LispNames.MAKE_PACKAGE, 1, UNBOUNDED);
		add(rows, LispNames.MAKE_RANDOM_STATE, 0, 1);
		add(rows, LispNames.RENAME_PACKAGE, 2, 3);
		add(rows, LispNames.ROW_MAJOR_AREF, 2, 2);
		add(rows, LispNames.WRITE_BYTE, 2, 2);
		// rontolisp's own.
		for (String member : new String[] { LispNames.AWAIT, LispNames.DESTROY_THREAD, LispNames.FUTUREP,
				LispNames.JOIN_THREAD, LispNames.JSON_PARSE, LispNames.JSON_STRINGIFY, LispNames.MUTEX_ACQUIRE,
				LispNames.MUTEX_RELEASE, LispNames.STREAM_CLOSE, LispNames.STREAM_READ, LispNames.STREAMP,
				LispNames.TCP_ACCEPT, LispNames.TCP_LOCAL_ADDRESS, LispNames.TCP_LOCAL_PORT, LispNames.TCP_PEER_ADDRESS,
				LispNames.TCP_PEER_PORT, LispNames.THREAD_ALIVE_P, LispNames.THREADP, LispNames.WAIT_FOR }) {
			add(rows, rontolisp(member), 1, 1);
		}
		for (String member : new String[] { LispNames.CURRENT_THREAD, LispNames.MAKE_MUTEX, LispNames.MAKE_STREAM,
				LispNames.VERSION }) {
			add(rows, rontolisp(member), 0, 0);
		}
		for (String member : new String[] { LispNames.STREAM_WRITE, LispNames.TCP_CONNECT,
				LispNames.TCP_SET_TIMEOUT }) {
			add(rows, rontolisp(member), 2, 2);
		}
		for (String member : new String[] { LispNames.FETCH, LispNames.MAKE_THREAD, LispNames.TCP_LISTEN }) {
			add(rows, rontolisp(member), 1, 2);
		}
		add(rows, rontolisp(LispNames.TLS_LISTEN), 3, 4);
		add(rows, rontolisp(LispNames.TLS_LISTEN_PEM), 3, 4);
		rows.put(LispNames.CLOSE, new Row(LispNames.CLOSE, 1, 3, true));
		for (String member : new String[] { LispNames.TLS_CONNECT, LispNames.TLS_UPGRADE }) {
			String name = rontolisp(member);
			rows.put(name, new Row(name, 2, 4, true));
		}
		return Map.copyOf(rows);
	}

	private static void add(Map<String, Row> rows, String name, int min, int max) {
		if (rows.put(name, new Row(name, min, max, false)) != null) {
			throw new IllegalStateException(name + " is listed twice");
		}
	}

	private static String rontolisp(String member) {
		return PackageRegistry.qualify(LispNames.RONTOLISP_PKG, member);
	}

}
