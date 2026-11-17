import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SPIKE: a minimal Java subset lowered to Common Lisp core forms (emitted as text).
 * Usage: java JavaSpike.java Prog.java > prog.lisp
 */
public class JavaSpike {

	// ------------------------------------------------------------------ lexer

	enum K {

		ID, INT, LONG, DBL, STR, CHR, OP, EOF

	}

	record Tok(K k, String s, int line) {
	}

	static final String[] OPS = { ">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "++", "--", "&&", "||", "==", "!=",
			"<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>" };

	static List<Tok> lex(String src) {
		List<Tok> out = new ArrayList<>();
		int i = 0, n = src.length(), line = 1;
		while (i < n) {
			char c = src.charAt(i);
			if (c == '\n') {
				line++;
				i++;
			}
			else if (Character.isWhitespace(c)) {
				i++;
			}
			else if (src.startsWith("//", i)) {
				while (i < n && src.charAt(i) != '\n') {
					i++;
				}
			}
			else if (src.startsWith("/*", i)) {
				int e = src.indexOf("*/", i + 2);
				for (int j = i; j < e; j++) {
					if (src.charAt(j) == '\n') {
						line++;
					}
				}
				i = e + 2;
			}
			else if (Character.isJavaIdentifierStart(c)) {
				int s = i;
				while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) {
					i++;
				}
				out.add(new Tok(K.ID, src.substring(s, i), line));
			}
			else if (Character.isDigit(c)) {
				int s = i;
				boolean hex = src.startsWith("0x", i) || src.startsWith("0X", i);
				boolean dbl = false;
				if (hex) {
					i += 2;
				}
				while (i < n) {
					char d = src.charAt(i);
					if (Character.isDigit(d) || d == '_' || (hex && Character.digit(d, 16) >= 0)) {
						i++;
					}
					else if (!hex && d == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1))) {
						dbl = true;
						i++;
					}
					else if (!hex && (d == 'e' || d == 'E')) {
						dbl = true;
						i++;
						if (src.charAt(i) == '+' || src.charAt(i) == '-') {
							i++;
						}
					}
					else {
						break;
					}
				}
				String text = src.substring(s, i).replace("_", "");
				char suffix = i < n ? src.charAt(i) : ' ';
				if (suffix == 'L' || suffix == 'l') {
					i++;
					out.add(new Tok(K.LONG, text, line));
				}
				else if (suffix == 'd' || suffix == 'D' || suffix == 'f' || suffix == 'F') {
					i++;
					out.add(new Tok(K.DBL, text, line));
				}
				else {
					out.add(new Tok(dbl ? K.DBL : K.INT, text, line));
				}
			}
			else if (c == '"' || c == '\'') {
				StringBuilder sb = new StringBuilder();
				i++;
				while (src.charAt(i) != c) {
					char d = src.charAt(i++);
					if (d == '\\') {
						char e = src.charAt(i++);
						switch (e) {
							case 'n' -> sb.append('\n');
							case 't' -> sb.append('\t');
							case 'r' -> sb.append('\r');
							case '0' -> sb.append('\0');
							case 'u' -> {
								sb.append((char) Integer.parseInt(src.substring(i, i + 4), 16));
								i += 4;
							}
							default -> sb.append(e);
						}
					}
					else {
						sb.append(d);
					}
				}
				i++;
				out.add(new Tok(c == '"' ? K.STR : K.CHR, sb.toString(), line));
			}
			else {
				String op = null;
				for (String o : OPS) {
					if (src.startsWith(o, i)) {
						op = o;
						break;
					}
				}
				if (op == null) {
					op = String.valueOf(c);
				}
				i += op.length();
				out.add(new Tok(K.OP, op, line));
			}
		}
		out.add(new Tok(K.EOF, "<eof>", line));
		return out;
	}

	// ------------------------------------------------------------------ tree

	record T(String n, List<T> a, int d) {
		static T of(String n) {
			return new T(n, List.of(), 0);
		}

		boolean is(String s) {
			return this.d == 0 && this.n.equals(s);
		}

		boolean integral() {
			return is("int") || is("long") || is("char");
		}

		boolean numeric() {
			return integral() || is("double");
		}

		T elem() {
			return this.d > 0 ? new T(this.n, this.a, this.d - 1) : this.a.isEmpty() ? OBJECT : this.a.get(0);
		}

		String erasure() {
			return this.n + "[]".repeat(this.d);
		}
	}

	static final T INT = T.of("int"), LONG = T.of("long"), DOUBLE = T.of("double"), BOOL = T.of("boolean"),
			CHAR = T.of("char"), STRING = T.of("String"), VOID = T.of("void"), NULL = T.of("null"),
			OBJECT = T.of("Object");

	interface E {
	}

	record Lit(T t, String code) implements E {
	}

	record Name(String id) implements E {
	}

	record Sel(E t, String name) implements E {
	}

	record Call(E t, String name, List<E> args, boolean sup) implements E {
	}

	record New(T t, List<E> args) implements E {
	}

	record NewArr(T elem, E len, List<E> init) implements E {
	}

	record Idx(E a, E i) implements E {
	}

	record Un(String op, E e, boolean post) implements E {
	}

	record Bin(String op, E l, E r) implements E {
	}

	record Asg(String op, E l, E r) implements E {
	}

	record Cnd(E c, E a, E b) implements E {
	}

	record Cast(T t, E e) implements E {
	}

	record InstOf(E e, T t) implements E {
	}

	record This() implements E {
	}

	record Lam(List<String> ps, E expr, Blk body) implements E {
	}

	interface S {
	}

	record Local(T t, String name, E init) implements S {
	}

	record ExprS(E e) implements S {
	}

	record If(E c, S a, S b) implements S {
	}

	record While(E c, S body) implements S {
	}

	record DoWhile(S body, E c) implements S {
	}

	record For(List<S> init, E c, List<E> upd, S body) implements S {
	}

	record ForEach(T t, String name, E it, S body) implements S {
	}

	record Ret(E e) implements S {
	}

	record Brk() implements S {
	}

	record Cont() implements S {
	}

	record Blk(List<S> ss) implements S {
	}

	record Throw(E e) implements S {
	}

	record Catch(T t, String name, Blk body) {
	}

	record Try(Blk body, List<Catch> cs, Blk fin) implements S {
	}

	record Fld(T t, String name, E init, boolean stat, Cls owner) {
	}

	record Prm(T t, String name) {
	}

	static class Mth {

		String name;

		List<Prm> ps = new ArrayList<>();

		T ret;

		Blk body;

		boolean stat, ctor;

		Cls owner;

		String sig() {
			StringBuilder sb = new StringBuilder(this.name).append('(');
			for (int i = 0; i < this.ps.size(); i++) {
				sb.append(i > 0 ? "," : "").append(this.ps.get(i).t.erasure());
			}
			return sb.append(')').toString();
		}

		String sym() {
			return "|" + this.owner.name + "." + sig() + "|";
		}

	}

	static class Cls {

		String name, sup;

		List<String> ifaces = new ArrayList<>();

		boolean iface, abs;

		List<Fld> flds = new ArrayList<>();

		List<Mth> mths = new ArrayList<>();

	}

	// ------------------------------------------------------------------ parser

	static class Parser {

		final List<Tok> toks;

		int p = 0;

		int halfGt = -1;

		final List<Cls> classes = new ArrayList<>();

		Parser(String src) {
			this.toks = lex(src);
		}

		Tok cur() {
			return this.toks.get(this.p);
		}

		Tok peek(int k) {
			return this.toks.get(Math.min(this.p + k, this.toks.size() - 1));
		}

		boolean at(String s) {
			Tok t = cur();
			return (t.k == K.OP || t.k == K.ID) && t.s.equals(s);
		}

		boolean peekIs(int k, String s) {
			Tok t = peek(k);
			return (t.k == K.OP || t.k == K.ID) && t.s.equals(s);
		}

		boolean eat(String s) {
			if (at(s)) {
				this.p++;
				return true;
			}
			return false;
		}

		void expect(String s) {
			if (!eat(s)) {
				throw err("expected '" + s + "' but found '" + cur().s + "'");
			}
		}

		String ident() {
			if (cur().k != K.ID) {
				throw err("expected an identifier but found '" + cur().s + "'");
			}
			return this.toks.get(this.p++).s;
		}

		RuntimeException err(String m) {
			return new RuntimeException("line " + cur().line + ": " + m);
		}

		static final Set<String> MODS = Set.of("public", "private", "protected", "static", "final", "abstract",
				"default");

		void parseUnit() {
			Cls implicit = null;
			while (cur().k != K.EOF) {
				if (at("package") || at("import")) {
					while (!eat(";")) {
						this.p++;
					}
					continue;
				}
				int save = this.p;
				Set<String> mods = mods();
				if (at("class") || at("interface")) {
					classDecl(mods);
				}
				else {
					this.p = save;
					if (implicit == null) {
						implicit = new Cls();
						implicit.name = "Main";
						this.classes.add(implicit);
					}
					member(implicit, true);
				}
			}
		}

		Set<String> mods() {
			Set<String> mods = new LinkedHashSet<>();
			while (true) {
				if (at("@")) {
					this.p += 2;
				}
				else if (cur().k == K.ID && MODS.contains(cur().s)) {
					mods.add(this.toks.get(this.p++).s);
				}
				else {
					return mods;
				}
			}
		}

		void classDecl(Set<String> mods) {
			Cls c = new Cls();
			c.iface = at("interface");
			this.p++;
			c.abs = mods.contains("abstract") || c.iface;
			c.name = ident();
			if (eat("extends")) {
				if (c.iface) {
					c.ifaces.add(type().n);
				}
				else {
					c.sup = type().n;
				}
			}
			if (eat("implements")) {
				do {
					c.ifaces.add(type().n);
				}
				while (eat(","));
			}
			this.classes.add(c);
			expect("{");
			while (!eat("}")) {
				member(c, false);
			}
		}

		void member(Cls c, boolean allStatic) {
			Set<String> mods = mods();
			if (at("class") || at("interface")) {
				classDecl(mods);
				return;
			}
			boolean stat = allStatic || mods.contains("static");
			if (cur().k == K.ID && cur().s.equals(c.name) && peekIs(1, "(")) {
				this.p++;
				Mth m = new Mth();
				m.name = "<init>";
				m.ctor = true;
				m.ret = VOID;
				m.owner = c;
				params(m);
				m.body = block();
				c.mths.add(m);
				return;
			}
			T t = type();
			String name = ident();
			if (at("(")) {
				Mth m = new Mth();
				m.name = name;
				m.ret = t;
				m.stat = stat;
				m.owner = c;
				params(m);
				if (eat("throws")) {
					do {
						type();
					}
					while (eat(","));
				}
				if (!eat(";")) {
					m.body = block();
				}
				c.mths.add(m);
				return;
			}
			while (true) {
				E init = eat("=") ? (at("{") ? arrayInit(t) : expr()) : null;
				c.flds.add(new Fld(t, name, init, stat, c));
				if (eat(";")) {
					return;
				}
				expect(",");
				name = ident();
			}
		}

		void params(Mth m) {
			expect("(");
			while (!eat(")")) {
				eat("final");
				T t = type();
				m.ps.add(new Prm(t, ident()));
				eat(",");
			}
		}

		static String normalize(String n) {
			return switch (n) {
				case "Integer", "short", "byte" -> "int";
				case "Long" -> "long";
				case "Double", "float", "Float" -> "double";
				case "Boolean" -> "boolean";
				case "Character" -> "char";
				case "List" -> "ArrayList";
				case "Map" -> "HashMap";
				default -> n;
			};
		}

		T type() {
			String n = ident();
			while (at(".") && peek(1).k == K.ID) {
				this.p++;
				n = ident();
			}
			List<T> args = new ArrayList<>();
			if (at("<")) {
				this.p++;
				while (!closeAngle()) {
					args.add(type());
					eat(",");
				}
			}
			int d = 0;
			while (at("[") && peekIs(1, "]")) {
				this.p += 2;
				d++;
			}
			return new T(normalize(n), args, d);
		}

		boolean closeAngle() {
			if (at(">")) {
				this.p++;
				return true;
			}
			if (at(">>")) {
				if (this.halfGt == this.p) {
					this.halfGt = -1;
					this.p++;
				}
				else {
					this.halfGt = this.p;
				}
				return true;
			}
			return false;
		}

		Blk block() {
			expect("{");
			List<S> ss = new ArrayList<>();
			while (!eat("}")) {
				stmt(ss);
			}
			return new Blk(ss);
		}

		S single() {
			List<S> ss = new ArrayList<>();
			stmt(ss);
			return ss.size() == 1 ? ss.get(0) : new Blk(ss);
		}

		boolean looksLikeDecl() {
			int save = this.p;
			try {
				eat("final");
				type();
				return cur().k == K.ID
						&& (peekIs(1, "=") || peekIs(1, ";") || peekIs(1, ":") || peekIs(1, ","));
			}
			catch (RuntimeException ex) {
				return false;
			}
			finally {
				this.p = save;
				this.halfGt = -1;
			}
		}

		void localDecl(List<S> out) {
			eat("final");
			T t = type();
			if (t.is("var")) {
				t = null;
			}
			do {
				String name = ident();
				E init = eat("=") ? (at("{") ? arrayInit(t) : expr()) : null;
				out.add(new Local(t, name, init));
			}
			while (eat(","));
		}

		E arrayInit(T arrayType) {
			expect("{");
			List<E> es = new ArrayList<>();
			while (!eat("}")) {
				es.add(expr());
				eat(",");
			}
			return new NewArr(arrayType.elem(), null, es);
		}

		void stmt(List<S> out) {
			if (at("{")) {
				out.add(block());
			}
			else if (eat(";")) {
				// empty
			}
			else if (eat("if")) {
				expect("(");
				E c = expr();
				expect(")");
				S a = single();
				S b = eat("else") ? single() : null;
				out.add(new If(c, a, b));
			}
			else if (eat("while")) {
				expect("(");
				E c = expr();
				expect(")");
				out.add(new While(c, single()));
			}
			else if (eat("do")) {
				S body = single();
				expect("while");
				expect("(");
				E c = expr();
				expect(")");
				expect(";");
				out.add(new DoWhile(body, c));
			}
			else if (eat("for")) {
				expect("(");
				List<S> init = new ArrayList<>();
				if (!at(";")) {
					if (looksLikeDecl()) {
						localDecl(init);
						if (eat(":")) {
							Local l = (Local) init.get(0);
							E it = expr();
							expect(")");
							out.add(new ForEach(l.t, l.name, it, single()));
							return;
						}
					}
					else {
						do {
							init.add(new ExprS(expr()));
						}
						while (eat(","));
					}
				}
				expect(";");
				E c = at(";") ? null : expr();
				expect(";");
				List<E> upd = new ArrayList<>();
				while (!eat(")")) {
					upd.add(expr());
					eat(",");
				}
				out.add(new For(init, c, upd, single()));
			}
			else if (eat("return")) {
				E e = at(";") ? null : expr();
				expect(";");
				out.add(new Ret(e));
			}
			else if (eat("break")) {
				expect(";");
				out.add(new Brk());
			}
			else if (eat("continue")) {
				expect(";");
				out.add(new Cont());
			}
			else if (eat("throw")) {
				E e = expr();
				expect(";");
				out.add(new Throw(e));
			}
			else if (eat("try")) {
				Blk body = block();
				List<Catch> cs = new ArrayList<>();
				while (eat("catch")) {
					expect("(");
					T t = type();
					String name = ident();
					expect(")");
					cs.add(new Catch(t, name, block()));
				}
				Blk fin = eat("finally") ? block() : null;
				out.add(new Try(body, cs, fin));
			}
			else if (cur().k == K.ID && looksLikeDecl()) {
				localDecl(out);
				expect(";");
			}
			else {
				out.add(new ExprS(expr()));
				expect(";");
			}
		}

		static final Set<String> ASSIGN = Set.of("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=",
				">>>=");

		static final String[][] LEVELS = { { "||" }, { "&&" }, { "|" }, { "^" }, { "&" }, { "==", "!=" },
				{ "<", ">", "<=", ">=", "instanceof" }, { "<<", ">>", ">>>" }, { "+", "-" }, { "*", "/", "%" } };

		E expr() {
			E l = ternary();
			if (cur().k == K.OP && ASSIGN.contains(cur().s)) {
				String op = this.toks.get(this.p++).s;
				return new Asg(op, l, expr());
			}
			return l;
		}

		E ternary() {
			E c = binary(0);
			if (eat("?")) {
				E a = expr();
				expect(":");
				return new Cnd(c, a, ternary());
			}
			return c;
		}

		E binary(int level) {
			if (level == LEVELS.length) {
				return unary();
			}
			E l = binary(level + 1);
			while (true) {
				String op = null;
				for (String o : LEVELS[level]) {
					if (at(o)) {
						op = o;
					}
				}
				if (op == null) {
					return l;
				}
				this.p++;
				l = op.equals("instanceof") ? new InstOf(l, type()) : new Bin(op, l, binary(level + 1));
			}
		}

		E unary() {
			for (String op : new String[] { "-", "+", "!", "~", "++", "--" }) {
				if (cur().k == K.OP && at(op)) {
					this.p++;
					return new Un(op, unary(), false);
				}
			}
			if (at("(") && isCast()) {
				this.p++;
				T t = type();
				expect(")");
				return new Cast(t, unary());
			}
			return postfix(primary());
		}

		boolean isCast() {
			int save = this.p;
			try {
				this.p++;
				T t = type();
				if (!eat(")")) {
					return false;
				}
				if (Set.of("int", "long", "double", "char", "boolean").contains(t.n)) {
					return true;
				}
				Tok n = cur();
				return n.k == K.STR || n.k == K.CHR || n.k == K.INT || n.k == K.DBL || n.k == K.LONG
						|| (n.k == K.ID && !n.s.equals("instanceof")) || n.s.equals("(") || n.s.equals("!")
						|| n.s.equals("~");
			}
			catch (RuntimeException ex) {
				return false;
			}
			finally {
				this.p = save;
				this.halfGt = -1;
			}
		}

		boolean isLambda() {
			if (cur().k == K.ID) {
				return peekIs(1, "->");
			}
			if (!at("(")) {
				return false;
			}
			int depth = 0, i = this.p;
			do {
				String s = this.toks.get(i).s;
				K k = this.toks.get(i).k;
				if (k == K.OP && s.equals("(")) {
					depth++;
				}
				else if (k == K.OP && s.equals(")")) {
					depth--;
				}
				else if (k == K.EOF) {
					return false;
				}
				i++;
			}
			while (depth > 0);
			return this.toks.get(i).s.equals("->");
		}

		E lambda() {
			List<String> ps = new ArrayList<>();
			if (eat("(")) {
				while (!eat(")")) {
					String id = ident();
					while (cur().k == K.ID) {
						id = ident();
					}
					ps.add(id);
					eat(",");
				}
			}
			else {
				ps.add(ident());
			}
			expect("->");
			return at("{") ? new Lam(ps, null, block()) : new Lam(ps, expr(), null);
		}

		List<E> args() {
			expect("(");
			List<E> as = new ArrayList<>();
			while (!eat(")")) {
				as.add(expr());
				eat(",");
			}
			return as;
		}

		E primary() {
			Tok t = cur();
			if (isLambda()) {
				return lambda();
			}
			this.p++;
			switch (t.k) {
				case INT -> {
					long v = t.s.startsWith("0x") || t.s.startsWith("0X") ? Long.parseLong(t.s.substring(2), 16)
							: Long.parseLong(t.s);
					return new Lit(INT, String.valueOf((int) v));
				}
				case LONG -> {
					return new Lit(LONG, String.valueOf(Long.parseLong(t.s)));
				}
				case DBL -> {
					String s = Double.toString(Double.parseDouble(t.s));
					return new Lit(DOUBLE, s.contains("E") ? s.replace("E", "d") : s + "d0");
				}
				case STR -> {
					return new Lit(STRING, "\"" + t.s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
				}
				case CHR -> {
					return new Lit(CHAR, String.valueOf((int) t.s.charAt(0)));
				}
				default -> {
				}
			}
			if (t.k == K.OP && t.s.equals("(")) {
				E e = expr();
				expect(")");
				return e;
			}
			if (t.k != K.ID) {
				this.p--;
				throw err("unexpected '" + t.s + "'");
			}
			switch (t.s) {
				case "true":
					return new Lit(BOOL, "t");
				case "false":
					return new Lit(BOOL, "nil");
				case "null":
					return new Lit(NULL, "nil");
				case "this":
					return at("(") ? new Call(null, "this", args(), true) : new This();
				case "super":
					if (at("(")) {
						return new Call(null, "super", args(), true);
					}
					expect(".");
					return new Call(null, ident(), args(), true);
				case "new": {
					String n = ident();
					List<T> targs = new ArrayList<>();
					if (eat("<")) {
						while (!closeAngle()) {
							targs.add(type());
							eat(",");
						}
					}
					T elem = new T(normalize(n), targs, 0);
					if (at("[")) {
						this.p++;
						if (eat("]")) {
							int d = 1;
							while (at("[")) {
								this.p += 2;
								d++;
							}
							return arrayInit(new T(elem.n, elem.a, d));
						}
						E len = expr();
						expect("]");
						return new NewArr(elem, len, null);
					}
					return new New(elem, args());
				}
				default:
					return at("(") ? new Call(null, t.s, args(), false) : new Name(t.s);
			}
		}

		E postfix(E e) {
			while (true) {
				if (eat(".")) {
					String name = ident();
					e = at("(") ? new Call(e, name, args(), false) : new Sel(e, name);
				}
				else if (eat("[")) {
					e = new Idx(e, expr());
					expect("]");
				}
				else if (at("++") || at("--")) {
					e = new Un(this.toks.get(this.p++).s, e, true);
				}
				else {
					return e;
				}
			}
		}

	}

	// ------------------------------------------------------------------ lowering

	record X(String c, T t) {
	}

	static final String JDK = """
			class Throwable {
				String message;
				Throwable() {}
				Throwable(String message) { this.message = message; }
				String getMessage() { return message; }
			}
			class Exception extends Throwable { Exception() {} Exception(String m) { super(m); } }
			class RuntimeException extends Exception { RuntimeException() {} RuntimeException(String m) { super(m); } }
			class IllegalArgumentException extends RuntimeException {
				IllegalArgumentException() {} IllegalArgumentException(String m) { super(m); } }
			class IllegalStateException extends RuntimeException {
				IllegalStateException() {} IllegalStateException(String m) { super(m); } }
			class ArithmeticException extends RuntimeException {
				ArithmeticException() {} ArithmeticException(String m) { super(m); } }
			""";

	static final String RUNTIME = """
			(defmacro |%i32| (x) `(- (logand (+ ,x 2147483648) 4294967295) 2147483648))
			(defmacro |%i64| (x) `(- (logand (+ ,x 9223372036854775808) 18446744073709551615) 9223372036854775808))
			(define-condition |%jthrow| (error) ((obj :initarg :obj :reader |%jthrow-obj|)))
			(defun |%jnn| (s) (if s s "null"))
			(defun |%jitoa| (x) (format nil "~D" x))
			(defun |%jdtoa| (d)
			  (let ((a (abs d)))
			    (cond ((= d 0) "0.0")
			          ((and (>= a 1d-3) (< a 1d7))
			           (let ((s (format nil "~F" d)))
			             (when (char= (char s (- (length s) 1)) #\\.) (setq s (concatenate 'string s "0")))
			             (when (char= (char s 0) #\\.) (setq s (concatenate 'string "0" s)))
			             s))
			          (t (let* ((e (floor (log a 10))) (m (/ d (expt 10d0 e))))
			               (format nil "~AE~D" (|%jdtoa| m) e))))))
			(defun |%jany| (x)
			  (cond ((stringp x) x) ((integerp x) (|%jitoa| x)) ((floatp x) (|%jdtoa| x))
			        ((null x) "null") ((eq x t) "true") (t (princ-to-string x))))
			(defun |%jsb-append| (sb s) (loop for ch across s do (vector-push-extend ch sb)) sb)
			(defun |%jlisp-error| (c)
			  (if (typep c 'division-by-zero)
			      (|ArithmeticException.new(String)| "/ by zero")
			      (|RuntimeException.new(String)| (princ-to-string c))))
			""";

	static class Lower {

		final Map<String, Cls> classes = new LinkedHashMap<>();

		final StringBuilder out = new StringBuilder();

		final Map<String, String> dispatchers = new LinkedHashMap<>();

		final Deque<Map<String, T>> scopes = new ArrayDeque<>();

		final Deque<boolean[]> fnBlock = new ArrayDeque<>();

		Cls cur;

		Mth curM;

		int gensym = 0;

		Lower(List<Cls> cs) {
			for (Cls c : cs) {
				this.classes.put(c.name, c);
				if (!c.iface && c.mths.stream().noneMatch(m -> m.ctor)) {
					Mth m = new Mth();
					m.name = "<init>";
					m.ctor = true;
					m.ret = VOID;
					m.owner = c;
					m.body = new Blk(List.of());
					c.mths.add(m);
				}
			}
		}

		static String sym(String id) {
			return id.chars().anyMatch(Character::isLowerCase) ? "|" + id + "|" : "|j%" + id + "|";
		}

		String tmp() {
			return "%t" + (this.gensym++);
		}

		RuntimeException unsupported(String what) {
			return new RuntimeException("spike: unsupported: " + what);
		}

		// ---- class table

		Cls cls(T t) {
			return t.d == 0 ? this.classes.get(t.n) : null;
		}

		boolean isSub(String a, String b) {
			if (a.equals(b)) {
				return true;
			}
			Cls c = this.classes.get(a);
			if (c == null) {
				return false;
			}
			if (c.sup != null && isSub(c.sup, b)) {
				return true;
			}
			return c.ifaces.stream().anyMatch(i -> isSub(i, b));
		}

		Fld field(Cls c, String name) {
			for (; c != null; c = c.sup == null ? null : this.classes.get(c.sup)) {
				for (Fld f : c.flds) {
					if (f.name.equals(name)) {
						return f;
					}
				}
			}
			return null;
		}

		void hierarchy(Cls c, List<Cls> into) {
			if (c == null || into.contains(c)) {
				return;
			}
			into.add(c);
			hierarchy(c.sup == null ? null : this.classes.get(c.sup), into);
			c.ifaces.forEach(i -> hierarchy(this.classes.get(i), into));
		}

		static int rank(T t) {
			return t.is("char") ? 0 : t.is("int") ? 1 : t.is("long") ? 2 : t.is("double") ? 3 : -1;
		}

		Mth functional(T t) {
			Cls c = cls(t);
			if (c == null || !c.iface) {
				return null;
			}
			List<Mth> abs = c.mths.stream().filter(m -> m.body == null).toList();
			return abs.size() == 1 ? abs.get(0) : null;
		}

		int score(T from, T to) {
			if (from == null) {
				return functional(to) != null ? 0 : -1;
			}
			if (from.erasure().equals(to.erasure())) {
				return 0;
			}
			if (rank(from) >= 0 && rank(to) >= 0) {
				return rank(from) <= rank(to) ? 1 : -1;
			}
			if (from.is("null")) {
				return rank(to) < 0 && !to.is("boolean") ? 1 : -1;
			}
			if (to.is("Object")) {
				return 2;
			}
			return from.d == 0 && to.d == 0 && isSub(from.n, to.n) ? 1 : -1;
		}

		Mth resolve(Cls c, String name, List<T> args, boolean ctor) {
			List<Cls> order = new ArrayList<>();
			if (ctor) {
				order.add(c);
			}
			else {
				hierarchy(c, order);
			}
			Mth best = null;
			int bestScore = Integer.MAX_VALUE;
			for (Cls k : order) {
				for (Mth m : k.mths) {
					if (!m.name.equals(name) || m.ps.size() != args.size()) {
						continue;
					}
					int total = 0;
					for (int i = 0; i < args.size() && total >= 0; i++) {
						int s = score(args.get(i), m.ps.get(i).t);
						total = s < 0 ? -1 : total + s;
					}
					if (total >= 0 && total < bestScore) {
						best = m;
						bestScore = total;
					}
				}
			}
			return best;
		}

		Mth implementation(Cls k, String sig) {
			for (; k != null; k = k.sup == null ? null : this.classes.get(k.sup)) {
				for (Mth m : k.mths) {
					if (m.body != null && !m.stat && m.sig().equals(sig)) {
						return m;
					}
				}
			}
			return null;
		}

		int depth(Cls c) {
			return c.sup == null ? 0 : 1 + depth(this.classes.get(c.sup));
		}

		String vcall(Cls stat, Mth m, String recv, List<String> args) {
			String sig = m.sig();
			Set<Mth> impls = new LinkedHashSet<>();
			for (Cls k : this.classes.values()) {
				if (!k.abs && isSub(k.name, stat.name)) {
					Mth impl = implementation(k, sig);
					if (impl != null) {
						impls.add(impl);
					}
				}
			}
			boolean lambda = stat.iface && functional(T.of(stat.name)) == m;
			String rest = args.isEmpty() ? "" : " " + String.join(" ", args);
			if (impls.isEmpty() && lambda) {
				return "(funcall " + recv + rest + ")";
			}
			if (impls.size() == 1 && !lambda) {
				return "(" + impls.iterator().next().sym() + " " + recv + rest + ")";
			}
			String name = "|" + stat.name + "." + sig + "%v|";
			if (!this.dispatchers.containsKey(name)) {
				StringBuilder ps = new StringBuilder();
				for (int i = 0; i < m.ps.size(); i++) {
					ps.append(" %a").append(i);
				}
				StringBuilder sb = new StringBuilder("(defun " + name + " (%o" + ps + ") (cond");
				if (lambda) {
					sb.append(" ((functionp %o) (funcall %o" + ps + "))");
				}
				impls.stream()
					.sorted((a, b) -> depth(b.owner) - depth(a.owner))
					.forEach(i -> sb.append(" ((|" + i.owner.name + "?| %o) (" + i.sym() + " %o" + ps + "))"));
				sb.append(" (t (error \"NullPointerException: " + sig + "\"))))");
				this.dispatchers.put(name, sb.toString());
			}
			return "(" + name + " " + recv + rest + ")";
		}

		// ---- scopes

		T local(String id) {
			for (Map<String, T> s : this.scopes) {
				T t = s.get(id);
				if (t != null) {
					return t;
				}
			}
			return null;
		}

		void bind(String id, T t) {
			this.scopes.peek().put(id, t);
		}

		// ---- conversions

		String coerce(X x, T to) {
			if (to != null && to.is("double") && x.t.integral()) {
				return x.c.matches("-?\\d+") ? x.c + ".0d0" : "(float " + x.c + " 1d0)";
			}
			return x.c;
		}

		String str(X x) {
			T t = x.t;
			if (t.is("String")) {
				return x.c.startsWith("\"") || x.c.startsWith("(concatenate") ? x.c : "(|%jnn| " + x.c + ")";
			}
			if (t.is("int") || t.is("long")) {
				return "(|%jitoa| " + x.c + ")";
			}
			if (t.is("char")) {
				return "(string (code-char " + x.c + "))";
			}
			if (t.is("boolean")) {
				return "(if " + x.c + " \"true\" \"false\")";
			}
			if (t.is("double")) {
				return "(|%jdtoa| " + x.c + ")";
			}
			if (t.is("StringBuilder")) {
				return "(coerce " + x.c + " 'simple-string)";
			}
			Cls c = cls(t);
			if (c != null) {
				// toString is Object's: an interface type has it without declaring it
				Mth m = new Mth();
				m.name = "toString";
				m.ret = STRING;
				m.owner = c;
				if (this.classes.values().stream().anyMatch(k -> !k.abs && isSub(k.name, c.name) && implementation(k, "toString()") != null)) {
					return vcall(c, m, x.c, List.of());
				}
			}
			return "(|%jany| " + x.c + ")";
		}

		String wrap(T t, String code) {
			return t.is("long") ? "(|%i64| " + code + ")" : t.is("double") ? code : "(|%i32| " + code + ")";
		}

		X cast(T to, X x) {
			if (to.is("int") && x.t.is("double")) {
				return new X("(|%i32| (truncate " + x.c + "))", INT);
			}
			if (to.is("long") && x.t.is("double")) {
				return new X("(|%i64| (truncate " + x.c + "))", LONG);
			}
			if (to.is("int") && x.t.is("long")) {
				return new X("(|%i32| " + x.c + ")", INT);
			}
			if (to.is("char") && x.t.integral()) {
				return new X(x.t.is("char") ? x.c : "(logand " + x.c + " 65535)", CHAR);
			}
			return new X(coerce(x, to), to);
		}

		// ---- expressions

		String staticTarget(E e) {
			if (e instanceof Name n && local(n.id) == null && (this.cur == null || field(this.cur, n.id) == null)) {
				return n.id;
			}
			return null;
		}

		X expr(E e) {
			return expr(e, null);
		}

		X expr(E e, T expect) {
			switch (e) {
				case Lit l:
					return new X(l.code, l.t);
				case This t:
					return new X("|this|", T.of(this.cur.name));
				case Name n: {
					T t = local(n.id);
					if (t != null) {
						return new X(sym(n.id), t);
					}
					Fld f = field(this.cur, n.id);
					if (f == null) {
						throw unsupported("name " + n.id);
					}
					return fieldRef(f, "|this|");
				}
				case Sel s: {
					String st = staticTarget(s.t);
					if (st != null) {
						if (st.equals("Integer") && s.name.equals("MAX_VALUE")) {
							return new X("2147483647", INT);
						}
						if (st.equals("Integer") && s.name.equals("MIN_VALUE")) {
							return new X("-2147483648", INT);
						}
						if (st.equals("Math") && s.name.equals("PI")) {
							return new X("3.141592653589793d0", DOUBLE);
						}
						Cls c = this.classes.get(st);
						Fld f = c == null ? null : field(c, s.name);
						if (f == null) {
							throw unsupported(st + "." + s.name);
						}
						return fieldRef(f, null);
					}
					X o = expr(s.t);
					if (o.t.d > 0 && s.name.equals("length")) {
						return new X("(length " + o.c + ")", INT);
					}
					Cls c = cls(o.t);
					Fld f = c == null ? null : field(c, s.name);
					if (f == null) {
						throw unsupported("field " + s.name + " of " + o.t.erasure());
					}
					return fieldRef(f, o.c);
				}
				case Call c:
					return call(c);
				case New n: {
					T t = n.t.a.isEmpty() && expect != null && expect.n.equals(n.t.n) ? expect : n.t;
					switch (t.n) {
						case "StringBuilder":
							return new X("(make-array 16 :element-type 'character :adjustable t :fill-pointer 0)", t);
						case "ArrayList":
							return new X("(make-array 8 :adjustable t :fill-pointer 0)", t);
						case "HashMap":
							return new X("(make-hash-table :test 'equal)", t);
						default:
					}
					Cls c = this.classes.get(t.n);
					if (c == null) {
						throw unsupported("new " + t.n);
					}
					return new X(invoke(c, "<init>", n.args, null, true), t);
				}
				case NewArr n: {
					T t = new T(n.elem.n, n.elem.a, n.elem.d + 1);
					String et = n.elem.is("int") ? " :element-type '(signed-byte 32)"
							: n.elem.is("double") ? " :element-type 'double-float" : "";
					String zero = n.elem.is("double") ? "0.0d0" : n.elem.integral() ? "0" : "nil";
					if (n.init != null) {
						StringBuilder sb = new StringBuilder("(make-array " + n.init.size() + et
								+ " :initial-contents (list");
						for (E i : n.init) {
							sb.append(' ').append(coerce(expr(i, n.elem), n.elem));
						}
						return new X(sb.append("))").toString(), t);
					}
					return new X("(make-array " + expr(n.len).c + et + " :initial-element " + zero + ")", t);
				}
				case Idx i: {
					X a = expr(i.a);
					return new X("(aref " + a.c + " " + expr(i.i).c + ")", a.t.elem());
				}
				case Un u: {
					if (u.op.equals("++") || u.op.equals("--")) {
						X assign = expr(new Asg(u.op.substring(1) + "=", u.e, new Lit(INT, "1")));
						return u.post ? new X("(prog1 " + expr(u.e).c + " " + assign.c + ")", assign.t) : assign;
					}
					X x = expr(u.e);
					return switch (u.op) {
						case "-" -> x.c.matches("\\d.*") ? new X("-" + x.c, x.t)
								: new X(wrap(x.t.is("char") ? INT : x.t, "(- " + x.c + ")"),
										x.t.is("char") ? INT : x.t);
						case "+" -> x;
						case "!" -> new X("(not " + x.c + ")", BOOL);
						default -> new X("(lognot " + x.c + ")", x.t);
					};
				}
				case Bin b:
					return binary(b);
				case Asg a:
					return assign(a);
				case Cnd c: {
					X a = expr(c.a, expect), b = expr(c.b, expect);
					T t = a.t.numeric() && b.t.numeric() ? promote(a.t, b.t) : a.t.is("null") ? b.t : a.t;
					return new X("(if " + expr(c.c).c + " " + coerce(a, t) + " " + coerce(b, t) + ")", t);
				}
				case Cast c:
					return cast(c.t, expr(c.e));
				case InstOf i: {
					Cls c = cls(i.t);
					if (c == null || c.iface) {
						throw unsupported("instanceof " + i.t.n);
					}
					return new X("(|" + c.name + "?| " + expr(i.e).c + ")", BOOL);
				}
				case Lam l: {
					Mth m = expect == null ? null : functional(expect);
					if (m == null) {
						throw unsupported("a lambda with no functional interface as its target");
					}
					this.scopes.push(new HashMap<>());
					StringBuilder ps = new StringBuilder();
					for (int k = 0; k < l.ps.size(); k++) {
						bind(l.ps.get(k), m.ps.get(k).t);
						ps.append(k > 0 ? " " : "").append(sym(l.ps.get(k)));
					}
					String body;
					if (l.expr != null) {
						body = coerce(expr(l.expr, m.ret), m.ret);
					}
					else {
						Mth saved = this.curM;
						Mth shape = new Mth();
						shape.ret = m.ret;
						this.curM = shape;
						body = fnBody(l.body);
						this.curM = saved;
					}
					this.scopes.pop();
					return new X("(lambda (" + ps + ") " + body + ")", expect);
				}
				default:
					throw unsupported(e.getClass().getSimpleName());
			}
		}

		X fieldRef(Fld f, String obj) {
			String name = "|" + f.owner.name + "." + f.name + "|";
			return new X(f.stat ? name : "(" + name + " " + obj + ")", f.t);
		}

		static T promote(T a, T b) {
			return a.is("double") || b.is("double") ? DOUBLE : a.is("long") || b.is("long") ? LONG : INT;
		}

		X binary(Bin b) {
			String op = b.op;
			if (op.equals("&&") || op.equals("||")) {
				return new X("(" + (op.equals("&&") ? "and " : "or ") + expr(b.l).c + " " + expr(b.r).c + ")", BOOL);
			}
			X l = expr(b.l), r = expr(b.r);
			if (op.equals("+") && (l.t.is("String") || r.t.is("String"))) {
				String ls = str(l), rs = str(r);
				if (ls.startsWith("(concatenate 'string ")) {
					return new X(ls.substring(0, ls.length() - 1) + " " + rs + ")", STRING);
				}
				return new X("(concatenate 'string " + ls + " " + rs + ")", STRING);
			}
			boolean num = l.t.numeric() && r.t.numeric();
			switch (op) {
				case "==", "!=": {
					String eq = "(" + (num ? "= " : "eq ") + l.c + " " + r.c + ")";
					return new X(op.equals("==") ? eq : "(not " + eq + ")", BOOL);
				}
				case "<", ">", "<=", ">=":
					return new X("(" + op + " " + l.c + " " + r.c + ")", BOOL);
				default:
			}
			if (l.t.is("boolean")) {
				String f = op.equals("&") ? "and" : op.equals("|") ? "or" : null;
				if (f == null) {
					throw unsupported("boolean " + op);
				}
				return new X("(" + f + " " + l.c + " " + r.c + ")", BOOL);
			}
			T t = promote(l.t, r.t);
			String a = coerce(l, t), c = coerce(r, t);
			int bits = t.is("long") ? 63 : 31;
			String mask = t.is("long") ? "18446744073709551615" : "4294967295";
			return switch (op) {
				case "+", "-", "*" -> new X(wrap(t, "(" + op + " " + a + " " + c + ")"), t);
				case "/" -> t.is("double") ? new X("(/ " + a + " " + c + ")", t)
						: new X(wrap(t, "(truncate " + a + " " + c + ")"), t);
				case "%" -> new X("(rem " + a + " " + c + ")", t);
				case "&" -> new X("(logand " + a + " " + c + ")", t);
				case "|" -> new X("(logior " + a + " " + c + ")", t);
				case "^" -> new X("(logxor " + a + " " + c + ")", t);
				case "<<" -> new X(wrap(l.t, "(ash " + l.c + " (logand " + r.c + " " + bits + "))"), l.t);
				case ">>" -> new X("(ash " + l.c + " (- (logand " + r.c + " " + bits + ")))", l.t);
				case ">>>" -> new X(wrap(l.t,
						"(ash (logand " + l.c + " " + mask + ") (- (logand " + r.c + " " + bits + ")))"), l.t);
				default -> throw unsupported("operator " + op);
			};
		}

		static boolean simple(E e) {
			return e instanceof Name || e instanceof This || e instanceof Lit
					|| (e instanceof Sel s && simple(s.t)) || (e instanceof Idx i && simple(i.a) && simple(i.i));
		}

		X assign(Asg a) {
			if (!a.op.equals("=")) {
				if (!simple(a.l)) {
					throw unsupported("a compound assignment to a place with side effects");
				}
				String op = a.op.substring(0, a.op.length() - 1);
				X target = expr(a.l);
				X value = expr(new Bin(op, a.l, a.r));
				return store(a.l, target.t, cast(target.t, value).c);
			}
			X target = expr(a.l);
			return store(a.l, target.t, coerce(expr(a.r, target.t), target.t));
		}

		X store(E place, T t, String value) {
			X target = expr(place);
			if (target.c.startsWith("(")) {
				return new X("(setf " + target.c + " " + value + ")", t);
			}
			return new X("(setq " + target.c + " " + value + ")", t);
		}

		String invoke(Cls c, String name, List<E> args, String recv, boolean ctor) {
			List<X> xs = new ArrayList<>();
			List<T> ts = new ArrayList<>();
			for (E a : args) {
				X x = a instanceof Lam ? null : expr(a);
				xs.add(x);
				ts.add(x == null ? null : x.t);
			}
			Mth m = resolve(c, name, ts, ctor);
			if (m == null) {
				throw unsupported("no method " + c.name + "." + name + "/" + args.size());
			}
			List<String> cs = new ArrayList<>();
			for (int i = 0; i < args.size(); i++) {
				T pt = m.ps.get(i).t;
				cs.add(coerce(xs.get(i) == null ? expr(args.get(i), pt) : xs.get(i), pt));
			}
			this.lastRet = m.ret;
			String rest = cs.isEmpty() ? "" : " " + String.join(" ", cs);
			if (ctor) {
				return recv == null ? "(|" + c.name + ".new" + m.sig().substring(6) + "|" + rest + ")"
						: "(" + m.sym() + " " + recv + rest + ")";
			}
			if (m.stat) {
				return "(" + m.sym() + rest + ")";
			}
			if (recv == null) {
				recv = "|this|";
			}
			return recv.equals("%super") ? "(" + implementation(c, m.sig()).sym() + " |this|" + rest + ")"
					: vcall(c, m, recv, cs);
		}

		T lastRet;

		X call(Call c) {
			if (c.sup) {
				Cls sup = this.classes.get(this.cur.sup);
				if (c.name.equals("super")) {
					return new X(invoke(sup, "<init>", c.args, "|this|", true), VOID);
				}
				if (c.name.equals("this")) {
					return new X(invoke(this.cur, "<init>", c.args, "|this|", true), VOID);
				}
				String code = invoke(sup, c.name, c.args, "%super", false);
				return new X(code, this.lastRet);
			}
			if (c.t == null) {
				T fn = local(c.name);
				String code = invoke(this.cur, c.name, c.args, null, false);
				return new X(code, this.lastRet);
			}
			// System.out.println / print
			if (c.t instanceof Sel s && "System".equals(staticTarget(s.t)) && s.name.equals("out")) {
				return print(c);
			}
			String st = staticTarget(c.t);
			List<X> as = new ArrayList<>();
			if (st != null && this.classes.containsKey(st)) {
				String code = invoke(this.classes.get(st), c.name, c.args, null, false);
				return new X(code, this.lastRet);
			}
			if (st != null) {
				if (st.equals("IO")) {
					return print(c);
				}
				for (E a : c.args) {
					as.add(expr(a));
				}
				String key = st + "." + c.name;
				switch (key) {
					case "Math.sqrt":
						return new X("(sqrt " + coerce(as.get(0), DOUBLE) + ")", DOUBLE);
					case "Math.floor":
						return new X("(ffloor " + coerce(as.get(0), DOUBLE) + ")", DOUBLE);
					case "Math.pow":
						return new X("(expt " + coerce(as.get(0), DOUBLE) + " " + coerce(as.get(1), DOUBLE) + ")",
								DOUBLE);
					case "Math.abs":
						return new X(wrap(as.get(0).t, "(abs " + as.get(0).c + ")"), as.get(0).t);
					case "Math.max", "Math.min": {
						T t = promote(as.get(0).t, as.get(1).t);
						return new X("(" + c.name + " " + coerce(as.get(0), t) + " " + coerce(as.get(1), t) + ")", t);
					}
					case "Integer.parseInt":
						return new X("(parse-integer " + as.get(0).c + ")", INT);
					case "String.valueOf", "Integer.toString":
						return new X(str(as.get(0)), STRING);
					default:
						throw unsupported(key);
				}
			}
			X o = expr(c.t);
			Cls k = cls(o.t);
			if (k != null) {
				String code = invoke(k, c.name, c.args, o.c, false);
				return new X(code, this.lastRet);
			}
			for (E a : c.args) {
				as.add(expr(a, o.t.elem()));
			}
			String a0 = as.size() > 0 ? as.get(0).c : null, a1 = as.size() > 1 ? as.get(1).c : null;
			String key = o.t.erasure() + "." + c.name;
			switch (key) {
				case "String.length", "StringBuilder.length", "ArrayList.size":
					return new X("(length " + o.c + ")", INT);
				case "String.charAt", "StringBuilder.charAt":
					return new X("(char-code (char " + o.c + " " + a0 + "))", CHAR);
				case "String.equals":
					return new X("(equal " + o.c + " " + a0 + ")", BOOL);
				case "String.isEmpty", "ArrayList.isEmpty":
					return new X("(= 0 (length " + o.c + "))", BOOL);
				case "String.substring":
					return new X("(subseq " + o.c + " " + a0 + (a1 == null ? "" : " " + a1) + ")", STRING);
				case "String.indexOf":
					return new X("(or (search " + a0 + " " + o.c + ") -1)", INT);
				case "String.toUpperCase":
					return new X("(string-upcase " + o.c + ")", STRING);
				case "StringBuilder.append":
					return new X("(|%jsb-append| " + o.c + " " + str(as.get(0)) + ")", o.t);
				case "StringBuilder.toString":
					return new X("(coerce " + o.c + " 'simple-string)", STRING);
				case "ArrayList.add":
					return new X("(progn (vector-push-extend " + a0 + " " + o.c + ") t)", BOOL);
				case "ArrayList.get":
					return new X("(aref " + o.c + " " + a0 + ")", o.t.elem());
				case "ArrayList.set":
					return new X("(setf (aref " + o.c + " " + a0 + ") " + a1 + ")", o.t.elem());
				case "HashMap.put":
					return new X("(setf (gethash " + a0 + " " + o.c + ") " + a1 + ")", o.t.a.get(1));
				case "HashMap.get":
					return new X("(gethash " + a0 + " " + o.c + ")", o.t.a.get(1));
				case "HashMap.getOrDefault":
					return new X("(gethash " + a0 + " " + o.c + " " + a1 + ")", o.t.a.get(1));
				case "HashMap.containsKey":
					return new X("(nth-value 1 (gethash " + a0 + " " + o.c + "))", BOOL);
				case "HashMap.size":
					return new X("(hash-table-count " + o.c + ")", INT);
				default:
					throw unsupported("method " + key);
			}
		}

		X print(Call c) {
			String s = c.args.isEmpty() ? "\"\"" : str(expr(c.args.get(0)));
			return new X(c.name.equals("println") ? "(write-line " + s + ")" : "(write-string " + s + ")", VOID);
		}

		// ---- statements

		static boolean returns(S s) {
			return s instanceof Ret || s instanceof Throw
					|| (s instanceof Blk b && !b.ss.isEmpty() && returns(b.ss.get(b.ss.size() - 1)))
					|| (s instanceof If i && i.b != null && returns(i.a) && returns(i.b));
		}

		static boolean continues(S s) {
			return s instanceof Cont || (s instanceof Blk b && b.ss.stream().anyMatch(Lower::continues))
					|| (s instanceof If i && (continues(i.a) || (i.b != null && continues(i.b))))
					|| (s instanceof Try t && (continues(t.body)
							|| t.cs.stream().anyMatch(c -> continues(c.body))));
		}

		String seq(List<S> ss, int i, boolean tail) {
			if (i == ss.size()) {
				return "";
			}
			S s = ss.get(i);
			if (s instanceof Local l) {
				X init = l.init == null ? null : expr(l.init, l.t);
				T t = l.t != null ? l.t : init.t;
				String value = init != null ? coerce(init, t)
						: t.is("double") ? "0.0d0" : t.integral() ? "0" : "nil";
				bind(l.name, t);
				return "(let ((" + sym(l.name) + " " + value + ")) " + seq(ss, i + 1, tail) + ")";
			}
			if (tail && s instanceof If f && f.b == null && returns(f.a) && i + 1 < ss.size()) {
				return "(if " + expr(f.c).c + " (progn " + stmt(f.a, true) + ") (progn " + seq(ss, i + 1, true)
						+ "))";
			}
			String head = stmt(s, tail && i == ss.size() - 1);
			return head + " " + seq(ss, i + 1, tail);
		}

		String scoped(S s, boolean tail) {
			this.scopes.push(new HashMap<>());
			String code = s instanceof Blk b ? seq(b.ss, 0, tail) : seq(List.of(s), 0, tail);
			this.scopes.pop();
			return code;
		}

		String loopBody(S body) {
			String code = scoped(body, false);
			return continues(body) ? "(block |%cont| " + code + ")" : code;
		}

		String stmt(S s, boolean tail) {
			switch (s) {
				case ExprS e:
					return expr(e.e).c;
				case Blk b:
					return scoped(b, tail);
				case If f: {
					String c = expr(f.c).c;
					String a = scoped(f.a, tail);
					if (f.b == null) {
						return "(when " + c + " " + a + ")";
					}
					return "(if " + c + " (progn " + a + ") (progn " + scoped(f.b, tail) + "))";
				}
				case While w:
					return "(loop (unless " + expr(w.c).c + " (return)) " + loopBody(w.body) + ")";
				case DoWhile w:
					return "(loop " + loopBody(w.body) + " (unless " + expr(w.c).c + " (return)))";
				case For f: {
					this.scopes.push(new HashMap<>());
					StringBuilder open = new StringBuilder();
					int lets = 0;
					for (S i : f.init) {
						if (i instanceof Local l) {
							X init = expr(l.init, l.t);
							T t = l.t != null ? l.t : init.t;
							bind(l.name, t);
							open.append("(let ((" + sym(l.name) + " " + coerce(init, t) + ")) ");
							lets++;
						}
						else {
							open.append(stmt(i, false)).append(' ');
						}
					}
					StringBuilder sb = new StringBuilder(open).append("(loop ");
					if (f.c != null) {
						sb.append("(unless " + expr(f.c).c + " (return)) ");
					}
					sb.append(loopBody(f.body));
					for (E u : f.upd) {
						sb.append(' ').append(expr(u).c);
					}
					sb.append(")").append(")".repeat(lets));
					this.scopes.pop();
					return lets == 0 && !f.init.isEmpty() ? "(progn " + sb + ")" : sb.toString();
				}
				case ForEach f: {
					X it = expr(f.it);
					String a = tmp(), i = tmp();
					this.scopes.push(new HashMap<>());
					bind(f.name, f.t != null ? f.t : it.t.elem());
					String body = loopBody(f.body);
					this.scopes.pop();
					return "(let ((" + a + " " + it.c + ") (" + i + " 0)) (loop (unless (< " + i + " (length " + a
							+ ")) (return)) (let ((" + sym(f.name) + " (aref " + a + " " + i + "))) " + body
							+ ") (setq " + i + " (+ " + i + " 1))))";
				}
				case Ret r: {
					String v = r.e == null ? "nil" : coerce(expr(r.e, this.curM.ret), this.curM.ret);
					if (tail) {
						return v;
					}
					this.fnBlock.peek()[0] = true;
					return "(return-from |%fn| " + v + ")";
				}
				case Brk b:
					return "(return)";
				case Cont c:
					return "(return-from |%cont|)";
				case Throw t:
					return "(error '|%jthrow| :obj " + expr(t.e).c + ")";
				case Try t: {
					String body = "(progn " + scoped(t.body, false) + ")";
					if (!t.cs.isEmpty()) {
						String c = tmp();
						StringBuilder javaArms = new StringBuilder();
						String lispArm = null;
						for (Catch k : t.cs) {
							this.scopes.push(new HashMap<>());
							bind(k.name, k.t);
							String handler = seq(k.body.ss, 0, false);
							this.scopes.pop();
							javaArms.append(" ((|" + k.t.n + "?| " + sym(k.name) + ") " + handler + ")");
							if (lispArm == null && isSub("ArithmeticException", k.t.n)) {
								lispArm = " (error (" + c + ") (let ((" + sym(k.name) + " (|%jlisp-error| " + c
										+ "))) " + handler + "))";
							}
						}
						String e = sym(t.cs.get(0).name);
						// every catch parameter of one try gets the first one's name here
						for (Catch k : t.cs) {
							if (!k.name.equals(t.cs.get(0).name)) {
								throw unsupported("catch parameters of one try with different names");
							}
						}
						body = "(handler-case " + body + " (|%jthrow| (" + c + ") (let ((" + e + " (|%jthrow-obj| "
								+ c + "))) (cond" + javaArms + " (t (error " + c + ")))))"
								+ (lispArm == null ? "" : lispArm) + ")";
					}
					if (t.fin != null) {
						body = "(unwind-protect " + body + " " + scoped(t.fin, false) + ")";
					}
					return body;
				}
				default:
					throw unsupported(s.getClass().getSimpleName());
			}
		}

		String fnBody(Blk body) {
			this.fnBlock.push(new boolean[1]);
			String code = scoped(body, true);
			boolean block = this.fnBlock.pop()[0];
			return block ? "(block |%fn| " + code + ")" : code.isBlank() ? "nil" : code;
		}

		// ---- declarations

		void emitStruct(Cls c, Set<String> done) {
			if (c.iface || !done.add(c.name)) {
				return;
			}
			if (c.sup != null) {
				emitStruct(this.classes.get(c.sup), done);
			}
			StringBuilder sb = new StringBuilder("(defstruct (|" + c.name + "|");
			if (c.sup != null) {
				sb.append(" (:include |" + c.sup + "|)");
			}
			sb.append(" (:conc-name |" + c.name + ".|) (:constructor |%make-" + c.name + "| ()) (:predicate |"
					+ c.name + "?|) (:copier nil))");
			for (Fld f : c.flds) {
				if (!f.stat) {
					sb.append(" (|" + f.name + "| "
							+ (f.t.is("double") ? "0.0d0" : f.t.integral() ? "0" : "nil") + ")");
				}
			}
			this.out.append(sb).append(")\n");
		}

		void emitMethod(Mth m) {
			if (m.body == null) {
				return;
			}
			this.cur = m.owner;
			this.curM = m;
			this.scopes.push(new HashMap<>());
			StringBuilder ps = new StringBuilder(m.stat ? "" : "|this|");
			for (Prm p : m.ps) {
				bind(p.name, p.t);
				ps.append(' ').append(sym(p.name));
			}
			String body;
			if (m.ctor) {
				StringBuilder sb = new StringBuilder();
				List<S> ss = m.body.ss;
				boolean explicit = !ss.isEmpty() && ss.get(0) instanceof ExprS e && e.e instanceof Call c && c.sup
						&& (c.name.equals("super") || c.name.equals("this"));
				boolean delegates = explicit && ((Call) ((ExprS) ss.get(0)).e).name.equals("this");
				if (explicit) {
					sb.append(stmt(ss.get(0), false)).append(' ');
				}
				else if (m.owner.sup != null) {
					sb.append(invoke(this.classes.get(m.owner.sup), "<init>", List.of(), "|this|", true)).append(' ');
				}
				if (!delegates) {
					for (Fld f : m.owner.flds) {
						if (!f.stat && f.init != null) {
							sb.append("(setf (|" + m.owner.name + "." + f.name + "| |this|) "
									+ coerce(expr(f.init, f.t), f.t) + ") ");
						}
					}
				}
				this.fnBlock.push(new boolean[1]);
				sb.append(seq(ss, explicit ? 1 : 0, false));
				body = this.fnBlock.pop()[0] ? "(block |%fn| " + sb + ")" : sb.toString();
				String args = ps.substring(6);
				this.out.append("(defun |" + m.owner.name + ".new" + m.sig().substring(6) + "| (" + args.trim()
						+ ") (let ((|this| (|%make-" + m.owner.name + "|))) (" + m.sym() + " |this|" + args
						+ ") |this|))\n");
			}
			else {
				body = fnBody(m.body);
			}
			this.scopes.pop();
			this.out.append("(defun " + m.sym() + " (" + ps.toString().trim() + ") " + body + ")\n");
		}

		String program() {
			this.out.append(RUNTIME);
			Set<String> done = new LinkedHashSet<>();
			this.classes.values().forEach(c -> emitStruct(c, done));
			StringBuilder statics = new StringBuilder();
			for (Cls c : this.classes.values()) {
				this.cur = c;
				this.scopes.push(new HashMap<>());
				for (Fld f : c.flds) {
					if (f.stat) {
						String init = f.init != null ? coerce(expr(f.init, f.t), f.t)
								: f.t.is("double") ? "0.0d0" : f.t.integral() ? "0" : "nil";
						this.out.append("(defvar |" + c.name + "." + f.name + "|)\n");
						statics.append("(setq |" + c.name + "." + f.name + "| " + init + ")\n");
					}
				}
				this.scopes.pop();
			}
			for (Cls c : this.classes.values()) {
				c.mths.forEach(this::emitMethod);
			}
			this.dispatchers.values().forEach(d -> this.out.append(d).append('\n'));
			this.out.append(statics);
			for (Cls c : this.classes.values()) {
				for (Mth m : c.mths) {
					if (m.name.equals("main") && c.sup == null && !"Throwable".equals(c.name)) {
						this.out.append("(" + m.sym() + (m.stat ? "" : " (|%make-" + c.name + "|)")
								+ (m.ps.isEmpty() ? "" : " (vector)") + ")\n");
						return this.out.toString();
					}
				}
			}
			throw unsupported("a program with no main");
		}

	}

	public static void main(String[] args) throws Exception {
		Parser jdk = new Parser(JDK);
		jdk.parseUnit();
		Parser p = new Parser(Files.readString(Path.of(args[0])));
		p.parseUnit();
		List<Cls> all = new ArrayList<>(jdk.classes);
		all.addAll(p.classes);
		System.out.print(new Lower(all).program());
	}

}
