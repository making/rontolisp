package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The standard constant variables: {@code pi}, the float-range constants, the fixnum
 * limits, the array limits, {@code char-code-limit},
 * {@code internal-time-units-per-second} and {@code lambda-list-keywords}.
 *
 * <p>
 * These names read as ordinary symbols (even under {@code quote} or in a binding
 * position) and every backend binds them as globals with its own value, so a reference in
 * code position answers the backend's constant while a quoted reference stays the symbol.
 * {@code nil} and {@code t} are deliberately NOT here: they are self-evaluating and the
 * reader answers them directly. See {@code .kb/read-time-constants.md}.
 */
public final class ClConstants {

	private ClConstants() {
	}

	/**
	 * The standard float-range constant names: {@code most-positive-*-float},
	 * {@code most-negative-*-float}, {@code least-positive-*-float},
	 * {@code least-negative-*-float}, {@code least-positive-normalized-*-float},
	 * {@code least-negative-normalized-*-float}, {@code *-float-epsilon} and
	 * {@code *-float-negative-epsilon} for {@code SHORT}/{@code SINGLE} (one binary32
	 * family) and {@code DOUBLE}/{@code LONG} (one binary64 family).
	 */
	public static final Set<String> FLOAT_NAMES = floatNames();

	private static Set<String> floatNames() {
		Set<String> names = new java.util.LinkedHashSet<>();
		for (String single : List.of("SHORT", "SINGLE")) {
			names.add("MOST-POSITIVE-" + single + "-FLOAT");
			names.add("MOST-NEGATIVE-" + single + "-FLOAT");
			names.add("LEAST-POSITIVE-" + single + "-FLOAT");
			names.add("LEAST-NEGATIVE-" + single + "-FLOAT");
			names.add("LEAST-POSITIVE-NORMALIZED-" + single + "-FLOAT");
			names.add("LEAST-NEGATIVE-NORMALIZED-" + single + "-FLOAT");
			names.add(single + "-FLOAT-EPSILON");
			names.add(single + "-FLOAT-NEGATIVE-EPSILON");
		}
		for (String doubl : List.of("DOUBLE", "LONG")) {
			names.add("MOST-POSITIVE-" + doubl + "-FLOAT");
			names.add("MOST-NEGATIVE-" + doubl + "-FLOAT");
			names.add("LEAST-POSITIVE-" + doubl + "-FLOAT");
			names.add("LEAST-NEGATIVE-" + doubl + "-FLOAT");
			names.add("LEAST-POSITIVE-NORMALIZED-" + doubl + "-FLOAT");
			names.add("LEAST-NEGATIVE-NORMALIZED-" + doubl + "-FLOAT");
			names.add(doubl + "-FLOAT-EPSILON");
			names.add(doubl + "-FLOAT-NEGATIVE-EPSILON");
		}
		return Set.copyOf(names);
	}

	/**
	 * The sixteen {@code boole} constants' values, keyed by their standard name. Only
	 * {@code boole-1} and {@code boole-2} are standard SPELLINGS that also read as
	 * numbers; the other fourteen (CLHS 12.1.4) are {@code boole-clr} ..
	 * {@code boole-xor}. The values themselves are implementation-dependent (CLHS never
	 * fixes them), so 1..16 in this table's iteration order is as good as any other
	 * assignment.
	 */
	private static final Map<String, Integer> BOOLE_VALUES = booleValues();

	private static Map<String, Integer> booleValues() {
		Map<String, Integer> table = new LinkedHashMap<>();
		List<String> names = List.of(LispNames.BOOLE_1, LispNames.BOOLE_2, LispNames.BOOLE_AND, LispNames.BOOLE_ANDC1,
				LispNames.BOOLE_ANDC2, LispNames.BOOLE_C1, LispNames.BOOLE_C2, LispNames.BOOLE_CLR, LispNames.BOOLE_EQV,
				LispNames.BOOLE_IOR, LispNames.BOOLE_NAND, LispNames.BOOLE_NOR, LispNames.BOOLE_ORC1,
				LispNames.BOOLE_ORC2, LispNames.BOOLE_SET, LispNames.BOOLE_XOR);
		for (int i = 0; i < names.size(); i++) {
			table.put(names.get(i), i + 1);
		}
		return Map.copyOf(table);
	}

	/**
	 * Every constant-variable name in this table, for the global seeders and the package
	 * registry.
	 */
	public static final Set<String> NAMES = names();

	private static Set<String> names() {
		Set<String> names = new java.util.LinkedHashSet<>(FLOAT_NAMES);
		names.addAll(Set.of(LispNames.PI, LispNames.MOST_POSITIVE_FIXNUM, LispNames.MOST_NEGATIVE_FIXNUM,
				LispNames.ARRAY_DIMENSION_LIMIT, LispNames.ARRAY_TOTAL_SIZE_LIMIT, LispNames.CHAR_CODE_LIMIT,
				LispNames.INTERNAL_TIME_UNITS_PER_SECOND, LispNames.LAMBDA_LIST_KEYWORDS, LispNames.ARRAY_RANK_LIMIT,
				LispNames.CALL_ARGUMENTS_LIMIT, LispNames.LAMBDA_PARAMETERS_LIMIT, LispNames.MULTIPLE_VALUES_LIMIT));
		names.addAll(BOOLE_VALUES.keySet());
		return Set.copyOf(names);
	}

	/**
	 * The float-range constant values by name. {@code short-float} is
	 * {@code single-float} and {@code long-float} is {@code double-float} here, the same
	 * two-format reading SBCL takes, so the four spellings collapse onto two sets of
	 * values. The single-float bounds are the exact doubles of the binary32 numbers CL
	 * names, which is what a runtime with one float type can answer:
	 * {@code most-positive-single-float} prints as {@code 3.4028234663852886e38} where a
	 * single-float implementation prints {@code 3.4028235e38} -- the same number, spelled
	 * with the digits a double round-trips through.
	 */
	private static final Map<String, Double> FLOAT_VALUES = floatValues();

	private static Map<String, Double> floatValues() {
		Map<String, Double> table = new LinkedHashMap<>();
		for (String single : List.of("SHORT", "SINGLE")) {
			table.put("MOST-POSITIVE-" + single + "-FLOAT", (double) Float.MAX_VALUE);
			table.put("MOST-NEGATIVE-" + single + "-FLOAT", (double) -Float.MAX_VALUE);
			table.put("LEAST-POSITIVE-" + single + "-FLOAT", (double) Float.MIN_VALUE);
			table.put("LEAST-NEGATIVE-" + single + "-FLOAT", (double) -Float.MIN_VALUE);
			table.put("LEAST-POSITIVE-NORMALIZED-" + single + "-FLOAT", (double) Float.MIN_NORMAL);
			table.put("LEAST-NEGATIVE-NORMALIZED-" + single + "-FLOAT", (double) -Float.MIN_NORMAL);
			// The SMALLEST e with (/= (+ 1 e) 1) -- one ulp above b^(1-p)/2, which itself
			// rounds away (p = 24 for binary32) -- and its (- 1 e) twin.
			table.put(single + "-FLOAT-EPSILON", (double) Math.nextUp(Math.scalb(1.0f, -24)));
			table.put(single + "-FLOAT-NEGATIVE-EPSILON", (double) Math.nextUp(Math.scalb(1.0f, -25)));
		}
		for (String doubl : List.of("DOUBLE", "LONG")) {
			table.put("MOST-POSITIVE-" + doubl + "-FLOAT", Double.MAX_VALUE);
			table.put("MOST-NEGATIVE-" + doubl + "-FLOAT", -Double.MAX_VALUE);
			table.put("LEAST-POSITIVE-" + doubl + "-FLOAT", Double.MIN_VALUE);
			table.put("LEAST-NEGATIVE-" + doubl + "-FLOAT", -Double.MIN_VALUE);
			table.put("LEAST-POSITIVE-NORMALIZED-" + doubl + "-FLOAT", Double.MIN_NORMAL);
			table.put("LEAST-NEGATIVE-NORMALIZED-" + doubl + "-FLOAT", -Double.MIN_NORMAL);
			// The same rule for binary64 (p = 53).
			table.put(doubl + "-FLOAT-EPSILON", Math.nextUp(Math.scalb(1.0, -53)));
			table.put(doubl + "-FLOAT-NEGATIVE-EPSILON", Math.nextUp(Math.scalb(1.0, -54)));
		}
		return Map.copyOf(table);
	}

	/**
	 * The {@code lambda-list-keywords} value: the {@code &}-symbols the lambda-list
	 * syntax knows. {@code &whole}/{@code &environment} are included -- they name
	 * positions the reader knows, even where a consumer's support for them is partial.
	 */
	public static final List<String> LAMBDA_LIST_KEYWORD_NAMES = List.of("&ALLOW-OTHER-KEYS", "&AUX", "&BODY",
			"&ENVIRONMENT", "&KEY", "&OPTIONAL", "&REST", "&WHOLE");

	/**
	 * Whether the member name (unqualified) is one of these constants.
	 * @param member the symbol name as read (upcased, package prefix stripped)
	 * @return {@code true} when the name is a standard constant variable
	 */
	public static boolean isMember(String member) {
		return NAMES.contains(member);
	}

	/**
	 * Whether the spelling as written names one of these constants: the bare member name,
	 * or a {@code cl:}-qualified spelling of one (a qualified spelling of a standard name
	 * means the standard name).
	 * @param spelling the symbol name as read (upcased, package prefix intact)
	 * @return {@code true} when the spelling denotes a standard constant variable
	 */
	public static boolean isSpelling(String spelling) {
		if (NAMES.contains(spelling)) {
			return true;
		}
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(spelling);
		return qualified != null && LispNames.CL_PKG.equals(PackageRegistry.canonicalBuiltinName(qualified.pkg()))
				&& NAMES.contains(qualified.member());
	}

	/**
	 * The member name the spelling denotes, stripping a {@code cl:} qualifier, or the
	 * spelling unchanged when it names no constant here.
	 * @param spelling the symbol name as read (upcased, package prefix intact)
	 * @return the unqualified constant name, or {@code spelling}
	 */
	public static String memberOf(String spelling) {
		if (NAMES.contains(spelling)) {
			return spelling;
		}
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(spelling);
		if (qualified != null && LispNames.CL_PKG.equals(PackageRegistry.canonicalBuiltinName(qualified.pkg()))
				&& NAMES.contains(qualified.member())) {
			return qualified.member();
		}
		return spelling;
	}

	/**
	 * The constant VALUE for the member name, or {@code null} when the name is not one.
	 *
	 * <p>
	 * The fixnum and array limits are backend-dependent: a WASM fixnum is an unboxed i31
	 * reference, so the WASM value stays inside that range, while the interpreter and the
	 * JVM backend hold a Java long.
	 * @param member the unqualified constant name
	 * @param wasm whether the value is for a WASM backend
	 * @return the value, or {@code null}
	 */
	public static @Nullable LispVal value(String member, boolean wasm) {
		if (LispNames.PI.equals(member)) {
			return new LispDouble(Math.PI);
		}
		if (LispNames.MOST_POSITIVE_FIXNUM.equals(member)) {
			return new LispInteger(wasm ? (1L << 30) - 1 : Long.MAX_VALUE);
		}
		if (LispNames.MOST_NEGATIVE_FIXNUM.equals(member)) {
			return new LispInteger(wasm ? -(1L << 30) : Long.MIN_VALUE);
		}
		if (LispNames.ARRAY_DIMENSION_LIMIT.equals(member) || LispNames.ARRAY_TOTAL_SIZE_LIMIT.equals(member)) {
			return new LispInteger(wasm ? (1L << 30) - 1 : 2147483639L);
		}
		if (LispNames.CHAR_CODE_LIMIT.equals(member)) {
			return new LispInteger(0x110000);
		}
		Double floatConstant = FLOAT_VALUES.get(member);
		if (floatConstant != null) {
			return new LispDouble(floatConstant);
		}
		if (LispNames.INTERNAL_TIME_UNITS_PER_SECOND.equals(member)) {
			return new LispInteger(1000);
		}
		if (LispNames.LAMBDA_LIST_KEYWORDS.equals(member)) {
			LispVal list = LispNil.INSTANCE;
			List<String> keywords = LAMBDA_LIST_KEYWORD_NAMES;
			for (int i = keywords.size() - 1; i >= 0; i--) {
				list = new LispCons(new LispSymbol(keywords.get(i)), list);
			}
			return list;
		}
		if (LispNames.ARRAY_RANK_LIMIT.equals(member)) {
			return new LispInteger(wasm ? 32 : 1024);
		}
		if (LispNames.CALL_ARGUMENTS_LIMIT.equals(member)) {
			return new LispInteger(wasm ? 256 : 1024);
		}
		if (LispNames.LAMBDA_PARAMETERS_LIMIT.equals(member)) {
			return new LispInteger(wasm ? 256 : 1024);
		}
		if (LispNames.MULTIPLE_VALUES_LIMIT.equals(member)) {
			return new LispInteger(wasm ? 256 : 1024);
		}
		Integer booleValue = BOOLE_VALUES.get(member);
		if (booleValue != null) {
			return new LispInteger(booleValue);
		}
		return null;
	}

	/**
	 * The {@code defconstant} initform for the member name: the value itself, except
	 * {@code lambda-list-keywords}, whose value is a list and is therefore quoted.
	 * @param member the unqualified constant name
	 * @param wasm whether the value is for a WASM backend
	 * @return the initform, or {@code null} when the name is not one
	 */
	public static @Nullable LispVal initForm(String member, boolean wasm) {
		LispVal got = value(member, wasm);
		if (got == null) {
			return null;
		}
		if (LispNames.LAMBDA_LIST_KEYWORDS.equals(member)) {
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(got, LispNil.INSTANCE));
		}
		return got;
	}

	/**
	 * Every constant name in a fixed order, for the global seeders (deterministic
	 * emission, {@code .kb/emitted-output-determinism.md}).
	 * @return the sorted constant names
	 */
	public static List<String> sortedNames() {
		List<String> names = new ArrayList<>(NAMES);
		Collections.sort(names);
		return List.copyOf(names);
	}

}
