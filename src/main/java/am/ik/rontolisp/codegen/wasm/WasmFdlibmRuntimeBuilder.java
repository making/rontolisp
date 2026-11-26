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
 * Each function is written once, in a C-like subset ({@link WasmFdlibmSources}), and
 * compiled to a function body by the small compiler below: the same operations in the
 * same order, on raw {@code f64}/{@code i32} locals, with the word accesses
 * ({@code __HI}/{@code __LO}) as {@code i64.reinterpret} arithmetic. The sources are
 * Sun's C fdlibm 5.3, whose notice that file keeps, and are written from those files
 * alone ({@code .kb/java-frontend-provenance.md}). Bit-identity to {@code StrictMath} --
 * a JDK run as the oracle -- is pinned by {@code WasmFdlibmRuntimeBuilderTest} against
 * every one of the functions over random and special arguments.
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
		HYPOT("hypot", "_fd_hypot", new Ty[] { Ty.D, Ty.D }, Ty.D),
		// The rest of StrictMath's double methods, for %strict-math: two more fdlibm
		// algorithms, the exact remainder and its fmod, and the bit-level operations.
		// Appended, so every slot above keeps its index.
		LOG10("log10", "_fd_log10", new Ty[] { Ty.D }, Ty.D), CBRT("cbrt", "_fd_cbrt", new Ty[] { Ty.D }, Ty.D),
		IEEE_REMAINDER("remainder", "_fd_remainder", new Ty[] { Ty.D, Ty.D }, Ty.D),
		FMOD("fmod", "_fd_fmod", new Ty[] { Ty.D, Ty.D }, Ty.D), ULP("ulp", "_fd_ulp", new Ty[] { Ty.D }, Ty.D),
		SIGNUM("signum", "_fd_signum", new Ty[] { Ty.D }, Ty.D),
		NEXT_UP("next_up", "_fd_next_up", new Ty[] { Ty.D }, Ty.D),
		NEXT_DOWN("next_down", "_fd_next_down", new Ty[] { Ty.D }, Ty.D),
		NEXT_AFTER("next_after", "_fd_next_after", new Ty[] { Ty.D, Ty.D }, Ty.D),
		COPY_SIGN("copy_sign", "_fd_copy_sign", new Ty[] { Ty.D, Ty.D }, Ty.D),
		GET_EXPONENT("get_exponent", "_fd_get_exponent", new Ty[] { Ty.D }, Ty.I),
		// scalb's exponent travels as a double (an integer, exact), so the function
		// shares (f64, f64) -> f64 and needs no signature of its own. It is fdlibm's
		// scalbn, which pow's subnormal results go through as well.
		SCALB("scalbn", "_fd_scalb", new Ty[] { Ty.D, Ty.D }, Ty.D);

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
				case POW -> EnumSet.of(SCALB);
				case LOG10 -> EnumSet.of(LOG);
				case IEEE_REMAINDER -> EnumSet.of(FMOD);
				case NEXT_AFTER -> EnumSet.of(NEXT_UP, NEXT_DOWN);
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
		for (Fn fn : fns) {
			if (addressesTables(fn)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the function's own body cites the {@link #tables()} base -- the shaker
	 * keeps the blob exactly while one of these survives, so the set must name every body
	 * that does.
	 * @param fn the function
	 * @return true for the trig reduction and its three entry points
	 */
	static boolean addressesTables(Fn fn) {
		return fn == Fn.REM_PIO2 || fn == Fn.K_REM_PIO2 || fn == Fn.SIN || fn == Fn.COS || fn == Fn.TAN;
	}

	// --- the linear-memory blob: two tables, then the reduction's scratch arrays ------

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
		writeTable(out, memArray("ipio2").offset(), WasmFdlibmSources.TWO_OVER_PI);
		writeTable(out, memArray("npio2_hw").offset(), WasmFdlibmSources.NPIO2_HW);
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
			return CACHE.computeIfAbsent(fn, f -> new Parser(f, WasmFdlibmSources.of(f.cname)).parseFunction());
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
					"+=", "-=", "*=", "/=", "&=", "|=", "^=" }) {
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

		// A numeric literal: decimal integer, hexadecimal integer (wrapping to a signed
		// int, so 0x80000000 is the sign bit), decimal floating point, hexadecimal
		// floating point (0x1.8p3), with an optional L suffix on the integers.
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
			// A hex int literal past Integer.MAX_VALUE wraps.
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
			// An assignment: to a local, to one word of a local (fdlibm's __HI(x) = ...),
			// or to a memory array element.
			String name = ident();
			if ((name.equals("__HI") || name.equals("__LO")) && is("(")) {
				advance();
				Var word = this.vars.get(ident());
				if (word == null || word.ty != Ty.D) {
					throw error(name + " assigns a word of a double local");
				}
				expect(")");
				String op = assignOp();
				Expr rhs = requireI(expr());
				expect(";");
				Expr value = op.equals("=") ? rhs : binary(op.substring(0, op.length() - 1),
						new Builtin(name, List.of(new Load(word)), Ty.I), rhs);
				out.add(new Assign(word,
						new Builtin(name.equals("__HI") ? "SET_HI" : "SET_LO", List.of(new Load(word), value), Ty.D)));
				return;
			}
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
			for (String op : new String[] { "=", "+=", "-=", "*=", "/=", "&=", "|=", "^=", "<<=", ">>=", ">>>=" }) {
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
		// explicit cast, so a conversion C makes silently has to be written, and a missed
		// one fails to parse rather than rounding somewhere C would not.
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
				case "__HI", "__LO" -> {
					sig = new Ty[] { Ty.D };
					result = Ty.I;
				}
				case "BITS" -> {
					sig = new Ty[] { Ty.D };
					result = Ty.L;
				}
				case "FROM_BITS" -> {
					sig = new Ty[] { Ty.L };
					result = Ty.D;
				}
				case "fabs", "sqrt", "floor" -> {
					sig = new Ty[] { Ty.D };
					result = Ty.D;
				}
				case "copysign" -> {
					sig = new Ty[] { Ty.D, Ty.D };
					result = Ty.D;
				}
				case "pow2" -> {
					sig = new Ty[] { Ty.I };
					result = Ty.D;
				}
				case "ult", "ule" -> {
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
		// i32.const, so a body cites it exactly when addressesTables says it does.
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
				// The shift count of an i64 shift is an i64 in wasm; the subset's is an
				// int.
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
						// (int) d saturates and NaN answers 0; fdlibm casts in-range
						// values only.
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
				case "__HI" -> {
					// (int) (bits >> 32)
					expr(a.get(0));
					this.w.write(Instruction.I64_REINTERPRET_F64);
					this.w.write(Instruction.I64_CONST);
					this.w.writeSignedLeb128(32L);
					this.w.write(Instruction.I64_SHR_S);
					this.w.write(Instruction.I32_WRAP_I64);
				}
				case "__LO" -> {
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
				case "BITS" -> {
					// Double.doubleToRawLongBits
					expr(a.get(0));
					this.w.write(Instruction.I64_REINTERPRET_F64);
				}
				case "FROM_BITS" -> {
					// Double.longBitsToDouble
					expr(a.get(0));
					this.w.write(Instruction.F64_REINTERPRET_I64);
				}
				case "fabs" -> {
					expr(a.get(0));
					this.w.write(Instruction.F64_ABS);
				}
				case "copysign" -> {
					expr(a.get(0));
					expr(a.get(1));
					this.w.write(Instruction.F64_COPYSIGN);
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
				case "ult" -> {
					expr(a.get(0));
					expr(a.get(1));
					this.w.write(Instruction.I32_LT_U);
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

}
