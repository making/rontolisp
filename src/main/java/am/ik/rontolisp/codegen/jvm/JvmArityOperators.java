package am.ik.rontolisp.codegen.jvm;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.DeclaredArityReport;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import org.jspecify.annotations.Nullable;

/**
 * The built-in operators one compiled class's wrong-argument-count reports name, and the
 * callee SHAPE that carries one.
 *
 * <p>
 * A built-in operator's function value names the operator in its report
 * ({@code CONS expects 2 arguments, got 1}), as the interpreter's does; every other
 * callee reports as {@code Function} ({@link BuiltinFunctionWrappers#arityOperator}
 * decides, by the callee's name). The class spells the message at run time from a shape
 * baked beside the callee -- the required count doubled, plus one for a {@code &rest}
 * tail -- so the operator travels in the same int: its 1-based index here from
 * {@link #OPERATOR_SHIFT} up, 0 for {@code Function}. Every site that bakes a shape (a
 * literal {@code apply}'s count guard, a spread dispatcher case, the {@code _arityErr}
 * table) asks {@link #shape} for it, and {@code _arityMsg} reads the names back in index
 * order once {@link #freeze} closes the registry. A site that registers a name after that
 * is a compiler bug, and fails loudly rather than reporting under a name the message
 * builder never learned.
 *
 * <p>
 * A callee whose body declares its own report ({@link DeclaredArityReport}) rides the
 * same index: its entry is {@link #DECLARED_MARK}, the index of its prefix's own entry
 * (shared by every report with that prefix) as one {@code char}, then its suffix, and
 * {@code _arityMsg} spells {@code prefix + count + suffix} from it.
 *
 * <p>
 * One registry per compile, shared by every compilation context of it.
 */
final class JvmArityOperators {

	/** The bit the operator index starts at; the plain shape stays below it. */
	static final int OPERATOR_SHIFT = 16;

	/**
	 * What opens an entry that is a declared report rather than an operator name: no
	 * operator name and no declared prefix starts with it.
	 */
	static final char DECLARED_MARK = '\0';

	private final Map<String, Integer> indices = new LinkedHashMap<>();

	private final Map<String, DeclaredArityReport> declared = new HashMap<>();

	private boolean frozen;

	/** The modified UTF-8 length of the entries joined, separators included. */
	private int utf8Length;

	/**
	 * The shape of a callee that reports as {@code Function}: the required count doubled,
	 * plus one for a {@code &rest} tail.
	 * @param required the callee's required parameter count
	 * @param variadic whether it takes a {@code &rest} tail
	 * @return the shape
	 */
	static int plainShape(int required, boolean variadic) {
		return required * 2 + (variadic ? 1 : 0);
	}

	/**
	 * The shape a report is spelled from for a callee of this name: {@link #plainShape},
	 * with the operator's index when the callee is a built-in's, or its declared report's
	 * when its body declares one ({@link #declare}).
	 * @param required the callee's required parameter count
	 * @param variadic whether it takes a {@code &rest} tail
	 * @param functionName its name, or {@code null} for an anonymous callee
	 * @return the shape
	 */
	int shape(int required, boolean variadic, @Nullable String functionName) {
		return shape(required, variadic, functionName, null);
	}

	/**
	 * {@link #shape(int, boolean, String)} for a callee whose declared report the caller
	 * already holds: a lambda, which has no name to register one under.
	 * @param required the callee's required parameter count
	 * @param variadic whether it takes a {@code &rest} tail
	 * @param functionName its name, or {@code null} for an anonymous callee
	 * @param report the report its body declares, or {@code null} to look the name up
	 * @return the shape
	 */
	int shape(int required, boolean variadic, @Nullable String functionName, @Nullable DeclaredArityReport report) {
		DeclaredArityReport declaredReport = report != null ? report : declared(functionName);
		if (declaredReport != null && encodable(declaredReport) && plainShape(required, variadic) < 1 << OPERATOR_SHIFT
				&& fits(declaredReport.prefix())) {
			// the prefix is an entry of its own, which every report sharing it points at
			int prefix = index(declaredReport.prefix());
			String entry = DECLARED_MARK + String.valueOf((char) prefix) + declaredReport.suffix();
			if (prefix <= Character.MAX_VALUE && fits(entry)) {
				return namedShape(required, variadic, entry);
			}
		}
		return namedShape(required, variadic, BuiltinFunctionWrappers.arityOperator(functionName));
	}

	/**
	 * Whether the entry is registered already or still fits the one string constant
	 * {@code _arityMsg} reads the entries from. Past it a declared report is dropped
	 * rather than the class: the callee reports as {@code Function}.
	 */
	private boolean fits(String entry) {
		return this.indices.containsKey(entry) || this.utf8Length + modifiedUtf8Length(entry) + 1 <= MAX_NAMES_UTF8;
	}

	/**
	 * The most modified UTF-8 bytes the joined entries may take: one
	 * {@code CONSTANT_Utf8} holds 65,535, and the operator names a program registers
	 * besides take a few hundred.
	 */
	private static final int MAX_NAMES_UTF8 = 60_000;

	private static int modifiedUtf8Length(String text) {
		int length = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			length += c != 0 && c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
		}
		return length;
	}

	/**
	 * Registers the report a named function's body declares, which every shape baked for
	 * that name then carries.
	 * @param functionName the function's name
	 * @param report its declared report
	 */
	void declare(String functionName, DeclaredArityReport report) {
		this.declared.put(functionName, report);
	}

	/**
	 * {@return the report the named function declares, or {@code null}}
	 * @param functionName the function's name, or {@code null}
	 */
	@Nullable DeclaredArityReport declared(@Nullable String functionName) {
		return functionName == null ? null : this.declared.get(functionName);
	}

	/**
	 * Whether an entry of the frozen list is a declared report rather than an operator
	 * name.
	 * @param entry the entry
	 * @return whether it opens with {@link #DECLARED_MARK}
	 */
	static boolean isDeclared(String entry) {
		return !entry.isEmpty() && entry.charAt(0) == DECLARED_MARK;
	}

	/** Whether the report's text can ride the newline-joined list. */
	private static boolean encodable(DeclaredArityReport report) {
		return report.prefix().indexOf('\n') < 0 && report.suffix().indexOf('\n') < 0 && !report.prefix().isEmpty()
				&& report.prefix().charAt(0) != DECLARED_MARK;
	}

	/**
	 * The shape of a report that names {@code operator} whatever it is: the compiled
	 * {@code eval}'s own count checks for the operators it evaluates inline and no
	 * wrapper backs ({@code EVAL expects 1 argument, got 2}), which the interpreter names
	 * as its built-ins.
	 * @param required the required argument count
	 * @param variadic whether more arguments are accepted
	 * @param operator the operator to name, or {@code null} for {@code Function}
	 * @return the shape
	 */
	int namedShape(int required, boolean variadic, @Nullable String operator) {
		int shape = plainShape(required, variadic);
		if (operator == null || shape >= 1 << OPERATOR_SHIFT) {
			return shape;
		}
		return shape | (index(operator) << OPERATOR_SHIFT);
	}

	/** The entry's 1-based index, registering it first when it is new. */
	private int index(String entry) {
		Integer index = this.indices.get(entry);
		if (index == null) {
			if (this.frozen) {
				throw new IllegalStateException(
						"arity operator " + entry + " registered after the message builder read the registry");
			}
			index = this.indices.size() + 1;
			this.indices.put(entry, index);
			this.utf8Length += modifiedUtf8Length(entry) + 1;
		}
		return index;
	}

	/**
	 * Closes the registry for the message builder.
	 * @return the registered operator names; the one at list index {@code i} is the
	 * shape's operator index {@code i + 1}
	 */
	List<String> freeze() {
		this.frozen = true;
		return List.copyOf(this.indices.keySet());
	}

}
