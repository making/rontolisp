package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import org.jspecify.annotations.Nullable;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Emits fdlibm -- the transcendental functions {@code java.lang.StrictMath} is specified
 * by -- as WASM runtime functions shared by both WASM backends, so that {@code (exp x)}
 * answers the SAME BITS on the interpreter, the JVM (both on {@code StrictMath}) and
 * every WASM target ({@code .kb/transcendentals.md}).
 *
 * <p>
 * Each function is written once, in a C-like subset ({@link Sources}) transliterated
 * statement for statement from the JDK's own Java port of fdlibm
 * ({@code java.lang.FdLibm}), and compiled to a function body by the small compiler
 * below: the same operations in the same order, on raw {@code f64}/{@code i32} locals,
 * with the word manipulations ({@code __HI}/{@code __LO}) as {@code i64.reinterpret}
 * arithmetic. Bit-identity to {@code StrictMath} is pinned by
 * {@code WasmFdlibmRuntimeBuilderTest} against every one of the functions over random and
 * special arguments.
 *
 * <p>
 * The argument reduction of {@code sin}/{@code cos}/{@code tan} beyond {@code 2^19 *
 * pi/2} ({@code __kernel_rem_pio2}) needs the 2/pi bit table and scratch arrays, which
 * live in linear memory: {@link #tables()} is the data blob (tables first, then the
 * zeroed scratch), placed by each backend wherever it keeps static data and handed to
 * {@link #build} as {@code tablesBase}; only {@link Fn#REM_PIO2}, {@link Fn#K_REM_PIO2}
 * and the three trig entry points address it ({@link #needsTables}).
 */
final class WasmFdlibmRuntimeBuilder {

	private WasmFdlibmRuntimeBuilder() {
	}

	/** The value types of the little language: f64, i32 and i64. */
	enum Ty {

		D(Type.F64), I(Type.I32), L(Type.I64);

		final Type wasm;

		Ty(Type wasm) {
			this.wasm = wasm;
		}

	}

	/**
	 * The runtime functions, in index order. {@code cname} is the identifier the sources
	 * call them by.
	 */
	enum Fn {

		EXP("exp", "_fd_exp", new Ty[] { Ty.D }, Ty.D), EXPM1("expm1", "_fd_expm1", new Ty[] { Ty.D }, Ty.D),
		LOG("log", "_fd_log", new Ty[] { Ty.D }, Ty.D), LOG1P("log1p", "_fd_log1p", new Ty[] { Ty.D }, Ty.D),
		SIN("sin", "_fd_sin", new Ty[] { Ty.D }, Ty.D), COS("cos", "_fd_cos", new Ty[] { Ty.D }, Ty.D),
		TAN("tan", "_fd_tan", new Ty[] { Ty.D }, Ty.D), K_SIN("k_sin", "_fd_ksin", new Ty[] { Ty.D, Ty.D, Ty.I }, Ty.D),
		K_COS("k_cos", "_fd_kcos", new Ty[] { Ty.D, Ty.D }, Ty.D),
		K_TAN("k_tan", "_fd_ktan", new Ty[] { Ty.D, Ty.D, Ty.I }, Ty.D),
		REM_PIO2("rem_pio2", "_fd_rem_pio2", new Ty[] { Ty.D }, Ty.I),
		K_REM_PIO2("krem", "_fd_krem", new Ty[] { Ty.I, Ty.I }, Ty.I),
		ASIN("asin", "_fd_asin", new Ty[] { Ty.D }, Ty.D), ACOS("acos", "_fd_acos", new Ty[] { Ty.D }, Ty.D),
		ATAN("atan", "_fd_atan", new Ty[] { Ty.D }, Ty.D), ATAN2("atan2", "_fd_atan2", new Ty[] { Ty.D, Ty.D }, Ty.D),
		SINH("sinh", "_fd_sinh", new Ty[] { Ty.D }, Ty.D), COSH("cosh", "_fd_cosh", new Ty[] { Ty.D }, Ty.D),
		TANH("tanh", "_fd_tanh", new Ty[] { Ty.D }, Ty.D), POW("pow", "_fd_pow", new Ty[] { Ty.D, Ty.D }, Ty.D),
		HYPOT("hypot", "_fd_hypot", new Ty[] { Ty.D, Ty.D }, Ty.D);

		final String cname;

		final String wasmName;

		final Ty[] params;

		final Ty result;

		Fn(String cname, String wasmName, Ty[] params, Ty result) {
			this.cname = cname;
			this.wasmName = wasmName;
			this.params = params;
			this.result = result;
		}

		/** The direct callees (the transitive set is {@link #closure}). */
		Set<Fn> dependencies() {
			return switch (this) {
				case SIN, COS -> EnumSet.of(K_SIN, K_COS, REM_PIO2);
				case TAN -> EnumSet.of(K_TAN, REM_PIO2);
				case REM_PIO2 -> EnumSet.of(K_REM_PIO2);
				case ATAN2 -> EnumSet.of(ATAN);
				case SINH, COSH -> EnumSet.of(EXPM1, EXP);
				case TANH -> EnumSet.of(EXPM1);
				default -> EnumSet.noneOf(Fn.class);
			};
		}

	}

	/** How many functions the block holds. */
	static final int FUNC_COUNT = Fn.values().length;

	/**
	 * The functions {@code roots} reach: themselves plus every callee, transitively.
	 * @param roots the functions a program calls directly
	 * @return the closed set (empty for empty roots)
	 */
	static Set<Fn> closure(Collection<Fn> roots) {
		Set<Fn> out = EnumSet.noneOf(Fn.class);
		List<Fn> work = new ArrayList<>(roots);
		while (!work.isEmpty()) {
			Fn fn = work.remove(work.size() - 1);
			if (out.add(fn)) {
				work.addAll(fn.dependencies());
			}
		}
		return out;
	}

	/**
	 * Whether any function in the set addresses the {@link #tables()} blob -- the trig
	 * reduction and its callers.
	 * @param fns a closed set of functions
	 * @return true when the blob must be placed
	 */
	static boolean needsTables(Collection<Fn> fns) {
		return fns.contains(Fn.REM_PIO2) || fns.contains(Fn.K_REM_PIO2) || fns.contains(Fn.SIN) || fns.contains(Fn.COS)
				|| fns.contains(Fn.TAN);
	}

	// --- the linear-memory blob: two tables, then the reduction's scratch arrays ------

	/** {@code two_over_pi}: the 24-bit chunks of 2/pi, fdlibm's {@code ipio2}. */
	private static final int[] TWO_OVER_PI = { 0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62, 0x95993C,
			0x439041, 0xFE5163, 0xABDEBB, 0xC561B7, 0x246E3A, 0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB,
			0x1CB129, 0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41, 0x3991D6, 0x398353, 0x39F49C,
			0x845F8B, 0xBDF928, 0x3B1FF8, 0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF, 0x27CB09,
			0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5, 0xF17B3D, 0x0739F7, 0x8A5292, 0xEA6BFB, 0x5FB11F,
			0x8D5D08, 0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3, 0x91615E, 0xE61B08, 0x659985,
			0x5F14A0, 0x68408D, 0xFFD880, 0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B };

	/** {@code npio2_hw}: the high words of {@code n*pi/2} for n = 1..32. */
	private static final int[] NPIO2_HW = { 0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C,
			0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB,
			0x403921FB, 0x403AB41B, 0x403C463A, 0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
			0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB };

	/**
	 * An array in the blob: the two constant tables and the six scratch arrays of the
	 * reduction ({@code __kernel_rem_pio2}'s {@code f}/{@code q}/{@code fq}/{@code iq},
	 * its input {@code tx} and the {@code y} pair it answers through).
	 */
	private record MemArray(String name, Ty elem, int count, int offset) {

		int shift() {
			return this.elem == Ty.D ? 3 : 2;
		}

		int size() {
			return this.count << shift();
		}

	}

	private static final Map<String, MemArray> MEM_ARRAYS = new LinkedHashMap<>();

	/** The blob's size in bytes. */
	static final int TABLES_SIZE;

	static {
		int off = 0;
		for (Object[] spec : new Object[][] { { "ipio2", Ty.I, 66 }, { "npio2_hw", Ty.I, 32 }, { "tx", Ty.D, 3 },
				{ "y", Ty.D, 2 }, { "f", Ty.D, 20 }, { "q", Ty.D, 20 }, { "fq", Ty.D, 20 }, { "iq", Ty.I, 20 } }) {
			MemArray a = new MemArray((String) spec[0], (Ty) spec[1], (Integer) spec[2], off);
			MEM_ARRAYS.put(a.name(), a);
			off += a.size();
		}
		TABLES_SIZE = off;
	}

	/**
	 * The data blob the trig reduction addresses: {@code ipio2} and {@code npio2_hw} as
	 * little-endian i32s, then {@link #TABLES_SIZE} minus their bytes of zeroed scratch.
	 * The blob is 4-aligned by its own layout (every f64 array starts at a multiple of 8
	 * inside it); the placing backend aligns its base to at least 4.
	 * @return a fresh copy of the bytes
	 */
	static byte[] tables() {
		byte[] out = new byte[TABLES_SIZE];
		writeTable(out, memArray("ipio2").offset(), TWO_OVER_PI);
		writeTable(out, memArray("npio2_hw").offset(), NPIO2_HW);
		return out;
	}

	private static MemArray memArray(String name) {
		MemArray a = MEM_ARRAYS.get(name);
		if (a == null) {
			throw new IllegalStateException("no memory array " + name);
		}
		return a;
	}

	private static void writeTable(byte[] out, int offset, int[] values) {
		for (int i = 0; i < values.length; i++) {
			int v = values[i];
			int p = offset + 4 * i;
			out[p] = (byte) v;
			out[p + 1] = (byte) (v >>> 8);
			out[p + 2] = (byte) (v >>> 16);
			out[p + 3] = (byte) (v >>> 24);
		}
	}

	// --- building ------------------------------------------------------------------

	/**
	 * A body that traps: what a backend emits in a slot whose function the program never
	 * reaches, so the fixed index space stays dense without carrying the bytes.
	 * @param fn the slot
	 * @return the body
	 */
	static byte[] stub(Fn fn) {
		ByteArrayOutputStream out = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	/**
	 * The function body of {@code fn}.
	 * @param fn the function
	 * @param indexOf the function index of each callee (only the {@link Fn#dependencies}
	 * are asked for)
	 * @param tablesBase the linear-memory address of {@link #tables()}; ignored by a
	 * function that does not address it
	 * @return the body, locals declaration included
	 */
	static byte[] build(Fn fn, ToIntFunction<Fn> indexOf, int tablesBase) {
		Function parsed = Parsed.of(fn);
		ByteArrayOutputStream out = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		new Emitter(w, parsed, indexOf, tablesBase).emit();
		return out.toByteArray();
	}

	// --- the parsed functions, cached once per JVM ------------------------------------

	private static final class Parsed {

		private static final Map<Fn, Function> CACHE = new HashMap<>();

		static synchronized Function of(Fn fn) {
			return CACHE.computeIfAbsent(fn, f -> new Parser(f, Sources.of(f)).parseFunction());
		}

	}

	// --- AST ---------------------------------------------------------------------------

	private static final class Var {

		final String name;

		final Ty ty;

		int index = -1;

		Var(String name, Ty ty) {
			this.name = name;
			this.ty = ty;
		}

	}

	private sealed interface Expr {

		Ty ty();

	}

	private record Num(Ty ty, double d, long l) implements Expr {
	}

	private record Load(Var v) implements Expr {

		@Override
		public Ty ty() {
			return this.v.ty;
		}

	}

	private record Unary(String op, Expr a, Ty ty) implements Expr {
	}

	private record Binary(String op, Expr a, Expr b, Ty ty) implements Expr {
	}

	private record Logical(boolean and, Expr a, Expr b) implements Expr {

		@Override
		public Ty ty() {
			return Ty.I;
		}

	}

	private record Ternary(Expr c, Expr a, Expr b) implements Expr {

		@Override
		public Ty ty() {
			return this.a.ty();
		}

	}

	private record Cast(Ty ty, Expr a) implements Expr {
	}

	private record Builtin(String name, List<Expr> args, Ty ty) implements Expr {
	}

	private record Call(Fn fn, List<Expr> args) implements Expr {

		@Override
		public Ty ty() {
			return this.fn.result;
		}

	}

	private record MemRead(MemArray arr, Expr index) implements Expr {

		@Override
		public Ty ty() {
			return this.arr.elem();
		}

	}

	/** A small constant table read at a run-time index: a chain of selects. */
	private record TableRead(double[] values, Expr index) implements Expr {

		@Override
		public Ty ty() {
			return Ty.D;
		}

	}

	private sealed interface Stmt {

	}

	private record Assign(Var v, Expr e) implements Stmt {
	}

	private record MemStore(MemArray arr, Expr index, Expr e) implements Stmt {
	}

	private record If(Expr c, List<Stmt> then, List<Stmt> els) implements Stmt {
	}

	private record While(@Nullable Expr c, List<Stmt> body) implements Stmt {
	}

	private record Return(Expr e) implements Stmt {
	}

	private record Break() implements Stmt {
	}

	private record Continue() implements Stmt {
	}

	private record Function(Fn fn, List<Var> params, List<Var> locals, List<Stmt> body) {
	}

	// --- lexer -------------------------------------------------------------------------

	private enum Tk {

		IDENT, NUM, PUNCT, EOF

	}

	private record Token(Tk kind, String text, Ty numTy, double d, long l) {
	}

	private static final class Lexer {

		private final String src;

		private int pos;

		Lexer(String src) {
			this.src = src;
		}

		Token next() {
			skipSpaceAndComments();
			if (this.pos >= this.src.length()) {
				return new Token(Tk.EOF, "", Ty.I, 0, 0);
			}
			char c = this.src.charAt(this.pos);
			if (Character.isLetter(c) || c == '_') {
				int start = this.pos;
				while (this.pos < this.src.length()
						&& (Character.isLetterOrDigit(this.src.charAt(this.pos)) || this.src.charAt(this.pos) == '_')) {
					this.pos++;
				}
				return new Token(Tk.IDENT, this.src.substring(start, this.pos), Ty.I, 0, 0);
			}
			if (Character.isDigit(c)) {
				return number();
			}
			for (String p : new String[] { ">>>=", "<<=", ">>=", ">>>", "==", "!=", "<=", ">=", "&&", "||", "<<", ">>",
					"+=", "-=", "*=", "/=", "&=", "|=" }) {
				if (this.src.startsWith(p, this.pos)) {
					this.pos += p.length();
					return new Token(Tk.PUNCT, p, Ty.I, 0, 0);
				}
			}
			this.pos++;
			return new Token(Tk.PUNCT, String.valueOf(c), Ty.I, 0, 0);
		}

		private void skipSpaceAndComments() {
			while (this.pos < this.src.length()) {
				char c = this.src.charAt(this.pos);
				if (Character.isWhitespace(c)) {
					this.pos++;
				}
				else if (c == '/' && this.src.startsWith("//", this.pos)) {
					while (this.pos < this.src.length() && this.src.charAt(this.pos) != '\n') {
						this.pos++;
					}
				}
				else {
					return;
				}
			}
		}

		// A Java numeric literal: decimal integer, hexadecimal integer (wrapping to int
		// like Java's), decimal floating point, hexadecimal floating point
		// (0x1.8p3), with an optional L suffix on the integers.
		private Token number() {
			int start = this.pos;
			boolean hex = this.src.startsWith("0x", this.pos) || this.src.startsWith("0X", this.pos);
			boolean floating = false;
			if (hex) {
				this.pos += 2;
				while (this.pos < this.src.length()) {
					char c = this.src.charAt(this.pos);
					if (Character.digit(c, 16) >= 0 || c == '_') {
						this.pos++;
					}
					else if (c == '.') {
						floating = true;
						this.pos++;
					}
					else if (c == 'p' || c == 'P') {
						floating = true;
						this.pos++;
						if (this.src.charAt(this.pos) == '-' || this.src.charAt(this.pos) == '+') {
							this.pos++;
						}
						while (this.pos < this.src.length() && Character.isDigit(this.src.charAt(this.pos))) {
							this.pos++;
						}
						break;
					}
					else {
						break;
					}
				}
			}
			else {
				while (this.pos < this.src.length()) {
					char c = this.src.charAt(this.pos);
					if (Character.isDigit(c) || c == '_') {
						this.pos++;
					}
					else if (c == '.') {
						floating = true;
						this.pos++;
					}
					else if (c == 'e' || c == 'E') {
						floating = true;
						this.pos++;
						if (this.src.charAt(this.pos) == '-' || this.src.charAt(this.pos) == '+') {
							this.pos++;
						}
					}
					else {
						break;
					}
				}
			}
			String text = this.src.substring(start, this.pos).replace("_", "");
			if (floating) {
				return new Token(Tk.NUM, text, Ty.D, Double.parseDouble(text), 0);
			}
			boolean isLong = this.pos < this.src.length() && this.src.charAt(this.pos) == 'L';
			if (isLong) {
				this.pos++;
			}
			long value = hex ? Long.parseUnsignedLong(text.substring(2), 16) : Long.parseLong(text);
			if (isLong) {
				return new Token(Tk.NUM, text, Ty.L, 0, value);
			}
			// A hex int literal past Integer.MAX_VALUE wraps, exactly as javac reads it.
			return new Token(Tk.NUM, text, Ty.I, 0, (int) value);
		}

	}

	// --- parser
	// --------------------------------------------------------------------------

	private static final class Parser {

		private final Fn fn;

		private final Lexer lexer;

		private Token tok;

		private final Map<String, Var> vars = new LinkedHashMap<>();

		private final Map<String, Num> consts = new HashMap<>();

		private final Map<String, double[]> tables = new HashMap<>();

		private final List<Var> params = new ArrayList<>();

		private final List<Var> locals = new ArrayList<>();

		Parser(Fn fn, String src) {
			this.fn = fn;
			this.lexer = new Lexer(src);
			// The constants FdLibm shares between its classes, plus the java.lang ones
			// it reads (Math.PI, Double.MAX_VALUE, ...), under the names the sources use.
			this.consts.put("INFINITY", new Num(Ty.D, Double.POSITIVE_INFINITY, 0));
			this.consts.put("NAN", new Num(Ty.D, Double.NaN, 0));
			this.consts.put("PI", new Num(Ty.D, Math.PI, 0));
			this.consts.put("HUGE", new Num(Ty.D, 1.0e+300, 0));
			this.consts.put("TWO24", new Num(Ty.D, 0x1.0p24, 0));
			this.consts.put("TWO54", new Num(Ty.D, 0x1.0p54, 0));
			this.consts.put("DBL_MAX", new Num(Ty.D, Double.MAX_VALUE, 0));
			this.consts.put("DBL_MIN_NORMAL", new Num(Ty.D, Double.MIN_NORMAL, 0));
			this.consts.put("SIGN_BIT", new Num(Ty.I, 0, 0x8000_0000));
			this.consts.put("EXP_BITS", new Num(Ty.I, 0, 0x7ff0_0000));
			this.consts.put("EXP_SIGNIF_BITS", new Num(Ty.I, 0, 0x7fff_ffff));
			this.tok = this.lexer.next();
		}

		private RuntimeException error(String msg) {
			return new IllegalStateException(this.fn + ": " + msg + " at '" + this.tok.text() + "'");
		}

		private void advance() {
			this.tok = this.lexer.next();
		}

		private boolean is(String punct) {
			return this.tok.kind() == Tk.PUNCT && this.tok.text().equals(punct);
		}

		private boolean isIdent(String name) {
			return this.tok.kind() == Tk.IDENT && this.tok.text().equals(name);
		}

		private void expect(String punct) {
			if (!is(punct)) {
				throw error("expected '" + punct + "'");
			}
			advance();
		}

		private String ident() {
			if (this.tok.kind() != Tk.IDENT) {
				throw error("expected an identifier");
			}
			String name = this.tok.text();
			advance();
			return name;
		}

		private @Nullable Ty typeKeyword() {
			if (this.tok.kind() != Tk.IDENT) {
				return null;
			}
			return switch (this.tok.text()) {
				case "double" -> Ty.D;
				case "int" -> Ty.I;
				case "long" -> Ty.L;
				default -> null;
			};
		}

		Function parseFunction() {
			// Leading const declarations (the prelude and the function's own) ...
			while (isIdent("const")) {
				parseConst();
			}
			// ... then the signature.
			Ty result = typeKeyword();
			if (result == null) {
				throw error("expected the result type");
			}
			advance();
			String name = ident();
			if (!name.equals(this.fn.cname) || result != this.fn.result) {
				throw error("signature does not match " + this.fn);
			}
			expect("(");
			int p = 0;
			while (!is(")")) {
				Ty ty = typeKeyword();
				if (ty == null) {
					throw error("expected a parameter type");
				}
				advance();
				String pname = ident();
				if (p >= this.fn.params.length || this.fn.params[p] != ty) {
					throw error("parameter " + p + " does not match " + this.fn);
				}
				Var v = new Var(pname, ty);
				v.index = p++;
				this.vars.put(pname, v);
				this.params.add(v);
				if (is(",")) {
					advance();
				}
			}
			if (p != this.fn.params.length) {
				throw error("parameter count does not match " + this.fn);
			}
			expect(")");
			List<Stmt> body = block();
			if (this.tok.kind() != Tk.EOF) {
				throw error("trailing text");
			}
			return new Function(this.fn, this.params, this.locals, body);
		}

		private void parseConst() {
			advance(); // const
			Ty ty = typeKeyword();
			if (ty == null) {
				throw error("expected a const type");
			}
			advance();
			if (is("[")) {
				expect("[");
				expect("]");
				String name = ident();
				expect("=");
				expect("{");
				List<Double> values = new ArrayList<>();
				while (!is("}")) {
					values.add(constant().d());
					if (is(",")) {
						advance();
					}
				}
				expect("}");
				expect(";");
				double[] arr = new double[values.size()];
				for (int i = 0; i < arr.length; i++) {
					arr[i] = values.get(i);
				}
				this.tables.put(name, arr);
				return;
			}
			String name = ident();
			expect("=");
			Num value = constant();
			if (value.ty() != ty) {
				throw error("const " + name + " has the wrong type");
			}
			expect(";");
			this.consts.put(name, value);
		}

		// A literal, a negated literal, or a previously declared constant.
		private Num constant() {
			boolean neg = false;
			if (is("-")) {
				neg = true;
				advance();
			}
			Num n;
			if (this.tok.kind() == Tk.NUM) {
				n = new Num(this.tok.numTy(), this.tok.d(), this.tok.l());
				advance();
			}
			else if (this.tok.kind() == Tk.IDENT && this.consts.containsKey(this.tok.text())) {
				n = this.consts.get(this.tok.text());
				advance();
			}
			else {
				throw error("expected a constant");
			}
			return neg ? new Num(n.ty(), -n.d(), -n.l()) : n;
		}

		private List<Stmt> block() {
			expect("{");
			List<Stmt> out = new ArrayList<>();
			while (!is("}")) {
				statement(out);
			}
			expect("}");
			return out;
		}

		private List<Stmt> stmtOrBlock() {
			if (is("{")) {
				return block();
			}
			List<Stmt> out = new ArrayList<>();
			statement(out);
			return out;
		}

		private void statement(List<Stmt> out) {
			Ty declared = typeKeyword();
			if (declared != null) {
				advance();
				while (true) {
					String name = ident();
					if (this.vars.containsKey(name)) {
						throw error("duplicate local " + name);
					}
					Var v = new Var(name, declared);
					this.vars.put(name, v);
					this.locals.add(v);
					if (is("=")) {
						advance();
						out.add(new Assign(v, coerceTo(expr(), v.ty)));
					}
					if (is(",")) {
						advance();
						continue;
					}
					expect(";");
					return;
				}
			}
			if (isIdent("if")) {
				advance();
				expect("(");
				Expr c = requireI(expr());
				expect(")");
				List<Stmt> then = stmtOrBlock();
				List<Stmt> els = List.of();
				if (isIdent("else")) {
					advance();
					els = stmtOrBlock();
				}
				out.add(new If(c, then, els));
				return;
			}
			if (isIdent("while")) {
				advance();
				expect("(");
				Expr c = requireI(expr());
				expect(")");
				List<Stmt> body = stmtOrBlock();
				boolean forever = c instanceof Num n && n.l() != 0;
				out.add(new While(forever ? null : c, body));
				return;
			}
			if (isIdent("return")) {
				advance();
				Expr e = coerceTo(expr(), this.fn.result);
				expect(";");
				out.add(new Return(e));
				return;
			}
			if (isIdent("break")) {
				advance();
				expect(";");
				out.add(new Break());
				return;
			}
			if (isIdent("continue")) {
				advance();
				expect(";");
				out.add(new Continue());
				return;
			}
			if (is("{")) {
				out.addAll(block());
				return;
			}
			// An assignment: to a local, or to a memory array element.
			String name = ident();
			if (is("[")) {
				MemArray arr = MEM_ARRAYS.get(name);
				if (arr == null) {
					throw error("not a memory array: " + name);
				}
				advance();
				Expr index = requireI(expr());
				expect("]");
				String op = assignOp();
				Expr rhs = coerceTo(expr(), arr.elem());
				expect(";");
				Expr value = op.equals("=") ? rhs
						: new Binary(op.substring(0, op.length() - 1), new MemRead(arr, index), rhs, arr.elem());
				out.add(new MemStore(arr, index, value));
				return;
			}
			Var v = this.vars.get(name);
			if (v == null) {
				throw error("unknown variable " + name);
			}
			String op = assignOp();
			Expr rhs = expr();
			expect(";");
			if (op.equals("=")) {
				out.add(new Assign(v, coerceTo(rhs, v.ty)));
			}
			else {
				String bin = op.substring(0, op.length() - 1);
				out.add(new Assign(v, binary(bin, new Load(v), rhs)));
			}
		}

		private String assignOp() {
			for (String op : new String[] { "=", "+=", "-=", "*=", "/=", "&=", "|=", "<<=", ">>=", ">>>=" }) {
				if (is(op)) {
					advance();
					return op;
				}
			}
			throw error("expected an assignment");
		}

		private Expr requireI(Expr e) {
			if (e.ty() != Ty.I) {
				throw error("expected an int expression");
			}
			return e;
		}

		// The language has no implicit promotion: an int meets a double only through an
		// explicit cast, so a missed cast in a transliteration fails to parse rather
		// than rounding somewhere Java would not.
		private Expr coerceTo(Expr e, Ty ty) {
			if (e.ty() != ty) {
				throw error("expected " + ty + ", got " + e.ty());
			}
			return e;
		}

		// --- expressions, lowest precedence first ---

		private Expr expr() {
			Expr c = or();
			if (is("?")) {
				advance();
				Expr a = expr();
				expect(":");
				Expr b = expr();
				requireI(c);
				if (a.ty() != b.ty()) {
					throw error("ternary arms differ in type");
				}
				return new Ternary(c, a, b);
			}
			return c;
		}

		private Expr or() {
			Expr a = and();
			while (is("||")) {
				advance();
				a = new Logical(false, requireI(a), requireI(and()));
			}
			return a;
		}

		private Expr and() {
			Expr a = bitOr();
			while (is("&&")) {
				advance();
				a = new Logical(true, requireI(a), requireI(bitOr()));
			}
			return a;
		}

		private Expr bitOr() {
			Expr a = bitXor();
			while (is("|")) {
				advance();
				a = binary("|", a, bitXor());
			}
			return a;
		}

		private Expr bitXor() {
			Expr a = bitAnd();
			while (is("^")) {
				advance();
				a = binary("^", a, bitAnd());
			}
			return a;
		}

		private Expr bitAnd() {
			Expr a = equality();
			while (is("&")) {
				advance();
				a = binary("&", a, equality());
			}
			return a;
		}

		private Expr equality() {
			Expr a = relational();
			while (is("==") || is("!=")) {
				String op = this.tok.text();
				advance();
				a = binary(op, a, relational());
			}
			return a;
		}

		private Expr relational() {
			Expr a = shift();
			while (is("<") || is(">") || is("<=") || is(">=")) {
				String op = this.tok.text();
				advance();
				a = binary(op, a, shift());
			}
			return a;
		}

		private Expr shift() {
			Expr a = additive();
			while (is("<<") || is(">>") || is(">>>")) {
				String op = this.tok.text();
				advance();
				a = binary(op, a, additive());
			}
			return a;
		}

		private Expr additive() {
			Expr a = multiplicative();
			while (is("+") || is("-")) {
				String op = this.tok.text();
				advance();
				a = binary(op, a, multiplicative());
			}
			return a;
		}

		private Expr multiplicative() {
			Expr a = unary();
			while (is("*") || is("/") || is("%")) {
				String op = this.tok.text();
				advance();
				a = binary(op, a, unary());
			}
			return a;
		}

		private Expr binary(String op, Expr a, Expr b) {
			switch (op) {
				case "+", "-", "*", "/", "%" -> {
					if (a.ty() != b.ty()) {
						throw error("operands of " + op + " differ in type");
					}
					if (op.equals("%") && a.ty() == Ty.D) {
						throw error("no float remainder");
					}
					return new Binary(op, a, b, a.ty());
				}
				case "&", "|", "^" -> {
					if (a.ty() != b.ty() || a.ty() == Ty.D) {
						throw error("bitwise operands must be integers of one type");
					}
					return new Binary(op, a, b, a.ty());
				}
				case "<<", ">>", ">>>" -> {
					if (a.ty() == Ty.D || b.ty() != Ty.I) {
						throw error("shift operands");
					}
					return new Binary(op, a, b, a.ty());
				}
				case "==", "!=", "<", ">", "<=", ">=" -> {
					if (a.ty() != b.ty()) {
						throw error("comparison operands differ in type");
					}
					return new Binary(op, a, b, Ty.I);
				}
				default -> throw error("unknown operator " + op);
			}
		}

		private Expr unary() {
			if (is("-")) {
				advance();
				Expr a = unary();
				if (a instanceof Num n) {
					return new Num(n.ty(), -n.d(), -n.l());
				}
				return new Unary("-", a, a.ty());
			}
			if (is("!")) {
				advance();
				return new Unary("!", requireI(unary()), Ty.I);
			}
			if (is("~")) {
				advance();
				Expr a = unary();
				if (a.ty() == Ty.D) {
					throw error("~ of a double");
				}
				return new Unary("~", a, a.ty());
			}
			if (is("(")) {
				// A cast, or a parenthesized expression.
				advance();
				Ty cast = typeKeyword();
				if (cast != null) {
					advance();
					expect(")");
					Expr a = unary();
					return a.ty() == cast ? a : new Cast(cast, a);
				}
				Expr e = expr();
				expect(")");
				return e;
			}
			return primary();
		}

		private Expr primary() {
			if (this.tok.kind() == Tk.NUM) {
				Num n = new Num(this.tok.numTy(), this.tok.d(), this.tok.l());
				advance();
				return n;
			}
			String name = ident();
			if (is("(")) {
				advance();
				List<Expr> args = new ArrayList<>();
				while (!is(")")) {
					args.add(expr());
					if (is(",")) {
						advance();
					}
				}
				expect(")");
				return call(name, args);
			}
			if (is("[")) {
				advance();
				Expr index = requireI(expr());
				expect("]");
				double[] table = this.tables.get(name);
				if (table != null) {
					if (index instanceof Num n) {
						return new Num(Ty.D, table[(int) n.l()], 0);
					}
					return new TableRead(table, index);
				}
				MemArray arr = MEM_ARRAYS.get(name);
				if (arr == null) {
					throw error("not an array: " + name);
				}
				return new MemRead(arr, index);
			}
			Num c = this.consts.get(name);
			if (c != null) {
				return c;
			}
			Var v = this.vars.get(name);
			if (v == null) {
				throw error("unknown identifier " + name);
			}
			return new Load(v);
		}

		private Expr call(String name, List<Expr> args) {
			Ty[] sig;
			Ty result;
			switch (name) {
				case "HI", "LO" -> {
					sig = new Ty[] { Ty.D };
					result = Ty.I;
				}
				case "SET_HI", "SET_LO" -> {
					sig = new Ty[] { Ty.D, Ty.I };
					result = Ty.D;
				}
				case "HI_LO" -> {
					sig = new Ty[] { Ty.I, Ty.I };
					result = Ty.D;
				}
				case "abs", "sqrt", "floor" -> {
					sig = new Ty[] { Ty.D };
					result = Ty.D;
				}
				case "pow2" -> {
					sig = new Ty[] { Ty.I };
					result = Ty.D;
				}
				case "scalb" -> {
					sig = new Ty[] { Ty.D, Ty.I };
					result = Ty.D;
				}
				case "isfinite" -> {
					sig = new Ty[] { Ty.D };
					result = Ty.I;
				}
				case "ule" -> {
					sig = new Ty[] { Ty.I, Ty.I };
					result = Ty.I;
				}
				default -> {
					for (Fn callee : Fn.values()) {
						if (callee.cname.equals(name)) {
							checkArgs(name, callee.params, args);
							return new Call(callee, args);
						}
					}
					throw error("unknown function " + name);
				}
			}
			checkArgs(name, sig, args);
			return new Builtin(name, args, result);
		}

		private void checkArgs(String name, Ty[] sig, List<Expr> args) {
			if (args.size() != sig.length) {
				throw error(name + " takes " + sig.length + " arguments");
			}
			for (int i = 0; i < sig.length; i++) {
				if (args.get(i).ty() != sig[i]) {
					throw error(name + " argument " + i + " must be " + sig[i]);
				}
			}
		}

	}

	// --- emitter
	// -------------------------------------------------------------------------

	private static final class Emitter {

		private final WasmWriter w;

		private final Function fn;

		private final ToIntFunction<Fn> indexOf;

		private final int tablesBase;

		/** The open control frames, innermost last: {@code true} for a loop's body. */
		private final List<Boolean> frames = new ArrayList<>();

		Emitter(WasmWriter w, Function fn, ToIntFunction<Fn> indexOf, int tablesBase) {
			this.w = w;
			this.fn = fn;
			this.indexOf = indexOf;
			this.tablesBase = tablesBase;
		}

		void emit() {
			// Locals: the params keep their indices; declared locals are grouped by type
			// (i32, i64, f64) in declaration order within each group.
			int next = this.fn.params().size();
			int[] counts = new int[Ty.values().length];
			for (Ty ty : new Ty[] { Ty.I, Ty.L, Ty.D }) {
				for (Var v : this.fn.locals()) {
					if (v.ty == ty) {
						v.index = next++;
						counts[ty.ordinal()]++;
					}
				}
			}
			int groups = 0;
			for (int c : counts) {
				groups += c > 0 ? 1 : 0;
			}
			this.w.writeUnsignedLeb128(groups);
			for (Ty ty : new Ty[] { Ty.I, Ty.L, Ty.D }) {
				if (counts[ty.ordinal()] > 0) {
					this.w.writeUnsignedLeb128(counts[ty.ordinal()]);
					this.w.write(ty.wasm);
				}
			}
			stmts(this.fn.body());
			// Every fdlibm function ends in a return; the trap below only documents
			// that the fall-through is impossible.
			this.w.write(Instruction.UNREACHABLE);
			this.w.write(Instruction.END);
		}

		private void stmts(List<Stmt> list) {
			for (Stmt s : list) {
				stmt(s);
			}
		}

		private void stmt(Stmt s) {
			switch (s) {
				case Assign a -> {
					expr(a.e());
					this.w.write(Instruction.SET_LOCAL);
					this.w.writeUnsignedLeb128(a.v().index);
				}
				case MemStore m -> {
					address(m.arr(), m.index());
					expr(m.e());
					this.w.write(m.arr().elem() == Ty.D ? Instruction.F64_STORE : Instruction.I32_STORE);
					this.w.writeUnsignedLeb128(m.arr().shift());
					this.w.writeUnsignedLeb128(m.arr().offset());
				}
				case If i -> {
					expr(i.c());
					this.w.write(Instruction.IF, 0x40);
					this.frames.add(false);
					stmts(i.then());
					if (!i.els().isEmpty()) {
						this.w.write(Instruction.ELSE);
						stmts(i.els());
					}
					this.frames.remove(this.frames.size() - 1);
					this.w.write(Instruction.END);
				}
				case While l -> {
					this.w.write(Instruction.BLOCK, 0x40);
					this.frames.add(false);
					this.w.write(Instruction.LOOP, 0x40);
					this.frames.add(true);
					if (l.c() != null) {
						expr(l.c());
						this.w.write(Instruction.I32_EQZ);
						this.w.write(Instruction.BR_IF);
						this.w.writeUnsignedLeb128(1);
					}
					stmts(l.body());
					this.w.write(Instruction.BR);
					this.w.writeUnsignedLeb128(0);
					this.frames.remove(this.frames.size() - 1);
					this.w.write(Instruction.END);
					this.frames.remove(this.frames.size() - 1);
					this.w.write(Instruction.END);
				}
				case Return r -> {
					expr(r.e());
					this.w.write(Instruction.RETURN);
				}
				case Break b -> {
					this.w.write(Instruction.BR);
					this.w.writeUnsignedLeb128(depthOfLoop() + 1);
				}
				case Continue c -> {
					this.w.write(Instruction.BR);
					this.w.writeUnsignedLeb128(depthOfLoop());
				}
			}
		}

		// The branch depth of the innermost loop's label from the current position.
		private int depthOfLoop() {
			for (int i = this.frames.size() - 1; i >= 0; i--) {
				if (this.frames.get(i)) {
					return this.frames.size() - 1 - i;
				}
			}
			throw new IllegalStateException("break/continue outside a loop");
		}

		// Pushes the element address: base + (index << shift); the array's own offset
		// rides in the load/store immediate. The base is always cited as its own
		// i32.const so a shaker probing the blob on its base word finds every reader.
		private void address(MemArray arr, Expr index) {
			this.w.write(Instruction.I32_CONST);
			this.w.writeSignedLeb128(this.tablesBase);
			if (index instanceof Num n) {
				if (n.l() != 0) {
					this.w.write(Instruction.I32_CONST);
					this.w.writeSignedLeb128((int) n.l() << arr.shift());
					this.w.write(Instruction.I32_ADD);
				}
				return;
			}
			expr(index);
			this.w.write(Instruction.I32_CONST);
			this.w.writeSignedLeb128(arr.shift());
			this.w.write(Instruction.I32_SHL);
			this.w.write(Instruction.I32_ADD);
		}

		private void expr(Expr e) {
			switch (e) {
				case Num n -> constant(n);
				case Load l -> {
					this.w.write(Instruction.GET_LOCAL);
					this.w.writeUnsignedLeb128(l.v().index);
				}
				case Unary u -> unary(u);
				case Binary b -> binary(b);
				case Logical l -> {
					expr(l.a());
					this.w.write(Instruction.IF, Type.I32.code());
					if (l.and()) {
						expr(l.b());
						this.w.write(Instruction.ELSE);
						this.w.write(Instruction.I32_CONST);
						this.w.writeSignedLeb128(0);
					}
					else {
						this.w.write(Instruction.I32_CONST);
						this.w.writeSignedLeb128(1);
						this.w.write(Instruction.ELSE);
						expr(l.b());
					}
					this.w.write(Instruction.END);
				}
				case Ternary t -> {
					expr(t.c());
					this.w.write(Instruction.IF, t.ty().wasm.code());
					expr(t.a());
					this.w.write(Instruction.ELSE);
					expr(t.b());
					this.w.write(Instruction.END);
				}
				case Cast c -> cast(c);
				case Builtin b -> builtin(b);
				case Call c -> {
					for (Expr a : c.args()) {
						expr(a);
					}
					this.w.write(Instruction.CALL);
					this.w.writeUnsignedLeb128(this.indexOf.applyAsInt(c.fn()));
				}
				case MemRead m -> {
					address(m.arr(), m.index());
					this.w.write(m.arr().elem() == Ty.D ? Instruction.F64_LOAD : Instruction.I32_LOAD);
					this.w.writeUnsignedLeb128(m.arr().shift());
					this.w.writeUnsignedLeb128(m.arr().offset());
				}
				case TableRead t -> table(t.values(), t.index(), 0);
			}
		}

		// value[i] as nested selects: values[k] when index == k, else the rest.
		private void table(double[] values, Expr index, int k) {
			f64(values[k]);
			if (k == values.length - 1) {
				return;
			}
			table(values, index, k + 1);
			expr(index);
			this.w.write(Instruction.I32_CONST);
			this.w.writeSignedLeb128(k);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.SELECT);
		}

		private void constant(Num n) {
			switch (n.ty()) {
				case D -> f64(n.d());
				case I -> {
					this.w.write(Instruction.I32_CONST);
					this.w.writeSignedLeb128((int) n.l());
				}
				case L -> {
					this.w.write(Instruction.I64_CONST);
					this.w.writeSignedLeb128(n.l());
				}
			}
		}

		private void f64(double d) {
			this.w.write(Instruction.F64_CONST);
			this.w.writeF64(d);
		}

		private void unary(Unary u) {
			switch (u.op()) {
				case "-" -> {
					if (u.ty() == Ty.D) {
						expr(u.a());
						this.w.write(Instruction.F64_NEG);
					}
					else if (u.ty() == Ty.I) {
						this.w.write(Instruction.I32_CONST);
						this.w.writeSignedLeb128(0);
						expr(u.a());
						this.w.write(Instruction.I32_SUB);
					}
					else {
						this.w.write(Instruction.I64_CONST);
						this.w.writeSignedLeb128(0L);
						expr(u.a());
						this.w.write(Instruction.I64_SUB);
					}
				}
				case "!" -> {
					expr(u.a());
					this.w.write(Instruction.I32_EQZ);
				}
				case "~" -> {
					expr(u.a());
					if (u.ty() == Ty.I) {
						this.w.write(Instruction.I32_CONST);
						this.w.writeSignedLeb128(-1);
						this.w.write(Instruction.I32_XOR);
					}
					else {
						this.w.write(Instruction.I64_CONST);
						this.w.writeSignedLeb128(-1L);
						this.w.write(Instruction.I64_XOR);
					}
				}
				default -> throw new IllegalStateException(u.op());
			}
		}

		private void binary(Binary b) {
			expr(b.a());
			expr(b.b());
			Ty operand = b.a().ty();
			int op = switch (operand) {
				case D -> switch (b.op()) {
					case "+" -> Instruction.F64_ADD;
					case "-" -> Instruction.F64_SUB;
					case "*" -> Instruction.F64_MUL;
					case "/" -> Instruction.F64_DIV;
					case "==" -> Instruction.F64_EQ;
					case "!=" -> Instruction.F64_NE;
					case "<" -> Instruction.F64_LT;
					case ">" -> Instruction.F64_GT;
					case "<=" -> Instruction.F64_LE;
					case ">=" -> Instruction.F64_GE;
					default -> throw new IllegalStateException(b.op());
				};
				case I -> switch (b.op()) {
					case "+" -> Instruction.I32_ADD;
					case "-" -> Instruction.I32_SUB;
					case "*" -> Instruction.I32_MUL;
					case "/" -> Instruction.I32_DIV_S;
					case "%" -> Instruction.I32_REM_S;
					case "&" -> Instruction.I32_AND;
					case "|" -> Instruction.I32_OR;
					case "^" -> Instruction.I32_XOR;
					case "<<" -> Instruction.I32_SHL;
					case ">>" -> Instruction.I32_SHR_S;
					case ">>>" -> Instruction.I32_SHR_U;
					case "==" -> Instruction.I32_EQ;
					case "!=" -> Instruction.I32_NE;
					case "<" -> Instruction.I32_LT_S;
					case ">" -> Instruction.I32_GT_S;
					case "<=" -> Instruction.I32_LE_S;
					case ">=" -> Instruction.I32_GE_S;
					default -> throw new IllegalStateException(b.op());
				};
				case L -> switch (b.op()) {
					case "+" -> Instruction.I64_ADD;
					case "-" -> Instruction.I64_SUB;
					case "*" -> Instruction.I64_MUL;
					case "/" -> Instruction.I64_DIV_S;
					case "%" -> Instruction.I64_REM_S;
					case "&" -> Instruction.I64_AND;
					case "|" -> Instruction.I64_OR;
					case "^" -> Instruction.I64_XOR;
					case "<<" -> Instruction.I64_SHL;
					case ">>" -> Instruction.I64_SHR_S;
					case ">>>" -> Instruction.I64_SHR_U;
					case "==" -> Instruction.I64_EQ;
					case "!=" -> Instruction.I64_NE;
					case "<" -> Instruction.I64_LT_S;
					case ">" -> Instruction.I64_GT_S;
					case "<=" -> Instruction.I64_LE_S;
					case ">=" -> Instruction.I64_GE_S;
					default -> throw new IllegalStateException(b.op());
				};
			};
			if (operand == Ty.L && (b.op().equals("<<") || b.op().equals(">>") || b.op().equals(">>>"))) {
				// The shift count of an i64 shift is an i64 in wasm; Java's is an int.
				this.w.write(Instruction.I64_EXTEND_S_I32);
			}
			this.w.write(op);
		}

		private void cast(Cast c) {
			expr(c.a());
			Ty from = c.a().ty();
			switch (c.ty()) {
				case D -> this.w.write(from == Ty.I ? Instruction.F64_CONVERT_S_I32 : Instruction.F64_CONVERT_S_I64);
				case I -> {
					if (from == Ty.D) {
						// Java's (int) d: NaN answers 0 and the range saturates.
						this.w.write(Instruction.MISC_PREFIX);
						this.w.writeUnsignedLeb128(Instruction.I32_TRUNC_SAT_F64_S);
					}
					else {
						this.w.write(Instruction.I32_WRAP_I64);
					}
				}
				case L -> {
					if (from == Ty.D) {
						this.w.write(Instruction.MISC_PREFIX);
						this.w.writeUnsignedLeb128(Instruction.I64_TRUNC_SAT_F64_S);
					}
					else {
						this.w.write(Instruction.I64_EXTEND_S_I32);
					}
				}
			}
		}

		private void builtin(Builtin b) {
			List<Expr> a = b.args();
			switch (b.name()) {
				case "HI" -> {
					// (int) (bits >> 32)
					expr(a.get(0));
					this.w.write(Instruction.I64_REINTERPRET_F64);
					this.w.write(Instruction.I64_CONST);
					this.w.writeSignedLeb128(32L);
					this.w.write(Instruction.I64_SHR_S);
					this.w.write(Instruction.I32_WRAP_I64);
				}
				case "LO" -> {
					expr(a.get(0));
					this.w.write(Instruction.I64_REINTERPRET_F64);
					this.w.write(Instruction.I32_WRAP_I64);
				}
				case "SET_HI" -> {
					// (bits & 0xFFFFFFFF) | ((long) high << 32)
					expr(a.get(0));
					this.w.write(Instruction.I64_REINTERPRET_F64);
					this.w.write(Instruction.I64_CONST);
					this.w.writeSignedLeb128(0xFFFF_FFFFL);
					this.w.write(Instruction.I64_AND);
					expr(a.get(1));
					this.w.write(Instruction.I64_EXTEND_U_I32);
					this.w.write(Instruction.I64_CONST);
					this.w.writeSignedLeb128(32L);
					this.w.write(Instruction.I64_SHL);
					this.w.write(Instruction.I64_OR);
					this.w.write(Instruction.F64_REINTERPRET_I64);
				}
				case "SET_LO" -> {
					// (bits & 0xFFFFFFFF00000000) | (low & 0xFFFFFFFF)
					expr(a.get(0));
					this.w.write(Instruction.I64_REINTERPRET_F64);
					this.w.write(Instruction.I64_CONST);
					this.w.writeSignedLeb128(0xFFFF_FFFF_0000_0000L);
					this.w.write(Instruction.I64_AND);
					expr(a.get(1));
					this.w.write(Instruction.I64_EXTEND_U_I32);
					this.w.write(Instruction.I64_OR);
					this.w.write(Instruction.F64_REINTERPRET_I64);
				}
				case "HI_LO" -> {
					// ((long) high << 32) | (low & 0xFFFFFFFF)
					expr(a.get(0));
					this.w.write(Instruction.I64_EXTEND_U_I32);
					this.w.write(Instruction.I64_CONST);
					this.w.writeSignedLeb128(32L);
					this.w.write(Instruction.I64_SHL);
					expr(a.get(1));
					this.w.write(Instruction.I64_EXTEND_U_I32);
					this.w.write(Instruction.I64_OR);
					this.w.write(Instruction.F64_REINTERPRET_I64);
				}
				case "abs" -> {
					expr(a.get(0));
					this.w.write(Instruction.F64_ABS);
				}
				case "sqrt" -> {
					expr(a.get(0));
					this.w.write(Instruction.F64_SQRT);
				}
				case "floor" -> {
					expr(a.get(0));
					this.w.write(Instruction.F64_FLOOR);
				}
				case "pow2" -> {
					expr(a.get(0));
					pow2();
				}
				case "scalb" -> {
					// x * 2^n, exact for a normal 2^n: the callers keep n in range or
					// split the scaling themselves (Pow's subnormal output).
					expr(a.get(0));
					expr(a.get(1));
					pow2();
					this.w.write(Instruction.F64_MUL);
				}
				case "isfinite" -> {
					expr(a.get(0));
					this.w.write(Instruction.F64_ABS);
					f64(Double.POSITIVE_INFINITY);
					this.w.write(Instruction.F64_LT);
				}
				case "ule" -> {
					expr(a.get(0));
					expr(a.get(1));
					this.w.write(Instruction.I32_LE_U);
				}
				default -> throw new IllegalStateException(b.name());
			}
		}

		// Consumes an i32 n and leaves the f64 2^n, built from its exponent bits.
		private void pow2() {
			this.w.write(Instruction.I64_EXTEND_S_I32);
			this.w.write(Instruction.I64_CONST);
			this.w.writeSignedLeb128(1023L);
			this.w.write(Instruction.I64_ADD);
			this.w.write(Instruction.I64_CONST);
			this.w.writeSignedLeb128(52L);
			this.w.write(Instruction.I64_SHL);
			this.w.write(Instruction.F64_REINTERPRET_I64);
		}

	}

	// --- the sources
	// -----------------------------------------------------------------------

	/**
	 * fdlibm in the C-like subset above, one function per {@link Fn}, transliterated from
	 * {@code java.lang.FdLibm} (JDK 25). Every arithmetic expression keeps the Java
	 * operand order and grouping -- that is what makes the bits identical. The only
	 * rewrites are syntactic: {@code switch} as if-chains, {@code for} as {@code while},
	 * the {@code __HI}/{@code __LO} accessors as {@code HI}/{@code LO}/
	 * {@code SET_HI}/{@code SET_LO}/{@code HI_LO}, {@code Math.scalb} as {@code scalb}
	 * where the power of two is normal, and the multi-valued {@code y[]} of the reduction
	 * as the {@code y} scratch array in linear memory.
	 */
	private static final class Sources {

		private Sources() {
		}

		static String of(Fn fn) {
			return switch (fn) {
				case EXP -> EXP;
				case EXPM1 -> EXPM1;
				case LOG -> LOG;
				case LOG1P -> LOG1P;
				case SIN -> SIN;
				case COS -> COS;
				case TAN -> TAN;
				case K_SIN -> K_SIN;
				case K_COS -> K_COS;
				case K_TAN -> K_TAN;
				case REM_PIO2 -> REM_PIO2;
				case K_REM_PIO2 -> K_REM_PIO2;
				case ASIN -> ASIN;
				case ACOS -> ACOS;
				case ATAN -> ATAN;
				case ATAN2 -> ATAN2;
				case SINH -> SINH;
				case COSH -> COSH;
				case TANH -> TANH;
				case POW -> POW;
				case HYPOT -> HYPOT;
			};
		}

		static final String EXP = """
				const double huge = 1.0e+300;
				const double twom1000 = 0x1.0p-1000;
				const double o_threshold = 0x1.62e42fefa39efp9;
				const double u_threshold = -0x1.74910d52d3051p9;
				const double[] half = {0.5, -0.5};
				const double[] ln2HI = {0x1.62e42feep-1, -0x1.62e42feep-1};
				const double[] ln2LO = {0x1.a39ef35793c76p-33, -0x1.a39ef35793c76p-33};
				const double invln2 = 0x1.71547652b82fep0;
				const double P1 = 0x1.555555555553ep-3;
				const double P2 = -0x1.6c16c16bebd93p-9;
				const double P3 = 0x1.1566aaf25de2cp-14;
				const double P4 = -0x1.bbd41c5d26bf1p-20;
				const double P5 = 0x1.6376972bea4d0p-25;
				double exp(double x) {
				  double y, hi = 0.0, lo = 0.0, c, t;
				  int k = 0, xsb, hx;
				  hx = HI(x);
				  xsb = (hx >> 31) & 1;
				  hx = hx & EXP_SIGNIF_BITS;
				  if (hx >= 0x40862E42) {
				    if (hx >= 0x7ff00000) {
				      if (((hx & 0xfffff) | LO(x)) != 0) return x + x;
				      else return (xsb == 0) ? x : 0.0;
				    }
				    if (x > o_threshold) return huge * huge;
				    if (x < u_threshold) return twom1000 * twom1000;
				  }
				  if (hx > 0x3fd62e42) {
				    if (hx < 0x3FF0A2B2) {
				      hi = x - ln2HI[xsb];
				      lo = ln2LO[xsb];
				      k = 1 - xsb - xsb;
				    } else {
				      k = (int) (invln2 * x + half[xsb]);
				      t = (double) k;
				      hi = x - t * ln2HI[0];
				      lo = t * ln2LO[0];
				    }
				    x = hi - lo;
				  } else if (hx < 0x3e300000) {
				    if (huge + x > 1.0) return 1.0 + x;
				  } else {
				    k = 0;
				  }
				  t = x * x;
				  c = x - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
				  if (k == 0) return 1.0 - ((x * c) / (c - 2.0) - x);
				  else y = 1.0 - ((lo - (x * c) / (2.0 - c)) - hi);
				  if (k >= -1021) {
				    y = SET_HI(y, HI(y) + (k << 20));
				    return y;
				  } else {
				    y = SET_HI(y, HI(y) + ((k + 1000) << 20));
				    return y * twom1000;
				  }
				}
				""";

		static final String EXPM1 = """
				const double huge = 1.0e+300;
				const double tiny = 1.0e-300;
				const double o_threshold = 0x1.62e42fefa39efp9;
				const double ln2_hi = 0x1.62e42feep-1;
				const double ln2_lo = 0x1.a39ef35793c76p-33;
				const double invln2 = 0x1.71547652b82fep0;
				const double Q1 = -0x1.11111111110f4p-5;
				const double Q2 = 0x1.a01a019fe5585p-10;
				const double Q3 = -0x1.4ce199eaadbb7p-14;
				const double Q4 = 0x1.0cfca86e65239p-18;
				const double Q5 = -0x1.afdb76e09c32dp-23;
				double expm1(double x) {
				  double y, hi, lo, c = 0.0, t, e, hxs, hfx, r1;
				  int k, xsb, hx;
				  hx = HI(x);
				  xsb = hx & SIGN_BIT;
				  hx = hx & EXP_SIGNIF_BITS;
				  if (hx >= 0x4043687A) {
				    if (hx >= 0x40862E42) {
				      if (hx >= 0x7ff00000) {
				        if (((hx & 0xfffff) | LO(x)) != 0) return x + x;
				        else return (xsb == 0) ? x : -1.0;
				      }
				      if (x > o_threshold) return huge * huge;
				    }
				    if (xsb != 0) {
				      if (x + tiny < 0.0) return tiny - 1.0;
				    }
				  }
				  if (hx > 0x3fd62e42) {
				    if (hx < 0x3FF0A2B2) {
				      if (xsb == 0) {
				        hi = x - ln2_hi;
				        lo = ln2_lo;
				        k = 1;
				      } else {
				        hi = x + ln2_hi;
				        lo = -ln2_lo;
				        k = -1;
				      }
				    } else {
				      k = (int) (invln2 * x + ((xsb == 0) ? 0.5 : -0.5));
				      t = (double) k;
				      hi = x - t * ln2_hi;
				      lo = t * ln2_lo;
				    }
				    x = hi - lo;
				    c = (hi - x) - lo;
				  } else if (hx < 0x3c900000) {
				    t = huge + x;
				    return x - (t - (huge + x));
				  } else {
				    k = 0;
				  }
				  hfx = 0.5 * x;
				  hxs = x * hfx;
				  r1 = 1.0 + hxs * (Q1 + hxs * (Q2 + hxs * (Q3 + hxs * (Q4 + hxs * Q5))));
				  t = 3.0 - r1 * hfx;
				  e = hxs * ((r1 - t) / (6.0 - x * t));
				  if (k == 0) return x - (x * e - hxs);
				  else {
				    e = (x * (e - c) - c);
				    e = e - hxs;
				    if (k == -1) return 0.5 * (x - e) - 0.5;
				    if (k == 1) {
				      if (x < -0.25) return -2.0 * (e - (x + 0.5));
				      else return 1.0 + 2.0 * (x - e);
				    }
				    if (k <= -2 || k > 56) {
				      y = 1.0 - (e - x);
				      y = SET_HI(y, HI(y) + (k << 20));
				      return y - 1.0;
				    }
				    t = 1.0;
				    if (k < 20) {
				      t = SET_HI(t, 0x3ff00000 - (0x200000 >> k));
				      y = t - (e - x);
				      y = SET_HI(y, HI(y) + (k << 20));
				    } else {
				      t = SET_HI(t, ((0x3ff - k) << 20));
				      y = x - (e + t);
				      y = y + 1.0;
				      y = SET_HI(y, HI(y) + (k << 20));
				    }
				  }
				  return y;
				}
				""";

		static final String LOG = """
				const double ln2_hi = 0x1.62e42feep-1;
				const double ln2_lo = 0x1.a39ef35793c76p-33;
				const double Lg1 = 0x1.5555555555593p-1;
				const double Lg2 = 0x1.999999997fa04p-2;
				const double Lg3 = 0x1.2492494229359p-2;
				const double Lg4 = 0x1.c71c51d8e78afp-3;
				const double Lg5 = 0x1.7466496cb03dep-3;
				const double Lg6 = 0x1.39a09d078c69fp-3;
				const double Lg7 = 0x1.2f112df3e5244p-3;
				double log(double x) {
				  double hfsq, f, s, z, R, w, t1, t2, dk;
				  int k, hx, i, j, lx;
				  hx = HI(x);
				  lx = LO(x);
				  k = 0;
				  if (hx < 0x00100000) {
				    if (((hx & EXP_SIGNIF_BITS) | lx) == 0) return -TWO54 / 0.0;
				    if (hx < 0) return (x - x) / 0.0;
				    k = k - 54;
				    x = x * TWO54;
				    hx = HI(x);
				  }
				  if (hx >= EXP_BITS) return x + x;
				  k = k + ((hx >> 20) - 1023);
				  hx = hx & 0x000fffff;
				  i = (hx + 0x95f64) & 0x100000;
				  x = SET_HI(x, hx | (i ^ 0x3ff00000));
				  k = k + (i >> 20);
				  f = x - 1.0;
				  if ((0x000fffff & (2 + hx)) < 3) {
				    if (f == 0.0) {
				      if (k == 0) return 0.0;
				      else {
				        dk = (double) k;
				        return dk * ln2_hi + dk * ln2_lo;
				      }
				    }
				    R = f * f * (0.5 - 0.33333333333333333 * f);
				    if (k == 0) return f - R;
				    else {
				      dk = (double) k;
				      return dk * ln2_hi - ((R - dk * ln2_lo) - f);
				    }
				  }
				  s = f / (2.0 + f);
				  dk = (double) k;
				  z = s * s;
				  i = hx - 0x6147a;
				  w = z * z;
				  j = 0x6b851 - hx;
				  t1 = w * (Lg2 + w * (Lg4 + w * Lg6));
				  t2 = z * (Lg1 + w * (Lg3 + w * (Lg5 + w * Lg7)));
				  i = i | j;
				  R = t2 + t1;
				  if (i > 0) {
				    hfsq = 0.5 * f * f;
				    if (k == 0) return f - (hfsq - s * (hfsq + R));
				    else return dk * ln2_hi - ((hfsq - (s * (hfsq + R) + dk * ln2_lo)) - f);
				  } else {
				    if (k == 0) return f - s * (f - R);
				    else return dk * ln2_hi - ((s * (f - R) - dk * ln2_lo) - f);
				  }
				}
				""";

		static final String LOG1P = """
				const double ln2_hi = 0x1.62e42feep-1;
				const double ln2_lo = 0x1.a39ef35793c76p-33;
				const double Lp1 = 0x1.5555555555593p-1;
				const double Lp2 = 0x1.999999997fa04p-2;
				const double Lp3 = 0x1.2492494229359p-2;
				const double Lp4 = 0x1.c71c51d8e78afp-3;
				const double Lp5 = 0x1.7466496cb03dep-3;
				const double Lp6 = 0x1.39a09d078c69fp-3;
				const double Lp7 = 0x1.2f112df3e5244p-3;
				double log1p(double x) {
				  double hfsq, f = 0.0, c = 0.0, s, z, R, u;
				  int k, hx, hu = 0, ax;
				  hx = HI(x);
				  ax = hx & EXP_SIGNIF_BITS;
				  k = 1;
				  if (hx < 0x3FDA827A) {
				    if (ax >= 0x3ff00000) {
				      if (x == -1.0) return -INFINITY;
				      else return NAN;
				    }
				    if (ax < 0x3e200000) {
				      if (TWO54 + x > 0.0 && ax < 0x3c900000) return x;
				      else return x - x * x * 0.5;
				    }
				    if (hx > 0 || hx <= 0xbfd2bec3) {
				      k = 0;
				      f = x;
				      hu = 1;
				    }
				  }
				  if (hx >= EXP_BITS) return x + x;
				  if (k != 0) {
				    if (hx < 0x43400000) {
				      u = 1.0 + x;
				      hu = HI(u);
				      k = (hu >> 20) - 1023;
				      c = (k > 0) ? 1.0 - (u - x) : x - (u - 1.0);
				      c = c / u;
				    } else {
				      u = x;
				      hu = HI(u);
				      k = (hu >> 20) - 1023;
				      c = 0.0;
				    }
				    hu = hu & 0x000fffff;
				    if (hu < 0x6a09e) {
				      u = SET_HI(u, hu | 0x3ff00000);
				    } else {
				      k = k + 1;
				      u = SET_HI(u, hu | 0x3fe00000);
				      hu = (0x00100000 - hu) >> 2;
				    }
				    f = u - 1.0;
				  }
				  hfsq = 0.5 * f * f;
				  if (hu == 0) {
				    if (f == 0.0) {
				      if (k == 0) return 0.0;
				      else {
				        c = c + (double) k * ln2_lo;
				        return (double) k * ln2_hi + c;
				      }
				    }
				    R = hfsq * (1.0 - 0.66666666666666666 * f);
				    if (k == 0) return f - R;
				    else return (double) k * ln2_hi - ((R - ((double) k * ln2_lo + c)) - f);
				  }
				  s = f / (2.0 + f);
				  z = s * s;
				  R = z * (Lp1 + z * (Lp2 + z * (Lp3 + z * (Lp4 + z * (Lp5 + z * (Lp6 + z * Lp7))))));
				  if (k == 0) return f - (hfsq - s * (hfsq + R));
				  else return (double) k * ln2_hi - ((hfsq - (s * (hfsq + R) + ((double) k * ln2_lo + c))) - f);
				}
				""";

		static final String K_SIN = """
				const double S1 = -0x1.5555555555549p-3;
				const double S2 = 0x1.111111110f8a6p-7;
				const double S3 = -0x1.a01a019c161d5p-13;
				const double S4 = 0x1.71de357b1fe7dp-19;
				const double S5 = -0x1.ae5e68a2b9cebp-26;
				const double S6 = 0x1.5d93a5acfd57cp-33;
				double k_sin(double x, double y, int iy) {
				  double z, r, v;
				  int ix;
				  ix = HI(x) & EXP_SIGNIF_BITS;
				  if (ix < 0x3e400000) {
				    if ((int) x == 0) return x;
				  }
				  z = x * x;
				  v = z * x;
				  r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
				  if (iy == 0) return x + v * (S1 + z * r);
				  else return x - ((z * (0.5 * y - v * r) - y) - v * S1);
				}
				""";

		static final String K_COS = """
				const double C1 = 0x1.555555555554cp-5;
				const double C2 = -0x1.6c16c16c15177p-10;
				const double C3 = 0x1.a01a019cb159p-16;
				const double C4 = -0x1.27e4f809c52adp-22;
				const double C5 = 0x1.1ee9ebdb4b1c4p-29;
				const double C6 = -0x1.8fae9be8838d4p-37;
				double k_cos(double x, double y) {
				  double a, hz, z, r, qx = 0.0;
				  int ix;
				  ix = HI(x) & EXP_SIGNIF_BITS;
				  if (ix < 0x3e400000) {
				    if ((int) x == 0) return 1.0;
				  }
				  z = x * x;
				  r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
				  if (ix < 0x3FD33333) {
				    return 1.0 - (0.5 * z - (z * r - x * y));
				  } else {
				    if (ix > 0x3fe90000) qx = 0.28125;
				    else qx = HI_LO(ix - 0x00200000, 0);
				    hz = 0.5 * z - qx;
				    a = 1.0 - qx;
				    return a - (hz - (z * r - x * y));
				  }
				}
				""";

		static final String K_TAN = """
				const double pio4 = 0x1.921fb54442d18p-1;
				const double pio4lo = 0x1.1a62633145c07p-55;
				const double T0 = 0x1.5555555555563p-2;
				const double T1 = 0x1.111111110fe7ap-3;
				const double T2 = 0x1.ba1ba1bb341fep-5;
				const double T3 = 0x1.664f48406d637p-6;
				const double T4 = 0x1.226e3e96e8493p-7;
				const double T5 = 0x1.d6d22c9560328p-9;
				const double T6 = 0x1.7dbc8fee08315p-10;
				const double T7 = 0x1.344d8f2f26501p-11;
				const double T8 = 0x1.026f71a8d1068p-12;
				const double T9 = 0x1.47e88a03792a6p-14;
				const double T10 = 0x1.2b80f32f0a7e9p-14;
				const double T11 = -0x1.375cbdb605373p-16;
				const double T12 = 0x1.b2a7074bf7ad4p-16;
				double k_tan(double x, double y, int iy) {
				  double z, r, v, w, s, a, t;
				  int ix, hx;
				  hx = HI(x);
				  ix = hx & EXP_SIGNIF_BITS;
				  if (ix < 0x3e300000) {
				    if ((int) x == 0) {
				      if (((ix | LO(x)) | (iy + 1)) == 0) {
				        return 1.0 / abs(x);
				      } else {
				        if (iy == 1) return x;
				        else {
				          w = x + y;
				          z = w;
				          z = SET_LO(z, 0);
				          v = y - (z - x);
				          a = -1.0 / w;
				          t = a;
				          t = SET_LO(t, 0);
				          s = 1.0 + t * z;
				          return t + a * (s + t * v);
				        }
				      }
				    }
				  }
				  if (ix >= 0x3FE59428) {
				    if (hx < 0) {
				      x = -x;
				      y = -y;
				    }
				    z = pio4 - x;
				    w = pio4lo - y;
				    x = z + w;
				    y = 0.0;
				  }
				  z = x * x;
				  w = z * z;
				  r = T1 + w * (T3 + w * (T5 + w * (T7 + w * (T9 + w * T11))));
				  v = z * (T2 + w * (T4 + w * (T6 + w * (T8 + w * (T10 + w * T12)))));
				  s = z * x;
				  r = y + z * (s * (r + v) + y);
				  r = r + T0 * s;
				  w = x + r;
				  if (ix >= 0x3FE59428) {
				    v = (double) iy;
				    return (double) (1 - ((hx >> 30) & 2)) * (v - 2.0 * (x - (w * w / (w + v) - r)));
				  }
				  if (iy == 1) return w;
				  else {
				    z = w;
				    z = SET_LO(z, 0);
				    v = r - (z - x);
				    a = -1.0 / w;
				    t = a;
				    t = SET_LO(t, 0);
				    s = 1.0 + t * z;
				    return t + a * (s + t * v);
				  }
				}
				""";

		static final String SIN = """
				double sin(double x) {
				  int n, ix;
				  ix = HI(x) & EXP_SIGNIF_BITS;
				  if (ix <= 0x3fe921fb) return k_sin(x, 0.0, 0);
				  else if (ix >= EXP_BITS) return x - x;
				  else {
				    n = rem_pio2(x);
				    n = n & 3;
				    if (n == 0) return k_sin(y[0], y[1], 1);
				    else if (n == 1) return k_cos(y[0], y[1]);
				    else if (n == 2) return -k_sin(y[0], y[1], 1);
				    else return -k_cos(y[0], y[1]);
				  }
				}
				""";

		static final String COS = """
				double cos(double x) {
				  int n, ix;
				  ix = HI(x) & EXP_SIGNIF_BITS;
				  if (ix <= 0x3fe921fb) return k_cos(x, 0.0);
				  else if (ix >= EXP_BITS) return x - x;
				  else {
				    n = rem_pio2(x);
				    n = n & 3;
				    if (n == 0) return k_cos(y[0], y[1]);
				    else if (n == 1) return -k_sin(y[0], y[1], 1);
				    else if (n == 2) return -k_cos(y[0], y[1]);
				    else return k_sin(y[0], y[1], 1);
				  }
				}
				""";

		static final String TAN = """
				double tan(double x) {
				  int n, ix;
				  ix = HI(x) & EXP_SIGNIF_BITS;
				  if (ix <= 0x3fe921fb) return k_tan(x, 0.0, 1);
				  else if (ix >= EXP_BITS) return x - x;
				  else {
				    n = rem_pio2(x);
				    return k_tan(y[0], y[1], 1 - ((n & 1) << 1));
				  }
				}
				""";

		static final String REM_PIO2 = """
				const double invpio2 = 0x1.45f306dc9c883p-1;
				const double pio2_1 = 0x1.921fb544p0;
				const double pio2_1t = 0x1.0b4611a626331p-34;
				const double pio2_2 = 0x1.0b4611a6p-34;
				const double pio2_2t = 0x1.3198a2e037073p-69;
				const double pio2_3 = 0x1.3198a2ep-69;
				const double pio2_3t = 0x1.b839a252049c1p-104;
				int rem_pio2(double x) {
				  double z = 0.0, w, t, r, fn;
				  int e0, i, j, nx, n, ix, hx;
				  hx = HI(x);
				  ix = hx & EXP_SIGNIF_BITS;
				  if (ix <= 0x3fe921fb) {
				    y[0] = x;
				    y[1] = 0.0;
				    return 0;
				  }
				  if (ix < 0x4002d97c) {
				    if (hx > 0) {
				      z = x - pio2_1;
				      if (ix != 0x3ff921fb) {
				        y[0] = z - pio2_1t;
				        y[1] = (z - y[0]) - pio2_1t;
				      } else {
				        z = z - pio2_2;
				        y[0] = z - pio2_2t;
				        y[1] = (z - y[0]) - pio2_2t;
				      }
				      return 1;
				    } else {
				      z = x + pio2_1;
				      if (ix != 0x3ff921fb) {
				        y[0] = z + pio2_1t;
				        y[1] = (z - y[0]) + pio2_1t;
				      } else {
				        z = z + pio2_2;
				        y[0] = z + pio2_2t;
				        y[1] = (z - y[0]) + pio2_2t;
				      }
				      return -1;
				    }
				  }
				  if (ix <= 0x413921fb) {
				    t = abs(x);
				    n = (int) (t * invpio2 + 0.5);
				    fn = (double) n;
				    r = t - fn * pio2_1;
				    w = fn * pio2_1t;
				    if (n < 32 && ix != npio2_hw[n - 1]) {
				      y[0] = r - w;
				    } else {
				      j = ix >> 20;
				      y[0] = r - w;
				      i = j - ((HI(y[0]) >> 20) & 0x7ff);
				      if (i > 16) {
				        t = r;
				        w = fn * pio2_2;
				        r = t - w;
				        w = fn * pio2_2t - ((t - r) - w);
				        y[0] = r - w;
				        i = j - ((HI(y[0]) >> 20) & 0x7ff);
				        if (i > 49) {
				          t = r;
				          w = fn * pio2_3;
				          r = t - w;
				          w = fn * pio2_3t - ((t - r) - w);
				          y[0] = r - w;
				        }
				      }
				    }
				    y[1] = (r - y[0]) - w;
				    if (hx < 0) {
				      y[0] = -y[0];
				      y[1] = -y[1];
				      return -n;
				    } else return n;
				  }
				  if (ix >= EXP_BITS) {
				    y[0] = x - x;
				    y[1] = x - x;
				    return 0;
				  }
				  z = SET_LO(z, LO(x));
				  e0 = (ix >> 20) - 1046;
				  z = SET_HI(z, ix - (e0 << 20));
				  i = 0;
				  while (i < 2) {
				    tx[i] = (double) ((int) z);
				    z = (z - tx[i]) * TWO24;
				    i = i + 1;
				  }
				  tx[2] = z;
				  nx = 3;
				  while (tx[nx - 1] == 0.0) nx = nx - 1;
				  n = krem(e0, nx);
				  if (hx < 0) {
				    y[0] = -y[0];
				    y[1] = -y[1];
				    return -n;
				  }
				  return n;
				}
				""";

		static final String K_REM_PIO2 = """
				const double[] PIo2 = {0x1.921fb4p0, 0x1.4442dp-24, 0x1.846988p-48, 0x1.8cc516p-72,
				    0x1.01b838p-96, 0x1.a25204p-120, 0x1.382228p-145, 0x1.9f31dp-169};
				const double twon24 = 0x1.0p-24;
				int krem(int e0, int nx) {
				  int jz, jx, jv, jp, jk, carry, n, i, j, k, m, q0, ih;
				  double z, fw;
				  jk = 4;
				  jp = jk;
				  jx = nx - 1;
				  jv = (e0 - 3) / 24;
				  if (jv < 0) jv = 0;
				  q0 = e0 - 24 * (jv + 1);
				  j = jv - jx;
				  m = jx + jk;
				  i = 0;
				  while (i <= m) {
				    f[i] = (j < 0) ? 0.0 : (double) ipio2[j];
				    i = i + 1;
				    j = j + 1;
				  }
				  i = 0;
				  while (i <= jk) {
				    j = 0;
				    fw = 0.0;
				    while (j <= jx) {
				      fw = fw + tx[j] * f[jx + i - j];
				      j = j + 1;
				    }
				    q[i] = fw;
				    i = i + 1;
				  }
				  jz = jk;
				  while (1) {
				    i = 0;
				    j = jz;
				    z = q[jz];
				    while (j > 0) {
				      fw = (double) ((int) (twon24 * z));
				      iq[i] = (int) (z - TWO24 * fw);
				      z = q[j - 1] + fw;
				      i = i + 1;
				      j = j - 1;
				    }
				    z = scalb(z, q0);
				    z = z - 8.0 * floor(z * 0.125);
				    n = (int) z;
				    z = z - (double) n;
				    ih = 0;
				    if (q0 > 0) {
				      i = (iq[jz - 1] >> (24 - q0));
				      n = n + i;
				      iq[jz - 1] = iq[jz - 1] - (i << (24 - q0));
				      ih = iq[jz - 1] >> (23 - q0);
				    } else if (q0 == 0) {
				      ih = iq[jz - 1] >> 23;
				    } else if (z >= 0.5) {
				      ih = 2;
				    }
				    if (ih > 0) {
				      n = n + 1;
				      carry = 0;
				      i = 0;
				      while (i < jz) {
				        j = iq[i];
				        if (carry == 0) {
				          if (j != 0) {
				            carry = 1;
				            iq[i] = 0x1000000 - j;
				          }
				        } else {
				          iq[i] = 0xffffff - j;
				        }
				        i = i + 1;
				      }
				      if (q0 > 0) {
				        if (q0 == 1) iq[jz - 1] = iq[jz - 1] & 0x7fffff;
				        else if (q0 == 2) iq[jz - 1] = iq[jz - 1] & 0x3fffff;
				      }
				      if (ih == 2) {
				        z = 1.0 - z;
				        if (carry != 0) z = z - scalb(1.0, q0);
				      }
				    }
				    if (z == 0.0) {
				      j = 0;
				      i = jz - 1;
				      while (i >= jk) {
				        j = j | iq[i];
				        i = i - 1;
				      }
				      if (j == 0) {
				        k = 1;
				        while (iq[jk - k] == 0) k = k + 1;
				        i = jz + 1;
				        while (i <= jz + k) {
				          f[jx + i] = (double) ipio2[jv + i];
				          j = 0;
				          fw = 0.0;
				          while (j <= jx) {
				            fw = fw + tx[j] * f[jx + i - j];
				            j = j + 1;
				          }
				          q[i] = fw;
				          i = i + 1;
				        }
				        jz = jz + k;
				        continue;
				      } else {
				        break;
				      }
				    } else {
				      break;
				    }
				  }
				  if (z == 0.0) {
				    jz = jz - 1;
				    q0 = q0 - 24;
				    while (iq[jz] == 0) {
				      jz = jz - 1;
				      q0 = q0 - 24;
				    }
				  } else {
				    z = scalb(z, -q0);
				    if (z >= TWO24) {
				      fw = (double) ((int) (twon24 * z));
				      iq[jz] = (int) (z - TWO24 * fw);
				      jz = jz + 1;
				      q0 = q0 + 24;
				      iq[jz] = (int) fw;
				    } else {
				      iq[jz] = (int) z;
				    }
				  }
				  fw = scalb(1.0, q0);
				  i = jz;
				  while (i >= 0) {
				    q[i] = fw * (double) iq[i];
				    fw = fw * twon24;
				    i = i - 1;
				  }
				  i = jz;
				  while (i >= 0) {
				    fw = 0.0;
				    k = 0;
				    while (k <= jp && k <= jz - i) {
				      fw = fw + PIo2[k] * q[i + k];
				      k = k + 1;
				    }
				    fq[jz - i] = fw;
				    i = i - 1;
				  }
				  fw = 0.0;
				  i = jz;
				  while (i >= 0) {
				    fw = fw + fq[i];
				    i = i - 1;
				  }
				  y[0] = (ih == 0) ? fw : -fw;
				  fw = fq[0] - fw;
				  i = 1;
				  while (i <= jz) {
				    fw = fw + fq[i];
				    i = i + 1;
				  }
				  y[1] = (ih == 0) ? fw : -fw;
				  return n & 7;
				}
				""";

		static final String ASIN = """
				const double pio2_hi = 0x1.921fb54442d18p0;
				const double pio2_lo = 0x1.1a62633145c07p-54;
				const double pio4_hi = 0x1.921fb54442d18p-1;
				const double pS0 = 0x1.5555555555555p-3;
				const double pS1 = -0x1.4d61203eb6f7dp-2;
				const double pS2 = 0x1.9c1550e884455p-3;
				const double pS3 = -0x1.48228b5688f3bp-5;
				const double pS4 = 0x1.9efe07501b288p-11;
				const double pS5 = 0x1.23de10dfdf709p-15;
				const double qS1 = -0x1.33a271c8a2d4bp1;
				const double qS2 = 0x1.02ae59c598ac8p1;
				const double qS3 = -0x1.6066c1b8d0159p-1;
				const double qS4 = 0x1.3b8c5b12e9282p-4;
				double asin(double x) {
				  double t = 0.0, w, p, q, c, r, s;
				  int hx, ix;
				  hx = HI(x);
				  ix = hx & EXP_SIGNIF_BITS;
				  if (ix >= 0x3ff00000) {
				    if (((ix - 0x3ff00000) | LO(x)) == 0) return x * pio2_hi + x * pio2_lo;
				    return (x - x) / (x - x);
				  } else if (ix < 0x3fe00000) {
				    if (ix < 0x3e400000) {
				      if (HUGE + x > 1.0) return x;
				    } else {
				      t = x * x;
				    }
				    p = t * (pS0 + t * (pS1 + t * (pS2 + t * (pS3 + t * (pS4 + t * pS5)))));
				    q = 1.0 + t * (qS1 + t * (qS2 + t * (qS3 + t * qS4)));
				    w = p / q;
				    return x + x * w;
				  }
				  w = 1.0 - abs(x);
				  t = w * 0.5;
				  p = t * (pS0 + t * (pS1 + t * (pS2 + t * (pS3 + t * (pS4 + t * pS5)))));
				  q = 1.0 + t * (qS1 + t * (qS2 + t * (qS3 + t * qS4)));
				  s = sqrt(t);
				  if (ix >= 0x3FEF3333) {
				    w = p / q;
				    t = pio2_hi - (2.0 * (s + s * w) - pio2_lo);
				  } else {
				    w = s;
				    w = SET_LO(w, 0);
				    c = (t - w * w) / (s + w);
				    r = p / q;
				    p = 2.0 * s * r - (pio2_lo - 2.0 * c);
				    q = pio4_hi - 2.0 * w;
				    t = pio4_hi - (p - q);
				  }
				  return (hx > 0) ? t : -t;
				}
				""";

		static final String ACOS = """
				const double pio2_hi = 0x1.921fb54442d18p0;
				const double pio2_lo = 0x1.1a62633145c07p-54;
				const double pS0 = 0x1.5555555555555p-3;
				const double pS1 = -0x1.4d61203eb6f7dp-2;
				const double pS2 = 0x1.9c1550e884455p-3;
				const double pS3 = -0x1.48228b5688f3bp-5;
				const double pS4 = 0x1.9efe07501b288p-11;
				const double pS5 = 0x1.23de10dfdf709p-15;
				const double qS1 = -0x1.33a271c8a2d4bp1;
				const double qS2 = 0x1.02ae59c598ac8p1;
				const double qS3 = -0x1.6066c1b8d0159p-1;
				const double qS4 = 0x1.3b8c5b12e9282p-4;
				double acos(double x) {
				  double z, p, q, r, w, s, c, df;
				  int hx, ix;
				  hx = HI(x);
				  ix = hx & EXP_SIGNIF_BITS;
				  if (ix >= 0x3ff00000) {
				    if (((ix - 0x3ff00000) | LO(x)) == 0) {
				      if (hx > 0) return 0.0;
				      else return PI + 2.0 * pio2_lo;
				    }
				    return (x - x) / (x - x);
				  }
				  if (ix < 0x3fe00000) {
				    if (ix <= 0x3c600000) return pio2_hi + pio2_lo;
				    z = x * x;
				    p = z * (pS0 + z * (pS1 + z * (pS2 + z * (pS3 + z * (pS4 + z * pS5)))));
				    q = 1.0 + z * (qS1 + z * (qS2 + z * (qS3 + z * qS4)));
				    r = p / q;
				    return pio2_hi - (x - (pio2_lo - x * r));
				  } else if (hx < 0) {
				    z = (1.0 + x) * 0.5;
				    p = z * (pS0 + z * (pS1 + z * (pS2 + z * (pS3 + z * (pS4 + z * pS5)))));
				    q = 1.0 + z * (qS1 + z * (qS2 + z * (qS3 + z * qS4)));
				    s = sqrt(z);
				    r = p / q;
				    w = r * s - pio2_lo;
				    return PI - 2.0 * (s + w);
				  } else {
				    z = (1.0 - x) * 0.5;
				    s = sqrt(z);
				    df = s;
				    df = SET_LO(df, 0);
				    c = (z - df * df) / (s + df);
				    p = z * (pS0 + z * (pS1 + z * (pS2 + z * (pS3 + z * (pS4 + z * pS5)))));
				    q = 1.0 + z * (qS1 + z * (qS2 + z * (qS3 + z * qS4)));
				    r = p / q;
				    w = r * s + c;
				    return 2.0 * (df + w);
				  }
				}
				""";

		static final String ATAN = """
				const double[] atanhi = {0x1.dac670561bb4fp-2, 0x1.921fb54442d18p-1, 0x1.f730bd281f69bp-1, 0x1.921fb54442d18p0};
				const double[] atanlo = {0x1.a2b7f222f65e2p-56, 0x1.1a62633145c07p-55, 0x1.007887af0cbbdp-56, 0x1.1a62633145c07p-54};
				const double aT0 = 0x1.555555555550dp-2;
				const double aT1 = -0x1.999999998ebc4p-3;
				const double aT2 = 0x1.24924920083ffp-3;
				const double aT3 = -0x1.c71c6fe231671p-4;
				const double aT4 = 0x1.745cdc54c206ep-4;
				const double aT5 = -0x1.3b0f2af749a6dp-4;
				const double aT6 = 0x1.10d66a0d03d51p-4;
				const double aT7 = -0x1.dde2d52defd9ap-5;
				const double aT8 = 0x1.97b4b24760debp-5;
				const double aT9 = -0x1.2b4442c6a6c2fp-5;
				const double aT10 = 0x1.0ad3ae322da11p-6;
				double atan(double x) {
				  double w, s1, s2, z;
				  int ix, hx, id;
				  hx = HI(x);
				  ix = hx & EXP_SIGNIF_BITS;
				  if (ix >= 0x44100000) {
				    if (ix > EXP_BITS || (ix == EXP_BITS && (LO(x) != 0))) return x + x;
				    if (hx > 0) return atanhi[3] + atanlo[3];
				    else return -atanhi[3] - atanlo[3];
				  }
				  if (ix < 0x3fdc0000) {
				    if (ix < 0x3e200000) {
				      if (HUGE + x > 1.0) return x;
				    }
				    id = -1;
				  } else {
				    x = abs(x);
				    if (ix < 0x3ff30000) {
				      if (ix < 0x3fe60000) {
				        id = 0;
				        x = (2.0 * x - 1.0) / (2.0 + x);
				      } else {
				        id = 1;
				        x = (x - 1.0) / (x + 1.0);
				      }
				    } else {
				      if (ix < 0x40038000) {
				        id = 2;
				        x = (x - 1.5) / (1.0 + 1.5 * x);
				      } else {
				        id = 3;
				        x = -1.0 / x;
				      }
				    }
				  }
				  z = x * x;
				  w = z * z;
				  s1 = z * (aT0 + w * (aT2 + w * (aT4 + w * (aT6 + w * (aT8 + w * aT10)))));
				  s2 = w * (aT1 + w * (aT3 + w * (aT5 + w * (aT7 + w * aT9))));
				  if (id < 0) return x - x * (s1 + s2);
				  else {
				    z = atanhi[id] - ((x * (s1 + s2) - atanlo[id]) - x);
				    return (hx < 0) ? -z : z;
				  }
				}
				""";

		static final String ATAN2 = """
				const double tiny = 1.0e-300;
				const double pi_o_4 = 0x1.921fb54442d18p-1;
				const double pi_o_2 = 0x1.921fb54442d18p0;
				const double pi_lo = 0x1.1a62633145c07p-53;
				double atan2(double y, double x) {
				  double z;
				  int k, m, hx, hy, ix, iy, lx, ly;
				  hx = HI(x);
				  ix = hx & EXP_SIGNIF_BITS;
				  lx = LO(x);
				  hy = HI(y);
				  iy = hy & EXP_SIGNIF_BITS;
				  ly = LO(y);
				  if (x != x || y != y) return x + y;
				  if (((hx - 0x3ff00000) | lx) == 0) return atan(y);
				  m = ((hy >> 31) & 1) | ((hx >> 30) & 2);
				  if ((iy | ly) == 0) {
				    if (m == 0 || m == 1) return y;
				    else if (m == 2) return PI + tiny;
				    else return -PI - tiny;
				  }
				  if ((ix | lx) == 0) return (hy < 0) ? -pi_o_2 - tiny : pi_o_2 + tiny;
				  if (ix == EXP_BITS) {
				    if (iy == EXP_BITS) {
				      if (m == 0) return pi_o_4 + tiny;
				      else if (m == 1) return -pi_o_4 - tiny;
				      else if (m == 2) return 3.0 * pi_o_4 + tiny;
				      else return -3.0 * pi_o_4 - tiny;
				    } else {
				      if (m == 0) return 0.0;
				      else if (m == 1) return -0.0;
				      else if (m == 2) return PI + tiny;
				      else return -PI - tiny;
				    }
				  }
				  if (iy == EXP_BITS) return (hy < 0) ? -pi_o_2 - tiny : pi_o_2 + tiny;
				  k = (iy - ix) >> 20;
				  if (k > 60) z = pi_o_2 + 0.5 * pi_lo;
				  else if (hx < 0 && k < -60) z = 0.0;
				  else z = atan(abs(y / x));
				  if (m == 0) return z;
				  else if (m == 1) return -z;
				  else if (m == 2) return PI - (z - pi_lo);
				  else return (z - pi_lo) - PI;
				}
				""";

		static final String SINH = """
				const double shuge = 1.0e307;
				double sinh(double x) {
				  double t, w, h;
				  int ix, jx, lx;
				  jx = HI(x);
				  ix = jx & EXP_SIGNIF_BITS;
				  if (ix >= EXP_BITS) return x + x;
				  h = 0.5;
				  if (jx < 0) h = -h;
				  if (ix < 0x40360000) {
				    if (ix < 0x3e300000) {
				      if (shuge + x > 1.0) return x;
				    }
				    t = expm1(abs(x));
				    if (ix < 0x3ff00000) return h * (2.0 * t - t * t / (t + 1.0));
				    return h * (t + t / (t + 1.0));
				  }
				  if (ix < 0x40862E42) return h * exp(abs(x));
				  lx = LO(x);
				  if (ix < 0x408633CE || ((ix == 0x408633ce) && ule(lx, 0x8fb9f87d))) {
				    w = exp(0.5 * abs(x));
				    t = h * w;
				    return t * w;
				  }
				  return x * shuge;
				}
				""";

		static final String COSH = """
				const double huge = 1.0e300;
				double cosh(double x) {
				  double t, w;
				  int ix, lx;
				  ix = HI(x) & EXP_SIGNIF_BITS;
				  if (ix >= EXP_BITS) return x * x;
				  if (ix < 0x3fd62e43) {
				    t = expm1(abs(x));
				    w = 1.0 + t;
				    if (ix < 0x3c800000) return w;
				    return 1.0 + (t * t) / (w + w);
				  }
				  if (ix < 0x40360000) {
				    t = exp(abs(x));
				    return 0.5 * t + 0.5 / t;
				  }
				  if (ix < 0x40862E42) return 0.5 * exp(abs(x));
				  lx = LO(x);
				  if (ix < 0x408633CE || ((ix == 0x408633ce) && ule(lx, 0x8fb9f87d))) {
				    w = exp(0.5 * abs(x));
				    t = 0.5 * w;
				    return t * w;
				  }
				  return huge * huge;
				}
				""";

		static final String TANH = """
				const double tiny = 1.0e-300;
				double tanh(double x) {
				  double t, z;
				  int jx, ix;
				  jx = HI(x);
				  ix = jx & EXP_SIGNIF_BITS;
				  if (ix >= EXP_BITS) {
				    if (jx >= 0) return 1.0 / x + 1.0;
				    else return 1.0 / x - 1.0;
				  }
				  if (ix < 0x40360000) {
				    if (ix < 0x3c800000) return x * (1.0 + x);
				    if (ix >= 0x3ff00000) {
				      t = expm1(2.0 * abs(x));
				      z = 1.0 - 2.0 / (t + 2.0);
				    } else {
				      t = expm1(-2.0 * abs(x));
				      z = -t / (t + 2.0);
				    }
				  } else {
				    z = 1.0 - tiny;
				  }
				  return (jx >= 0) ? z : -z;
				}
				""";

		static final String POW = """
				const double INV_LN2 = 0x1.71547652b82fep0;
				const double INV_LN2_H = 0x1.715476p0;
				const double INV_LN2_L = 0x1.4ae0bf85ddf44p-26;
				const double CP = 0x1.ec709dc3a03fdp-1;
				const double CP_H = 0x1.ec709ep-1;
				const double CP_L = -0x1.e2fe0145b01f5p-28;
				const double[] BP = {1.0, 1.5};
				const double[] DP_H = {0.0, 0x1.2b8034p-1};
				const double[] DP_L = {0.0, 0x1.cfdeb43cfd006p-27};
				const double L1 = 0x1.3333333333303p-1;
				const double L2 = 0x1.b6db6db6fabffp-2;
				const double L3 = 0x1.55555518f264dp-2;
				const double L4 = 0x1.17460a91d4101p-2;
				const double L5 = 0x1.d864a93c9db65p-3;
				const double L6 = 0x1.a7e284a454eefp-3;
				const double OVT = 8.0085662595372944372e-0017;
				const double P1 = 0x1.555555555553ep-3;
				const double P2 = -0x1.6c16c16bebd93p-9;
				const double P3 = 0x1.1566aaf25de2cp-14;
				const double P4 = -0x1.bbd41c5d26bf1p-20;
				const double P5 = 0x1.6376972bea4d0p-25;
				const double LG2 = 0x1.62e42fefa39efp-1;
				const double LG2_H = 0x1.62e43p-1;
				const double LG2_L = -0x1.05c610ca86c39p-29;
				double pow(double x, double y) {
				  double z, r, s, t, u, v, w, y_abs, x_abs, p_h, p_l, t1, t2, z_h, z_l, ss, s2, s_h, s_l, t_h, t_l, y1;
				  int i, j, k, n, hx, ix, y_is_int, z_hi;
				  long y_abs_as_long;
				  if (y == 0.0) return 1.0;
				  if (x != x || y != y) return x + y;
				  y_abs = abs(y);
				  x_abs = abs(x);
				  if (y == 2.0) return x * x;
				  else if (y == 0.5) {
				    if (x >= -DBL_MAX) return sqrt(x + 0.0);
				  } else if (y_abs == 1.0) return (y == 1.0) ? x : 1.0 / x;
				  else if (y_abs == INFINITY) {
				    if (x_abs == 1.0) return y - y;
				    else if (x_abs > 1.0) return (y >= 0.0) ? y : 0.0;
				    else return (y < 0.0) ? -y : 0.0;
				  }
				  hx = HI(x);
				  ix = hx & EXP_SIGNIF_BITS;
				  y_is_int = 0;
				  if (hx < 0) {
				    if (y_abs >= 0x1.0p53) y_is_int = 2;
				    else if (y_abs >= 1.0) {
				      y_abs_as_long = (long) y_abs;
				      if ((double) y_abs_as_long == y_abs) y_is_int = 2 - (int) (y_abs_as_long & 1L);
				    }
				  }
				  if (x_abs == 0.0 || x_abs == INFINITY || x_abs == 1.0) {
				    z = x_abs;
				    if (y < 0.0) z = 1.0 / z;
				    if (hx < 0) {
				      if (((ix - 0x3ff00000) | y_is_int) == 0) z = (z - z) / (z - z);
				      else if (y_is_int == 1) z = -1.0 * z;
				    }
				    return z;
				  }
				  n = (hx >> 31) + 1;
				  if ((n | y_is_int) == 0) return (x - x) / (x - x);
				  s = 1.0;
				  if ((n | (y_is_int - 1)) == 0) s = -1.0;
				  if (y_abs > 0x1.00000ffffffffp31) {
				    if (x_abs < 0x1.fffff00000000p-1) return (y < 0.0) ? s * INFINITY : s * 0.0;
				    if (x_abs > 0x1.00000ffffffffp0) return (y > 0.0) ? s * INFINITY : s * 0.0;
				    t = x_abs - 1.0;
				    w = (t * t) * (0.5 - t * (0.3333333333333333333333 - t * 0.25));
				    u = INV_LN2_H * t;
				    v = t * INV_LN2_L - w * INV_LN2;
				    t1 = u + v;
				    t1 = SET_LO(t1, 0);
				    t2 = v - (t1 - u);
				  } else {
				    n = 0;
				    if (ix < 0x00100000) {
				      x_abs = x_abs * 0x1.0p53;
				      n = n - 53;
				      ix = HI(x_abs);
				    }
				    n = n + ((ix >> 20) - 0x3ff);
				    j = ix & 0x000fffff;
				    ix = j | 0x3ff00000;
				    if (j <= 0x3988E) k = 0;
				    else if (j < 0xBB67A) k = 1;
				    else {
				      k = 0;
				      n = n + 1;
				      ix = ix - 0x00100000;
				    }
				    x_abs = SET_HI(x_abs, ix);
				    u = x_abs - BP[k];
				    v = 1.0 / (x_abs + BP[k]);
				    ss = u * v;
				    s_h = ss;
				    s_h = SET_LO(s_h, 0);
				    t_h = 0.0;
				    t_h = SET_HI(t_h, ((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18));
				    t_l = x_abs - (t_h - BP[k]);
				    s_l = v * ((u - s_h * t_h) - s_h * t_l);
				    s2 = ss * ss;
				    r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))));
				    r = r + s_l * (s_h + ss);
				    s2 = s_h * s_h;
				    t_h = 3.0 + s2 + r;
				    t_h = SET_LO(t_h, 0);
				    t_l = r - ((t_h - 3.0) - s2);
				    u = s_h * t_h;
				    v = s_l * t_h + t_l * ss;
				    p_h = u + v;
				    p_h = SET_LO(p_h, 0);
				    p_l = v - (p_h - u);
				    z_h = CP_H * p_h;
				    z_l = CP_L * p_h + p_l * CP + DP_L[k];
				    t = (double) n;
				    t1 = (((z_h + z_l) + DP_H[k]) + t);
				    t1 = SET_LO(t1, 0);
				    t2 = z_l - (((t1 - t) - DP_H[k]) - z_h);
				  }
				  y1 = y;
				  y1 = SET_LO(y1, 0);
				  p_l = (y - y1) * t1 + y * t2;
				  p_h = y1 * t1;
				  z = p_l + p_h;
				  j = HI(z);
				  i = LO(z);
				  if (j >= 0x40900000) {
				    if (((j - 0x40900000) | i) != 0) return s * INFINITY;
				    else {
				      if (p_l + OVT > z - p_h) return s * INFINITY;
				    }
				  } else if ((j & EXP_SIGNIF_BITS) >= 0x4090cc00) {
				    if (((j - 0xc090cc00) | i) != 0) return s * 0.0;
				    else {
				      if (p_l <= z - p_h) return s * 0.0;
				    }
				  }
				  i = j & EXP_SIGNIF_BITS;
				  k = (i >> 20) - 0x3ff;
				  n = 0;
				  if (i > 0x3fe00000) {
				    n = j + (0x00100000 >> (k + 1));
				    k = ((n & EXP_SIGNIF_BITS) >> 20) - 0x3ff;
				    t = 0.0;
				    t = SET_HI(t, (n & ~(0x000fffff >> k)));
				    n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
				    if (j < 0) n = -n;
				    p_h = p_h - t;
				  }
				  t = p_l + p_h;
				  t = SET_LO(t, 0);
				  u = t * LG2_H;
				  v = (p_l - (t - p_h)) * LG2 + t * LG2_L;
				  z = u + v;
				  w = v - (z - u);
				  t = z * z;
				  t1 = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
				  r = (z * t1) / (t1 - 2.0) - (w + z * w);
				  z = 1.0 - (r - z);
				  j = HI(z);
				  j = j + (n << 20);
				  if ((j >> 20) <= 0) {
				    z = (z * 0x1.0p-1000) * pow2(n + 1000);
				  } else {
				    z_hi = HI(z);
				    z_hi = z_hi + (n << 20);
				    z = SET_HI(z, z_hi);
				  }
				  return s * z;
				}
				""";

		static final String HYPOT = """
				const double TWO_MINUS_600 = 0x1.0p-600;
				const double TWO_PLUS_600 = 0x1.0p600;
				double hypot(double x, double y) {
				  double a, b, t1, t2, w, y1, y2, tmp;
				  int ha, hb, k;
				  a = abs(x);
				  b = abs(y);
				  if (!isfinite(a) || !isfinite(b)) {
				    if (a == INFINITY || b == INFINITY) return INFINITY;
				    else return a + b;
				  }
				  if (b > a) {
				    tmp = a;
				    a = b;
				    b = tmp;
				  }
				  ha = HI(a);
				  hb = HI(b);
				  if ((ha - hb) > 0x3c00000) return a + b;
				  k = 0;
				  if (a > 0x1.00000ffffffffp500) {
				    ha = ha - 0x25800000;
				    hb = hb - 0x25800000;
				    a = a * TWO_MINUS_600;
				    b = b * TWO_MINUS_600;
				    k = k + 600;
				  }
				  if (b < 0x1.0p-500) {
				    if (b < DBL_MIN_NORMAL) {
				      if (b == 0.0) return a;
				      t1 = 0x1.0p1022;
				      b = b * t1;
				      a = a * t1;
				      k = k - 1022;
				    } else {
				      ha = ha + 0x25800000;
				      hb = hb + 0x25800000;
				      a = a * TWO_PLUS_600;
				      b = b * TWO_PLUS_600;
				      k = k - 600;
				    }
				  }
				  w = a - b;
				  if (w > b) {
				    t1 = 0.0;
				    t1 = SET_HI(t1, ha);
				    t2 = a - t1;
				    w = sqrt(t1 * t1 - (b * (-b) - t2 * (a + t1)));
				  } else {
				    a = a + a;
				    y1 = 0.0;
				    y1 = SET_HI(y1, hb);
				    y2 = b - y1;
				    t1 = 0.0;
				    t1 = SET_HI(t1, ha + 0x00100000);
				    t2 = a - t1;
				    w = sqrt(t1 * y1 - (w * (-w) - (t1 * y2 + t2 * b)));
				  }
				  if (k != 0) return pow2(k) * w;
				  else return w;
				}
				""";

	}

}
