package am.ik.rontolisp.compiler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Common Lisp's stream DESIGNATOR rule as an AST rewrite the compile backends share: for
 * an output operation, an omitted stream argument AND an explicit {@code nil} both denote
 * {@code *standard-output*} (for an INPUT operation, {@code *standard-input*}), resolved
 * at the time of the operation; only {@code t} names the process standard stream.
 *
 * <p>
 * The omitted case is what {@code (with-output-to-string (*standard-output*) ...)} needs;
 * the explicit-nil case is what a forwarded optional needs --
 * {@code (defun emit (x &optional stream) (princ x stream))} called as {@code (emit x)}
 * passes nil down, and CL sends that to the CURRENT {@code *standard-output*}, not to raw
 * stdout. Because the argument arrives in a variable, the test cannot be hoisted: a
 * non-literal expression becomes {@code (or <expr> *standard-output*)}, which evaluates
 * it exactly once.
 *
 * <p>
 * Both backends apply this ONLY when the redirect is active (the variable has a global
 * cell because the program binds it somewhere -- see
 * {@code .kb/standard-output-redirect.md}); a program that never binds
 * {@code *standard-output*} keeps its hard-coded standard output and compiles
 * byte-identically to before.
 */
public final class StreamDesignators {

	private StreamDesignators() {
	}

	/**
	 * The {@code *standard-output*} read an omitted stream argument denotes.
	 * @return the {@code *standard-output*} variable reference
	 */
	public static LispVal standardOutput() {
		return new LispSymbol(LispNames.STANDARD_OUTPUT_VAR);
	}

	/**
	 * The destination expression of an output operation whose stream argument is
	 * {@code explicit} ({@code null} when the argument was omitted), for a program in
	 * which the {@code *standard-output*} redirect is active.
	 * @param explicit the stream argument expression, or {@code null} if omitted
	 * @return the expression to compile as the destination
	 */
	public static LispVal resolveOutput(@Nullable LispVal explicit) {
		if (explicit == null || explicit instanceof LispNil || isStandardOutputRead(explicit)) {
			return standardOutput();
		}
		if (explicit instanceof LispTrue || explicit instanceof LispInteger) {
			// A literal that can never be nil needs no test.
			return explicit;
		}
		return new LispCons(new LispSymbol(LispNames.OR),
				new LispCons(explicit, new LispCons(standardOutput(), LispNil.INSTANCE)));
	}

	/**
	 * The stream handle that denotes the process standard ERROR -- the value
	 * {@code *error-output*} is seeded with on every backend. It is the WASI file
	 * descriptor 2, which is what the wasm backends already write stderr through; the
	 * interpreter and the JVM reserve the handles below {@link #FIRST_USER_HANDLE} in
	 * their stream tables so no user stream can ever collide with it.
	 */
	public static final long STANDARD_ERROR_HANDLE = 2;

	/**
	 * The first handle the interpreter's / the JVM's stream table hands out: 0, 1 and 2
	 * are reserved for the process standard streams, so the handle numbering agrees with
	 * the wasm backends' WASI file descriptors.
	 */
	public static final long FIRST_USER_HANDLE = 3;

	/**
	 * The VALUE the {@code *error-output*} variable holds by default: the process
	 * standard error as a stream value over the reserved handle
	 * {@link #STANDARD_ERROR_HANDLE}. Unlike {@code *standard-output*}'s {@code t} this
	 * one cannot be a designator, because {@code t} already names the process standard
	 * OUTPUT -- so it is a real {@code %STREAM} instance, and {@code (streamp
	 * *error-output*)} answers t off the value itself rather than off "it happens to be
	 * an integer".
	 *
	 * <p>
	 * Returned as the CONSTRUCTOR EXPRESSION rather than a built instance because the
	 * compile backends seed the variable by compiling this form; the interpreter takes
	 * {@link #standardErrorValue()} instead.
	 * @return the {@code (%obj-new '%STREAM 2 :standard)} form
	 */
	public static LispVal standardError() {
		return streamValueForm(new LispInteger(STANDARD_ERROR_HANDLE), LispLayout.Kinds.STANDARD);
	}

	/**
	 * The interpreter's {@code *error-output*} default: the built instance
	 * {@link #standardError()} is the constructor form of.
	 * @return the process standard error stream value
	 */
	public static LispInstance standardErrorValue() {
		return streamValue(STANDARD_ERROR_HANDLE, LispLayout.Kinds.STANDARD);
	}

	/**
	 * Builds a stream VALUE over a backend handle -- the interpreter's half of what the
	 * compile backends emit at every stream producer.
	 * @param handle the stream table index / file descriptor
	 * @param kind one of {@link LispLayout.Kinds}
	 * @return the stream instance
	 */
	public static LispInstance streamValue(long handle, String kind) {
		return new LispInstance(LispLayout.STREAM, new LispVal[] { new LispInteger(handle), new LispSymbol(kind) });
	}

	/**
	 * The {@code (%obj-new '%STREAM <handle> :<kind>)} form that wraps a raw handle
	 * EXPRESSION into a stream value on the compile paths.
	 * @param handle the expression producing the raw handle
	 * @param kind one of {@link LispLayout.Kinds}
	 * @return the constructor form
	 */
	public static LispVal streamValueForm(LispVal handle, String kind) {
		return new LispCons(new LispSymbol(LispNames.OBJ_NEW),
				new LispCons(
						new LispCons(new LispSymbol(LispNames.QUOTE),
								new LispCons(new LispSymbol(LispLayout.STREAM_TAG), LispNil.INSTANCE)),
						new LispCons(handle, new LispCons(new LispSymbol(kind), LispNil.INSTANCE))));
	}

	/**
	 * The {@code *error-output*} read {@code warn} sends its report to.
	 * @return the {@code *error-output*} variable reference
	 */
	public static LispVal errorOutput() {
		return new LispSymbol(LispNames.ERROR_OUTPUT_VAR);
	}

	/**
	 * The {@code *standard-input*} read an omitted stream argument denotes.
	 * @return the {@code *standard-input*} variable reference
	 */
	public static LispVal standardInput() {
		return new LispSymbol(LispNames.STANDARD_INPUT_VAR);
	}

	private static final Map<String, LispVal> STANDARD_STREAM_DEFAULTS = standardStreamDefaultTable();

	private static Map<String, LispVal> standardStreamDefaultTable() {
		Map<String, LispVal> defaults = new LinkedHashMap<>();
		defaults.put(LispNames.STANDARD_OUTPUT_VAR, LispTrue.INSTANCE);
		defaults.put(LispNames.STANDARD_INPUT_VAR, LispTrue.INSTANCE);
		defaults.put(LispNames.ERROR_OUTPUT_VAR, standardError());
		return Collections.unmodifiableMap(defaults);
	}

	/**
	 * The three standard stream variables, each paired with the designator its global
	 * default holds: {@code t} (the process standard stream) for
	 * {@code *standard-output*} and {@code *standard-input*}, the reserved handle
	 * {@link #STANDARD_ERROR_HANDLE} for {@code *error-output*}, which {@code t} cannot
	 * name. Iteration order is fixed.
	 *
	 * <p>
	 * ONE table, because a compiled program keeps the value in TWO homes: the per-name
	 * global field (JVM) / module global (WASM) that direct reads use, and the eval
	 * runtime's global-environment mirror that {@code symbol-value} / {@code boundp} /
	 * {@code eval} probe. Both seed from here, so a name cannot end up bound in one home
	 * and unbound in the other -- which is exactly what it used to be: the mirror knew
	 * nothing of the seeding, so {@code (symbol-value '*error-output*)} signalled
	 * "unbound" on every compile backend while the interpreter answered the handle.
	 * @return the variable name to seeded default mapping
	 */
	public static Map<String, LispVal> standardStreamDefaults() {
		return STANDARD_STREAM_DEFAULTS;
	}

	/**
	 * The source expression of an INPUT operation whose stream argument is
	 * {@code explicit} ({@code null} when the argument was omitted), for a program in
	 * which the {@code *standard-input*} redirect is active. The mirror of
	 * {@link #resolveOutput}: an omitted argument and an explicit nil both denote
	 * {@code *standard-input*}, and only {@code t} names the process standard input.
	 * @param explicit the stream argument expression, or {@code null} if omitted
	 * @return the expression to compile as the source
	 */
	public static LispVal resolveInput(@Nullable LispVal explicit) {
		if (explicit == null || explicit instanceof LispNil || isRead(explicit, LispNames.STANDARD_INPUT_VAR)) {
			return standardInput();
		}
		if (explicit instanceof LispTrue || explicit instanceof LispInteger) {
			// A literal that can never be nil needs no test.
			return explicit;
		}
		return new LispCons(new LispSymbol(LispNames.OR),
				new LispCons(explicit, new LispCons(standardInput(), LispNil.INSTANCE)));
	}

	/**
	 * The same designator expression, resolved down to the raw HANDLE the I/O primitives
	 * act on. Two stream kinds are values rather than handles and both are unwrapped here
	 * by {@code %STREAM-TARGET}: a synonym stream ({@code LispLayout.SYNONYM_STREAM}),
	 * whose target is whatever the variable it names holds AT THAT MOMENT (the reader
	 * closure the value carries, applied recursively so a synonym over a synonym resolves
	 * too), and an OPEN stream ({@code LispLayout.STREAM}), whose slot 0 IS the handle.
	 *
	 * <p>
	 * Applied by both backends' {@code streamArg}/{@code inputStreamArg} seams, and ONLY
	 * when the program can build a stream VALUE at all ({@code Ctx.usesStreamValues} /
	 * {@code Ctx.usesSynonymStreams}), so every other program keeps its exact bytes. A
	 * literal that can never BE one -- an omitted argument, {@code t}, a literal handle
	 * -- is handed back untouched.
	 * @param designator the already-resolved designator expression, or {@code null} for
	 * the hard-coded standard stream
	 * @return the expression to compile as the designator
	 */
	/** The binding the inline stream-designator unwrap introduces; nesting shadows. */
	private static final String STREAM_ARG_VAR = "__stream-arg";

	/**
	 * The same resolution WITHOUT the {@code %STREAM-TARGET} defun: the open-stream
	 * unwrap written out of the {@code %obj-*} primitives alone,
	 * {@code (let ((__stream-arg D)) (if (%obj-is __stream-arg '%STREAM) (%obj-ref __stream-arg 0) __stream-arg))}
	 * -- the shape {@code LispMacroExpander.coercePathArg} uses for a pathname.
	 *
	 * <p>
	 * Used only when the prelude did not splice the defun, which happens for a stream
	 * value that arrives from a form injected after the prelude selection ran (the
	 * generated condition renderer and the print-object seam each open a string output
	 * stream). It cannot resolve a SYNONYM stream and does not need to: a program that
	 * can build one always names {@code make-synonym-stream}, which the selection sees.
	 * @param designator the already-resolved designator expression, or {@code null}
	 * @return the expression to compile as the designator
	 */
	public static @Nullable LispVal throughStreamInline(@Nullable LispVal designator) {
		if (designator == null || designator instanceof LispTrue || designator instanceof LispInteger
				|| designator instanceof LispNil) {
			return designator;
		}
		LispSymbol var = new LispSymbol(STREAM_ARG_VAR);
		LispVal tagTest = list(new LispSymbol(LispNames.OBJ_IS), var,
				list(new LispSymbol(LispNames.QUOTE), new LispSymbol(LispLayout.STREAM_TAG)));
		LispVal unwrap = list(new LispSymbol(LispNames.IF), tagTest,
				list(new LispSymbol(LispNames.OBJ_REF), var, new LispInteger(0)), var);
		LispVal binding = list(list(var, designator));
		return list(new LispSymbol(LispNames.LET), binding, unwrap);
	}

	private static LispVal list(LispVal... elements) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			tail = new LispCons(elements[i], tail);
		}
		return tail;
	}

	public static @Nullable LispVal throughStream(@Nullable LispVal designator) {
		if (designator == null || designator instanceof LispTrue || designator instanceof LispInteger
				|| designator instanceof LispNil) {
			return designator;
		}
		return new LispCons(new LispSymbol(LispNames.STREAM_TARGET), new LispCons(designator, LispNil.INSTANCE));
	}

	private static boolean isStandardOutputRead(LispVal expr) {
		return isRead(expr, LispNames.STANDARD_OUTPUT_VAR);
	}

	private static boolean isRead(LispVal expr, String name) {
		return expr instanceof LispSymbol sym && name.equals(sym.name());
	}

}
