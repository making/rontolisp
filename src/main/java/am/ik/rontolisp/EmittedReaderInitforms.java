package am.ik.rontolisp;

import java.math.BigInteger;

import org.jspecify.annotations.Nullable;

/**
 * Computes, per {@code defstruct} slot, what the EMITTED runtime reader should do when a
 * {@code #S(...)} datum read at run time omits that slot. The frontend fold
 * ({@link StructLiteralFolder}) substitutes the recorded initform when it is a constant;
 * the compiled artifacts cannot evaluate an initform at run time, so each slot is baked
 * into one of three states the reader dispatches on:
 *
 * <ul>
 * <li>{@code null} -- the initform is nil (or absent): the omitted slot reads as nil,
 * matching the fold;</li>
 * <li>a printed TEXT -- the initform is a constant whose {@code prin1} form the emitted
 * reader is known to parse back to the same value: the reader re-reads the text in place,
 * matching the fold;</li>
 * <li>{@code "\0" + message} -- the initform is not a constant (the fold errors too) or
 * its printed form is outside the emitted reader's grammar (exponent floats; on WASM also
 * bignums and out-of-i31 integers): the reader signals the baked message instead of
 * silently substituting a wrong value.</li>
 * </ul>
 *
 * The guard is deliberately conservative: only value shapes whose printed form is
 * guaranteed re-readable are baked as text, so this class can never introduce a silent
 * misread -- the exact invariant the emitted-reader parity work exists to protect.
 */
public final class EmittedReaderInitforms {

	/** Marks an entry whose remainder is the error message to signal, not a text. */
	public static final char SIGNAL_MARKER = '\0';

	private EmittedReaderInitforms() {
	}

	/**
	 * The per-slot omitted-slot actions for a struct layout.
	 * @param layout the struct layout
	 * @param wasmLimits when true, apply the WASM runtime's narrower numeric grammar (no
	 * bignums, i31 integers only)
	 * @return one entry per slot: null (nil), a re-readable printed text, or
	 * {@link #SIGNAL_MARKER} followed by the error message
	 */
	public static @Nullable String[] initTexts(LispLayout layout, boolean wasmLimits) {
		@Nullable String[] out = new @Nullable String[layout.slotCount()];
		for (int i = 0; i < out.length; i++) {
			LispVal initform = layout.initforms().get(i);
			LispVal constant = constantValue(initform);
			if (constant == null) {
				out[i] = SIGNAL_MARKER + "#S(" + layout.printName() + " ...): slot " + layout.slotNames().get(i)
						+ " is omitted and its initform " + initform.print() + " is not a constant";
			}
			else if (constant instanceof LispNil) {
				out[i] = null;
			}
			else if (isReadable(constant, wasmLimits)) {
				out[i] = constant.print();
			}
			else {
				out[i] = SIGNAL_MARKER + "#S(" + layout.printName() + " ...): slot " + layout.slotNames().get(i)
						+ " is omitted and its initform " + constant.print()
						+ " is not readable by the compiled runtime reader";
			}
		}
		return out;
	}

	/**
	 * The value of a constant initform, or null when the initform has to be evaluated.
	 * Mirrors the fold's rule: a self-evaluating datum stands for itself and
	 * {@code (quote x)} for its datum.
	 */
	@Nullable private static LispVal constantValue(LispVal initform) {
		return switch (initform) {
			case LispCons cons -> cons.car() instanceof LispSymbol head && LispNames.QUOTE.equals(head.name())
					&& cons.cdr() instanceof LispCons datum && datum.cdr() instanceof LispNil ? datum.car() : null;
			case LispSymbol ignored -> null;
			default -> initform;
		};
	}

	/**
	 * Whether the emitted reader is guaranteed to re-read {@code value.print()} back to
	 * an equal value. Symbols are readable because a layout's initform datum is already
	 * in canonical (upcased) spelling; a double is readable only when its printed form
	 * avoids the exponent notation the emitted float parser does not know.
	 */
	private static boolean isReadable(LispVal value, boolean wasmLimits) {
		return switch (value) {
			case LispInteger i -> !wasmLimits || (i.value() >= -(1L << 30) && i.value() < (1L << 30));
			case LispBigInteger ignored -> !wasmLimits;
			case LispDouble d -> {
				String text = value.print();
				yield Double.isFinite(d.value()) && text.indexOf('E') < 0 && text.indexOf('e') < 0;
			}
			case LispRatio r -> !wasmLimits || (fitsI31(r.numerator()) && fitsI31(r.denominator()));
			case LispString ignored -> true;
			case LispChar c -> isReadableChar(c.codePoint());
			case LispSymbol sym -> !sym.name().isEmpty() && sym.name().charAt(0) != '#';
			case LispCons cons -> isReadableList(cons, wasmLimits);
			case LispNil ignored -> true;
			case LispArray array -> {
				if (array.fillPointer() >= 0 || array.adjustable() || array.displacedTo() != null) {
					yield false;
				}
				for (LispVal element : array.data()) {
					if (element != null && !isReadable(element, wasmLimits)) {
						yield false;
					}
				}
				yield true;
			}
			case LispInstance inst -> {
				if (inst.layout().kind() != LispLayout.Kind.STRUCT) {
					yield false;
				}
				for (int i = 0; i < inst.slotCount(); i++) {
					if (!isReadable(inst.slot(i), wasmLimits)) {
						yield false;
					}
				}
				yield true;
			}
			default -> false;
		};
	}

	/** Every element and the tail of a list, down the cdr spine in a loop. */
	private static boolean isReadableList(LispCons list, boolean wasmLimits) {
		LispVal node = list;
		while (node instanceof LispCons cell) {
			if (!isReadable(cell.car(), wasmLimits)) {
				return false;
			}
			node = cell.cdr();
		}
		return isReadable(node, wasmLimits);
	}

	private static boolean fitsI31(BigInteger v) {
		return v.bitLength() < 31;
	}

	/**
	 * Whether {@code #\}-printing this code point yields a form the reader parses back: a
	 * named character (Space, Newline, ...) or a single non-letter-followed glyph. The
	 * printer renders unnamed control characters as their raw glyph, which does not
	 * re-read, so they are excluded.
	 */
	private static boolean isReadableChar(int codePoint) {
		return switch (codePoint) {
			case 32, 10, 9, 13, 12, 8, 0, 127 -> true;
			default -> codePoint > 32 && codePoint != 127;
		};
	}

}
