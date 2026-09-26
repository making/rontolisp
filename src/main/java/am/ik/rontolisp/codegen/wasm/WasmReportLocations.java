package am.ik.rontolisp.codegen.wasm;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * How finely a wasm-GC module notes where an uncaught condition happened
 * ({@code --report-locations}): the location lines the interpreter prints under
 * {@code Unhandled condition: <report>}. Off by default, so a default build is
 * byte-identical to one that never knew about them; each value buys the lines with bytes
 * in every user function ({@code .kb/error-handling.md}, "Location lines on wasm-GC").
 */
public enum WasmReportLocations {

	/**
	 * The function the condition left and the line its definition starts on: a fixed cost
	 * per function, nothing per form.
	 */
	FUNCTION("function"),

	/**
	 * The innermost form read from a file that the condition passed through, as the
	 * interpreter reports it: a local set per located form whose line differs from the
	 * enclosing one's.
	 */
	LINE("line");

	private final String spelling;

	WasmReportLocations(String spelling) {
		this.spelling = spelling;
	}

	/**
	 * The option value that selects this granularity.
	 * @return the spelling
	 */
	public String spelling() {
		return this.spelling;
	}

	/**
	 * Parses an option value.
	 * @param value {@code function} or {@code line}
	 * @return the granularity
	 * @throws IllegalArgumentException for anything else
	 */
	public static WasmReportLocations parse(@Nullable String value) {
		String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		for (WasmReportLocations candidate : values()) {
			if (candidate.spelling.equals(normalized)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("--report-locations takes 'function' or 'line'"
				+ (value == null || value.isEmpty() ? "" : " ('" + value + "' given)")
				+ ", e.g. --report-locations=line");
	}

}
