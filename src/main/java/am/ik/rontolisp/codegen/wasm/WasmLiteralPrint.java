package am.ik.rontolisp.codegen.wasm;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * The literal fold shared by every output built-in that writes to standard output:
 * {@code print} / {@code prin1} / {@code princ} (which render first) and
 * {@code write-string} / {@code write-line} (which write a string as it is).
 *
 * <p>
 * The text a LITERAL argument produces is a compile-time constant -- every
 * printer-control variable that could change it is inert (see
 * {@code .kb/pretty-printer.md}) -- so it is written as pre-rendered static bytes through
 * {@code FUNC_WRITE_STR} instead of calling the generic printer. A program that only
 * prints literals therefore never references {@code FUNC_PRINT_VAL} /
 * {@code FUNC_PRINC_VAL} / {@code FUNC_WRITE_LINE}, and the whole print-dispatch family
 * (float / bignum / ratio / array / instance renderers) stays shakeable under
 * {@code --optimize}: it is the difference between a 4.8 KB and a 0.65 KB hello-world
 * module.
 */
final class WasmLiteralPrint {

	private WasmLiteralPrint() {
	}

	/**
	 * The text a literal argument prints as.
	 *
	 * <p>
	 * A FLOAT literal is deliberately absent. The emitted {@code _print_f64} does not
	 * agree with {@code LispDouble.print()} on every magnitude (the open large-float
	 * rounding gap), so folding one would give a program TWO spellings of the same value
	 * -- the literal's and the computed one's. Re-evaluate when those two renderers
	 * agree.
	 * @param obj the argument expression
	 * @param readably {@code *print-escape*}: the {@code prin1} rendering rather than the
	 * {@code princ} one
	 * @return the rendered text, or {@code null} when the argument is not a literal whose
	 * rendering is a compile-time constant
	 */
	static @Nullable String rendered(LispVal obj, boolean readably) {
		return switch (obj) {
			case LispString s -> readably ? s.print() : s.value();
			case LispInteger i -> i.print();
			case LispBigInteger bi -> bi.print();
			case LispChar c -> readably ? c.print() : c.display();
			case LispRatio r -> r.print();
			case LispNil nil -> nil.print();
			case LispTrue t -> t.print();
			default -> null;
		};
	}

	/**
	 * Emits the static {@code FUNC_WRITE_STR} write of a pre-rendered literal, which
	 * keeps the {@code *standard-output*} redirect semantics of the generic printer (it
	 * is the same sink the printer's own writes reach).
	 *
	 * <p>
	 * A string written WITHOUT escapes needs no bytes of its own: the compiled value form
	 * of a string is its content framed in {@code "} (the compile-path storage form,
	 * {@code LispString.literal()}), and the display rendering is exactly that frame's
	 * interior -- the bytes the argument itself just interned, minus the two quotes.
	 * Pointing at them is what {@code _princ_val} does at run time.
	 * @param rendered the text from {@link #rendered}
	 * @param obj the argument it was rendered from
	 * @param readably the flag {@code rendered} was called with
	 * @param ctx the compile context
	 */
	static void emitStaticWrite(String rendered, LispVal obj, boolean readably, WasmLispCompiler.Ctx ctx) {
		int offset;
		int length;
		if (!readably && obj instanceof LispString s) {
			WasmLispCompiler.StringTable.StringEntry framed = ctx.stringTable.addString(s.literal());
			offset = framed.offset() + 1;
			length = framed.length() - 2;
		}
		else {
			WasmLispCompiler.StringTable.StringEntry out = ctx.stringTable.addString(rendered);
			offset = out.offset();
			length = out.length();
		}
		emitStaticWrite(offset, length, ctx);
	}

	/** Emits {@code _write_str(offset, length)} over an already-interned byte range. */
	static void emitStaticWrite(int offset, int length, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(offset);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(length);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
	}

	/**
	 * Emits the static newline write the {@code print} / {@code write-line} tail needs.
	 */
	static void emitNewline(WasmLispCompiler.Ctx ctx) {
		emitStaticWrite(ctx.stringTable.newline.offset(), ctx.stringTable.newline.length(), ctx);
	}

}
