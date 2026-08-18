package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the literal {@code open} arguments to a compile-time file mode shared by the
 * JVM and WASM compilers. The form is {@code (open path [direction [element-type]])}
 * where the direction must be the literal {@code :input} (default), {@code :output} or
 * {@code :append} keyword and the element type must be the literal
 * {@code '(unsigned-byte 8)} (binary) or {@code 'character} (text, default). The mode
 * encoding is {@code 0} = text input, {@code 1} = text output, {@code 2} = binary input,
 * {@code 3} = binary output, {@code 5} = text output APPENDING, {@code 7} = binary output
 * appending.
 *
 * <p>
 * {@code :append} is NOT a Common Lisp direction: it is the normalized spelling of
 * {@code :direction :output :if-exists :append}, produced here and by
 * {@code LispMacroExpander.expandWithOpenFile} so that every backend reads one literal
 * token instead of re-deriving the option pair. smart-buffer's disk-spill path is the
 * caller that made it real -- every chunk past the memory limit appends to the temporary
 * file.
 */
public final class OpenModes {

	/** Bit set in the mode when the stream is opened for output. */
	public static final int OUTPUT_BIT = 1;

	/** Bit set in the mode when the stream is binary ({@code '(unsigned-byte 8)}). */
	public static final int BINARY_BIT = 2;

	/**
	 * Bit set in the mode when an output stream APPENDS instead of truncating
	 * ({@code :if-exists :append}). Never set without {@link #OUTPUT_BIT}.
	 */
	public static final int APPEND_BIT = 4;

	private OpenModes() {
	}

	/**
	 * Whether the form is written in the CL keyword shape ({@code (open path :direction
	 * ...)}) rather than the internal positional one ({@code (open path :input
	 * 'character)}), which the backends' mode resolution reads directly.
	 */
	private static boolean isKeywordForm(List<LispVal> parts) {
		return parts.size() >= 3 && parts.get(2) instanceof LispSymbol first && first.name().startsWith(":")
				&& !LispNames.INPUT_KEYWORD.equals(first.name()) && !LispNames.OUTPUT_KEYWORD.equals(first.name())
				&& !LispNames.APPEND_KEYWORD.equals(first.name());
	}

	/**
	 * Lowers an {@code open} carrying a COMPUTED option value onto the runtime dispatch
	 * over the literal shapes ({@code LispMacroExpander.lowerRuntimeOpenOptions}), which
	 * is what lets a portable wrapper pass {@code :element-type} / {@code :direction}
	 * down as an argument. Returns null when every option value is literal -- then
	 * {@link #normalizeKeywordForm} plus {@link #staticMode} pick the mode at compile
	 * time exactly as before, and the emitted code is unchanged.
	 * @param cons the open form as written
	 * @return the lowered expression, or null when the form folds statically
	 */
	public static @Nullable LispVal lowerRuntimeOptions(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (!isKeywordForm(parts)) {
			return null;
		}
		List<LispVal> options = parts.subList(2, parts.size());
		if (!LispMacroExpander.hasRuntimeOpenOption(options)) {
			return null;
		}
		return LispMacroExpander.lowerRuntimeOpenOptions(LispNames.OPEN, parts.get(1), options);
	}

	/**
	 * Normalizes the CL keyword-argument {@code open} shape ({@code (open path :direction
	 * :input :element-type 'character ...)}) into the positional form the backends
	 * compile. {@code :external-format} (UTF-8 is the native format), {@code :if-exists}
	 * and {@code :if-does-not-exist} (the create/supersede defaults already match) are
	 * accepted and dropped; any other option is rejected. A form already in positional
	 * shape passes through unchanged.
	 * @param cons the open form as written
	 * @return the positional open form
	 */
	public static LispCons normalizeKeywordForm(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (!isKeywordForm(parts)) {
			return cons;
		}
		LispVal direction = new LispSymbol(LispNames.INPUT_KEYWORD);
		LispVal elementType = null;
		boolean append = false;
		for (int i = 2; i < parts.size(); i += 2) {
			if (i + 1 >= parts.size() || !(parts.get(i) instanceof LispSymbol key) || !key.name().startsWith(":")) {
				throw new UnsupportedOperationException("open expects :option value pairs: " + cons.print());
			}
			switch (key.name()) {
				case ":DIRECTION" -> direction = parts.get(i + 1);
				case ":ELEMENT-TYPE" -> elementType = parts.get(i + 1);
				case ":EXTERNAL-FORMAT", ":IF-EXISTS", ":IF-DOES-NOT-EXIST" -> {
					if (LispMacroExpander.isAppendIfExists(key.name(), parts.get(i + 1))) {
						append = true;
					}
					else if (!LispMacroExpander.ignorableOpenOptionValue(key.name(), parts.get(i + 1))) {
						throw new UnsupportedOperationException(
								"open: " + key.name() + " supports only the native default value");
					}
				}
				default -> throw new UnsupportedOperationException("open: unsupported option " + key.name());
			}
		}
		if (append) {
			// :if-exists :append implies output; a source that spelled :direction :input
			// alongside it is contradictory, and CL ignores :if-exists on an input
			// stream -- so the append only takes effect on an output direction.
			if (direction instanceof LispSymbol dir && LispNames.OUTPUT_KEYWORD.equals(dir.name())) {
				direction = new LispSymbol(LispNames.APPEND_KEYWORD);
			}
		}
		List<LispVal> positional = new java.util.ArrayList<>(List.of(parts.get(0), parts.get(1), direction));
		if (elementType != null) {
			positional.add(elementType);
		}
		LispVal rebuilt = LispNil.INSTANCE;
		for (int i = positional.size() - 1; i >= 0; i--) {
			rebuilt = new LispCons(positional.get(i), rebuilt);
		}
		return (LispCons) rebuilt;
	}

	/**
	 * Resolves the literal direction and element-type arguments to the file mode.
	 * @param parts the open form parts
	 * @return the file mode (0 = text input, 1 = text output, 2 = binary input, 3 =
	 * binary output)
	 */
	public static int staticMode(List<LispVal> parts) {
		if (parts.size() < 3) {
			return 0;
		}
		int mode;
		if (parts.get(2) instanceof LispSymbol dir && LispNames.INPUT_KEYWORD.equals(dir.name())) {
			mode = 0;
		}
		else if (parts.get(2) instanceof LispSymbol dir && LispNames.OUTPUT_KEYWORD.equals(dir.name())) {
			mode = OUTPUT_BIT;
		}
		else if (parts.get(2) instanceof LispSymbol dir && LispNames.APPEND_KEYWORD.equals(dir.name())) {
			mode = OUTPUT_BIT | APPEND_BIT;
		}
		else {
			throw new UnsupportedOperationException("open requires a literal :input, :output or :append direction");
		}
		if (parts.size() > 3) {
			if (isBinaryElementType(unquote(parts.get(3)))) {
				mode |= BINARY_BIT;
			}
		}
		return mode;
	}

	/**
	 * Strips a literal {@code (quote x)} wrapper, leaving the type specifier form.
	 * @param val the element-type argument as it appears in the source
	 * @return the quoted form, or the value itself when not a quote form
	 */
	private static LispVal unquote(LispVal val) {
		if (val instanceof LispCons cons) {
			List<LispVal> list = cons.toList();
			if (list.size() == 2 && list.get(0) instanceof LispSymbol sym && LispNames.QUOTE.equals(sym.name())) {
				return list.get(1);
			}
		}
		return val;
	}

	/**
	 * Classifies an element type specifier: {@code (unsigned-byte 8)} is binary,
	 * {@code character} is text, anything else is rejected.
	 * @param spec the unquoted type specifier
	 * @return true for the binary element type
	 */
	public static boolean isBinaryElementType(LispVal spec) {
		if (spec instanceof LispSymbol sym && LispNames.CHARACTER_TYPE.equals(sym.name())) {
			return false;
		}
		if (spec instanceof LispCons cons) {
			List<LispVal> list = cons.toList();
			if (list.size() == 2 && list.get(0) instanceof LispSymbol sym && LispNames.UNSIGNED_BYTE.equals(sym.name())
					&& list.get(1) instanceof LispInteger bits && bits.value() == 8) {
				return true;
			}
		}
		throw new UnsupportedOperationException(
				"open requires a literal 'character or '(unsigned-byte 8) element type");
	}

}
