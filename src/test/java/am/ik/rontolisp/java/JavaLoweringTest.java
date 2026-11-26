package am.ik.rontolisp.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.ExitLibrary;
import am.ik.rontolisp.eval.JavaLibrary;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lowering table of the EXPERIMENTAL front end for a subset of the Java language
 * ({@code .kb/java-frontend.md}) as emitted forms, and what the front end refuses: a
 * construct the subset does not have by name, an error a compiler reports that the
 * lowering depends on in its wording, each at its position. What the forms PRINT is
 * {@code JavaSpecE2eTest}'s, on four backends against the JDK.
 */
class JavaLoweringTest {

	private static String runtimeLibrary() {
		try (InputStream in = JavaLoweringTest.class.getResourceAsStream("/am/ik/rontolisp/eval/java.lisp")) {
			return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static List<String> lower(String source, String file) {
		return Java.read(source, file, JavaFiles.NONE).stream().map(LispVal::print).toList();
	}

	@Test
	void theSameSourceLoweredTwiceGivesTheSameForms() {
		// classes that need no other are initialized before main in an order the language
		// leaves open; the order is the declaration's, never an object's identity
		StringBuilder source = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			source.append("class C").append(i).append(" { static int n = ").append(i).append("; }\n");
		}
		source.append("void main() { IO.println(C0.n); }");
		List<String> first = lower(source.toString(), "Main.java");
		assertThat(lower(source.toString(), "Main.java")).isEqualTo(first);
	}

	@Test
	void theLoweringTable() {
		// A class is a structure, extends its :include, a field a slot with its type's
		// default, a static field a global set before main; a method's function has
		// its signature in its name; a call is direct with one body and a function
		// over the structure predicates with several, a funcall where the receiver can
		// only be a lambda; + is by the operands' static types; an unboxing tests for
		// null, and so does a call straight to a body through a reference that can be
		// null; a library class is read where the program first names it, here inside
		// method bodies; a catch makes the exceptions of the run time's it takes from
		// the condition; the program runs inside the handler of what nothing caught.
		assertThat(
				lower("""
						interface Shape { double area(); }
						interface Op { int apply(int x); }
						abstract class Base implements Shape {
						    static int count;
						    static final int LIMIT = 10;
						    protected String label = "b";
						    Base(String label) { this.label = label; count++; }
						    public String toString() { return label + area(); }
						}
						class Circle extends Base {
						    double r;
						    Circle(double r) { super("circle"); this.r = r; }
						    public double area() { return 3 * r * r; }
						}
						class Rect extends Base {
						    Rect() { super("rect"); }
						    public double area() { return 1; }
						}
						class L1 {
						    static int twice(int x) { return x * 2; }
						    static long wide(long x, int y) { return x + y << 3; }
						    static String show(Shape s, Object o, char c, boolean b) { return "s=" + s + o + c + b + s.area(); }
						    static int loop(int[] xs) {
						        int sum = 0;
						        for (int i = 0; i < xs.length; i++) { if (xs[i] < 0) continue; if (xs[i] > 99) break; sum += xs[i]; }
						        return sum;
						    }
						    static int guarded(Object o, int d) {
						        try { Circle c = (Circle) o; return (int) c.r / d; }
						        catch (ArithmeticException e) { return -1; }
						        finally { Base.count = Base.LIMIT; }
						    }
						    static Op adder(int n) { return x -> x + n; }
						    public static void main(String[] args) {
						        Shape s = new Circle(1.5);
						        Integer boxed = twice(3);
						        Boolean flag = boxed > 3;
						        System.out.println(show(s, boxed, 'c', flag) + adder(1).apply(2) + (s instanceof Rect) + (s instanceof Shape));
						        if (loop(new int[] {1, -2}) == 3) throw new IllegalStateException("x");
						    }
						}
						""",
						"L1.java"))
			.contains(
					"(DEFSTRUCT (|Base| (:CONC-NAME |Base.|) (:CONSTRUCTOR |%make-Base| NIL) (:PREDICATE |Base?|) "
							+ "(:COPIER NIL)) (|label| NIL))",
					"(DEFSTRUCT (|Circle| (:INCLUDE |Base|) (:CONC-NAME |Circle.|) (:CONSTRUCTOR |%make-Circle| "
							+ "NIL) (:PREDICATE |Circle?|) (:COPIER NIL)) (|r| 0.0))",
					"(DEFSTRUCT (|j%L1| (:CONC-NAME L1.) (:CONSTRUCTOR |%make-L1| NIL) (:PREDICATE |j%L1?|) (:COPIER "
							+ "NIL)))",
					"(DEFVAR |Base.count| 0)",
					"(DEFUN |Base.<init>(String)| (|this| |label|) (PROGN (SETF (|Base.label| |this|) \"b\") (SETF "
							+ "(|Base.label| |this|) |label|) (SETQ |Base.count| (%MASK-SIGNED-FIELD 32 (+ (THE "
							+ "(SIGNED-BYTE 32) |Base.count|) 1)))))",
					"(DEFUN |Base.toString()| (|this|) (%STRING-CONCAT (OR (|Base.label| |this|) \"null\") (RONTOLISP::%JAVA-DOUBLE-STRING "
							+ "(|Base.area()%v| |this|))))",
					"(DEFUN |Circle.new(double)| (|r|) (LET ((|this| (|%make-Circle|))) (|Circle.<init>(double)| "
							+ "|this| |r|) |this|))",
					"(DEFUN |Circle.area()| (|this|) (* (* 3.0 (|Circle.r| |this|)) (|Circle.r| |this|)))",
					"(DEFUN |Rect.new()| NIL (LET ((|this| (|%make-Rect|))) (|Rect.<init>()| |this|) |this|))",
					"(DEFUN |L1.twice(int)| (|x|) (DECLARE (TYPE (SIGNED-BYTE 32) |x|)) (%MASK-SIGNED-FIELD 32 (* "
							+ "|x| 2)))",
					"(DEFUN |L1.wide(long,int)| (|x| |y|) (DECLARE (TYPE (SIGNED-BYTE 64) |x|) (TYPE (SIGNED-BYTE "
							+ "32) |y|)) (%MASK-SIGNED-FIELD 64 (ASH (%MASK-SIGNED-FIELD 64 (+ |x| |y|)) 3)))",
					"(DEFUN |L1.show(Shape,Object,char,boolean)| (|s| |o| |c| |b|) (DECLARE (TYPE (INTEGER 0 "
							+ "1114111) |c|)) (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT "
							+ "(%STRING-CONCAT \"s=\" (|%string| |s|)) (|%string| |o|)) (RONTOLISP::%JAVA-CHAR-STRING "
							+ "|c|)) (IF |b| \"true\" \"false\")) (RONTOLISP::%JAVA-DOUBLE-STRING (|Shape.area()%v| "
							+ "|s|))))",
					// an array is declared of its vector or nil, and asserted of its
					// vector
					// where it is no null
					"(DEFUN |L1.loop(int[])| (|xs|) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY (UNSIGNED-BYTE 32) (*))) "
							+ "|xs|)) (LET ((|sum| 0)) (DECLARE (TYPE (SIGNED-BYTE 32) |sum|)) (LET "
							+ "((|i| 0)) (DECLARE (TYPE (SIGNED-BYTE 32) |i|)) (BLOCK |%break| (WHILE (< |i| (THE "
							+ "(SIGNED-BYTE 32) (LENGTH (THE (SIMPLE-ARRAY (UNSIGNED-BYTE 32) (*)) (OR |xs| "
							+ "(RONTOLISP::%JAVA-NPE)))))) (BLOCK |%continue| (IF (< "
							+ "(%MASK-SIGNED-FIELD 32 (AREF |xs| |i|)) 0) (RETURN-FROM |%continue| NIL)) (IF (> "
							+ "(%MASK-SIGNED-FIELD 32 (AREF |xs| |i|)) 99) (RETURN-FROM |%break| NIL)) (SETQ |sum| "
							+ "(%MASK-SIGNED-FIELD 32 (+ |sum| (%MASK-SIGNED-FIELD 32 (AREF |xs| |i|)))))) (SETQ |i| "
							+ "(%MASK-SIGNED-FIELD 32 (+ |i| 1)))))) |sum|))",
					"(DEFUN |L1.guarded(Object,int)| (|o| |d|) (DECLARE (TYPE (SIGNED-BYTE 32) |d|)) (BLOCK |%fn| "
							+ "(UNWIND-PROTECT (HANDLER-CASE (LET ((|c| (LET ((|%t0| |o|)) (IF (OR (NULL |%t0|) "
							+ "(|Circle?| |%t0|)) |%t0| (RONTOLISP::%JAVA-THROW (|ClassCastException.new(String)| (IF "
							+ "(FUNCTIONP |%t0|) NIL (%STRING-CONCAT (%STRING-CONCAT \"class \" (|%class-name| |%t0|)) \" "
							+ "cannot be cast to class Circle\"))) #'|Throwable.toString()|))))) (RETURN-FROM |%fn| "
							+ "(%MASK-SIGNED-FIELD 32 (THE (INTEGER -2147483648 2147483648) (TRUNCATE (THE (SIGNED-BYTE "
							+ "32) (RONTOLISP::%JAVA-DOUBLE-TO-INT (|Circle.r| |c|))) |d|))))) ((SATISFIES |%catch0|) "
							+ "(|%t1|) (LET ((|e| (OR (RONTOLISP::%JAVA-THROWN |%t1|) (|%lisp-error| |%t1|)))) "
							+ "(RETURN-FROM |%fn| -1)))) (SETQ |Base.count| 10))))",
					// a catch clause's type is its test: a condition no clause takes goes
					// on as it came, signalled by nothing
					"(DEFUN |%catch0| (|%c|) (|ArithmeticException?| (OR (RONTOLISP::%JAVA-THROWN |%c|) "
							+ "(|%lisp-error| |%c|))))",
					"(DEFUN |L1.adder(int)| (|n|) (DECLARE (TYPE (SIGNED-BYTE 32) |n|)) (LAMBDA (|x|) (DECLARE "
							+ "(TYPE (SIGNED-BYTE 32) |x|)) (%MASK-SIGNED-FIELD 32 (+ |x| |n|))))",
					"(DEFUN |L1.main(String[])| (|args|) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY T (*))) |args|)) "
							+ "(LET ((|s| (|Circle.new(double)| 1.5))) (LET ((|boxed| "
							+ "(|L1.twice(int)| 3))) (LET ((|flag| (IF (> (THE (SIGNED-BYTE 32) (OR |boxed| "
							+ "(RONTOLISP::%JAVA-NULL-UNBOX))) 3) T :FALSE))) (WRITE-LINE (%STRING-CONCAT (%STRING-CONCAT "
							+ "(%STRING-CONCAT (OR (|L1.show(Shape,Object,char,boolean)| |s| |boxed| 99 "
							+ "(RONTOLISP::%JAVA-UNBOX-BOOLEAN |flag|)) \"null\") (%DECIMAL-PIECE (FUNCALL (OR "
							+ "(|L1.adder(int)| 1) (RONTOLISP::%JAVA-NPE)) 2))) (IF (|Rect?| |s|) \"true\" \"false\")) "
							+ "(IF (NOT (NULL |s|)) \"true\" \"false\"))) (IF (= (THE (SIGNED-BYTE 32) (|L1.loop(int[])| "
							+ "(MAKE-ARRAY 2 :ELEMENT-TYPE '(UNSIGNED-BYTE 32) :INITIAL-CONTENTS '(1 4294967294)))) 3) "
							+ "(RONTOLISP::%JAVA-THROW (|IllegalStateException.new(String)| \"x\") "
							+ "#'|Throwable.toString()|))))))",
					"(DEFUN |%class-name| (|%o|) (COND ((|ClassCastException?| |%o|) \"java.lang.ClassCastException\")"
							+ " ((|ArithmeticException?| |%o|) \"java.lang.ArithmeticException\") ((|IllegalStateException?|"
							+ " |%o|) \"java.lang.IllegalStateException\") ((|Circle?| |%o|) \"Circle\") ((|Rect?| |%o|)"
							+ " \"Rect\") ((|j%L1?| |%o|) \"L1\") (T (RONTOLISP::%JAVA-CLASS-NAME |%o| T))))",
					"(DEFUN |Throwable.toString()| (|this|) (LET ((|name| (|%class-name| |this|))) (LET ((|text| "
							+ "(|Throwable.getLocalizedMessage()| |this|))) (IF (NULL |text|) |name| (%STRING-CONCAT (%STRING-CONCAT "
							+ "(OR |name| \"null\") \": \") (OR |text| \"null\"))))))",
					"(DEFUN |Base.area()%v| (|%o|) (COND ((|Circle?| |%o|) (|Circle.area()| |%o|)) ((|Rect?| "
							+ "|%o|) (|Rect.area()| |%o|)) (T (RONTOLISP::%JAVA-NO-RECEIVER |%o| \"area()\"))))",
					"(DEFUN |Shape.area()%v| (|%o|) (COND ((|Circle?| |%o|) (|Circle.area()| |%o|)) ((|Rect?| "
							+ "|%o|) (|Rect.area()| |%o|)) (T (RONTOLISP::%JAVA-NO-RECEIVER |%o| \"area()\"))))",
					"(DEFUN |%string| (|%o|) (COND ((|Base?| |%o|) (|Base.toString()| |%o|)) ((|Throwable?| |%o|) "
							+ "(|Throwable.toString()| |%o|)) (T (RONTOLISP::%JAVA-TO-STRING |%o|))))",
					"(DEFUN |%lisp-error| (|%c|) (LET ((|%e| (RONTOLISP::%JAVA-EXCEPTION-OF |%c|))) (COND ((EQUAL "
							+ "(CAR |%e|) \"java.lang.ArithmeticException\") (|ArithmeticException.new(String)| (CDR |%e|))))))",
					// what nothing caught is the pipeline's own report with the program's
					// line in it: no handler holds the condition
					"(%REPORTING-UNCAUGHT #'RONTOLISP::%JAVA-UNCAUGHT-THROWN-LINE (%HOST-EXIT 1) (|L1.main(String[])| "
							+ "(VECTOR)))");
		// every exception class the run time's table names is one the lowering can make
		Matcher named = Pattern.compile("\"(java\\.(?:lang|util)\\.\\w+Exception)\"").matcher(runtimeLibrary());
		while (named.find()) {
			assertThat(JavaLowering.RAISED).as("java.lisp names %s", named.group(1)).contains(named.group(1));
		}
		// the decimal digits above ASCII Character.digit reads are the running JDK's
		assertThat(runtimeLibrary()).as("java.lisp's %java-unicode-digit").contains("\"" + decimalZeros() + "\"");
		// the prelude keeps a function a program names in any position, so a variable or
		// a slot of java.lisp named as one keeps it in every program reaching the
		// definition: `describe` kept 10 KB of wasm in each program that throws
		List<String> bound = new ArrayList<>();
		for (LispVal form : JavaLibrary.everyVariantForms()) {
			bound(form, bound);
		}
		assertThat(bound).isNotEmpty().noneMatch(LispPreludeLibrary::isPreludeFunction);
		// no time passes within a reactor's call: its variant alone names no sleep -- but
		// where its host waits for it (--host-timer), whose timer the sleep is and where
		// a request's call idles, the next request's to ring, and what the threads have
		// left with none idling is a turn the host is asked for
		assertThat(JavaLibrary.forms(Features.WASM_REACTOR).stream().map(LispVal::print))
			.noneMatch(form -> form.contains("(SLEEP ") || form.contains("%HOST-IDLE"));
		assertThat(JavaLibrary.forms(Features.WASM).stream().map(LispVal::print))
			.anyMatch(form -> form.contains("(SLEEP "));
		assertThat(JavaLibrary.forms(Features.WASM_REACTOR.with(List.of(Features.HOST_TIMER)))
			.stream()
			.map(LispVal::print)).anyMatch(form -> form.contains("(SLEEP "))
			.anyMatch(form -> form.contains("(RONTOLISP::%HOST-IDLE "))
			.anyMatch(form -> form.contains("(RONTOLISP::%HOST-RING "))
			.anyMatch(form -> form.contains("(RONTOLISP::%HOST-WAKE "));
	}

	/**
	 * The zeros of the sets of ten code points above ASCII whose general category is Nd,
	 * in order, as java.lisp's {@code %java-unicode-digit} spells them: the characters of
	 * a string.
	 */
	private static String decimalZeros() {
		StringBuilder zeros = new StringBuilder();
		for (int code = 0x80; code <= Character.MAX_CODE_POINT; code++) {
			if (Character.getType(code) == Character.DECIMAL_DIGIT_NUMBER && Character.digit(code, 10) == 0) {
				zeros.appendCodePoint(code);
			}
		}
		return zeros.toString();
	}

	/**
	 * The names a form of java.lisp binds: parameters, let variables, a condition's
	 * slots.
	 */
	private static void bound(LispVal form, List<String> into) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		List<LispVal> parts = cons.toList();
		String head = parts.get(0) instanceof LispSymbol symbol ? symbol.name() : "";
		int bindings = switch (head) {
			case "DEFUN" -> 2;
			case "LAMBDA", "LET", "LET*", "DOLIST", "DOTIMES" -> 1;
			case "DEFINE-CONDITION" -> 3;
			default -> -1;
		};
		if (bindings >= 0 && bindings < parts.size() && parts.get(bindings) instanceof LispCons list) {
			for (LispVal binding : list.toList()) {
				LispVal name = binding instanceof LispCons pair ? pair.car() : binding;
				if (name instanceof LispSymbol symbol) {
					into.add(symbol.name());
				}
			}
		}
		for (LispVal part : parts) {
			bound(part, into);
		}
	}

	@Test
	void theDeclarationsAndStatementsPastTheFirstSlice() {
		// A record and an enum are classes with the members the language gives them; an
		// inner class holds the instance around it and a class declared in a method the
		// locals it reads, each a slot set where the instance is made; a switch is a cond
		// over the selector, cases that run into the next numbered; a pattern's binding
		// is a variable of its statement, set where the test holds; a labeled loop's
		// block has the label's name; a variable arity call makes the array; a class
		// whose static initialization shows is initialized at its first use, any other
		// before main by a function of its own, after the settings that cannot throw,
		// each a form of its own.
		assertThat(
				lower("""
						sealed interface Shape permits Circle, Square { }
						record Circle(double r) implements Shape { }
						record Square(int side) implements Shape { }
						enum Level { LOW, HIGH; static final Level[] ALL = values(); }
						class Log { static int lines; static { System.out.println("log"); } static void line() { lines++; } }
						class L2 {
						    int base = 1;
						    class Inner { int twice() { return base * 2; } }
						    static String name(int n) {
						        switch (n) {
						            case 1: return "one";
						            case 2:
						            case 3: return "few";
						            default: return "many";
						        }
						    }
						    static int fall(int n) {
						        int acc = 0;
						        switch (n) { case 1: acc += 1; case 2: acc += 10; break; default: acc = -1; }
						        return acc;
						    }
						    static double area(Shape s) {
						        return switch (s) { case Circle c -> 3 * c.r() * c.r(); case Square(int side) when side > 1 -> side * side; case Square q -> 1; };
						    }
						    static int rank(Level l) { return switch (l) { case LOW -> 1; case HIGH -> { int r = 2; yield r * 2; } }; }
						    static int size(Object o) { if (!(o instanceof String s)) return -1; return s.length(); }
						    static int sum(int... xs) { int t = 0; outer: for (int x : xs) { if (x < 0) break outer; t += x; } return t; }
						    public static void main(String[] args) {
						        int step = 3;
						        Runnable r = new Runnable() { public void run() { Log.line(); System.out.println(step); } };
						        r.run();
						        System.out.println(name(2) + fall(1) + area(new Square(2)) + rank(Level.HIGH) + size("abc") + sum(1, 2) + new L2().new Inner().twice() + Level.ALL.length + Log.lines);
						    }
						}
						""",
						"L2.java"))
			.contains(
					"(DEFSTRUCT (|Circle| (:INCLUDE |java.lang.Record|) (:CONC-NAME |Circle.|) (:CONSTRUCTOR |%make-Circle| "
							+ "NIL) (:PREDICATE |Circle?|) (:COPIER NIL)) (|r| 0.0))",
					"(DEFSTRUCT (|L2$Inner| (:CONC-NAME |L2.Inner.|) (:CONSTRUCTOR |%make-L2.Inner| NIL) (:PREDICATE "
							+ "|L2.Inner?|) (:COPIER NIL)) (|this$0| NIL))",
					"(DEFSTRUCT (|j%L2$1| (:CONC-NAME L2$1.) (:CONSTRUCTOR |%make-L2$1| NIL) (:PREDICATE |j%L2$1?|) "
							+ "(:COPIER NIL)) (|val$step$0| NIL))",
					"(DEFUN |Circle.toString()| (|this|) (%STRING-CONCAT (%STRING-CONCAT \"Circle[r=\" (RONTOLISP::%JAVA-DOUBLE-STRING "
							+ "(|Circle.r| |this|))) \"]\"))",
					"(DEFUN |Circle.equals(Object)| (|this| |$o|) (LET ((|$that| NIL)) (AND (AND (|Circle?| |$o|) "
							+ "(PROGN (SETQ |$that| |$o|) T)) (= (THE (SIGNED-BYTE 32) (RONTOLISP::%JAVA-DOUBLE-COMPARE "
							+ "(|Circle.r| |this|) (|Circle.r| |$that|))) 0))))",
					"(DEFUN |Log.line()| NIL (PROGN (|Log.<clinit>|) (SETQ |Log.lines| (%MASK-SIGNED-FIELD 32 (+ "
							+ "(THE (SIGNED-BYTE 32) |Log.lines|) 1)))))",
					"(DEFUN |L2.name(int)| (|n|) (DECLARE (TYPE (SIGNED-BYTE 32) |n|)) (COND ((EQL |n| 1) \"one\") "
							+ "((OR (EQL |n| 2) (EQL |n| 3)) \"few\") (T \"many\")))",
					"(DEFUN |L2.fall(int)| (|n|) (DECLARE (TYPE (SIGNED-BYTE 32) |n|)) (LET ((|acc| 0)) (DECLARE "
							+ "(TYPE (SIGNED-BYTE 32) |acc|)) (BLOCK |%break| (LET ((|%t0| (COND ((EQL |n| 1) 0) ((EQL "
							+ "|n| 2) 1) (T 2)))) (IF (<= |%t0| 0) (SETQ |acc| (%MASK-SIGNED-FIELD 32 (+ |acc| 1)))) (IF "
							+ "(<= |%t0| 1) (PROGN (SETQ |acc| (%MASK-SIGNED-FIELD 32 (+ |acc| 10))) (RETURN-FROM "
							+ "|%break| NIL))) (IF (<= |%t0| 2) (SETQ |acc| -1)))) |acc|))",
					"(DEFUN |L2.area(Shape)| (|s|) (LET ((|c| NIL) (|side| 0) (|q| NIL)) (COND ((NULL |s|) "
							+ "(RONTOLISP::%JAVA-NULL-SWITCH)) ((AND (|Circle?| |s|) (PROGN (SETQ |c| |s|) T)) (* (* 3.0 "
							+ "(|Circle.r()| (OR |c| (RONTOLISP::%JAVA-NPE)))) (|Circle.r()| (OR |c| "
							+ "(RONTOLISP::%JAVA-NPE))))) ((AND (AND (|Square?| |s|) (PROGN (SETQ |side| (|Square.side| "
							+ "|s|)) T)) (> |side| 1)) (FLOAT (%MASK-SIGNED-FIELD 32 (* |side| |side|)) 1.0)) ((AND "
							+ "(|Square?| |s|) (PROGN (SETQ |q| |s|) T)) 1.0) (T (RONTOLISP::%JAVA-NO-MATCH)))))",
					"(DEFUN |L2.rank(Level)| (|l|) (COND ((EQ |l| |Level.LOW|) 1) ((EQ |l| |Level.HIGH|) (LET ((|r| "
							+ "2)) (DECLARE (TYPE (SIGNED-BYTE 32) |r|)) (%MASK-SIGNED-FIELD 32 (* |r| 2)))) ((NULL |l|) "
							+ "(RONTOLISP::%JAVA-NULL-SWITCH)) (T (RONTOLISP::%JAVA-NO-MATCH))))",
					"(DEFUN |L2.size(Object)| (|o|) (LET ((|s| NIL)) (IF (NOT (AND (SIMPLE-STRING-P |o|) (PROGN "
							+ "(SETQ |s| |o|) T))) -1 (LENGTH (OR |s| (RONTOLISP::%JAVA-NPE))))))",
					"(DEFUN |L2.sum(int[])| (|xs|) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY (UNSIGNED-BYTE 32) (*))) "
							+ "|xs|)) (LET ((|t| 0)) (DECLARE (TYPE (SIGNED-BYTE 32) |t|)) (BLOCK "
							+ "|outer%break| (LET* ((|%t1| (THE (SIMPLE-ARRAY (UNSIGNED-BYTE 32) (*)) (OR |xs| "
							+ "(RONTOLISP::%JAVA-NPE)))) (|%t3| (LENGTH |%t1|)) "
							+ "(|%t2| 0)) (WHILE (< |%t2| |%t3|) (LET ((|x| (%MASK-SIGNED-FIELD 32 (AREF |%t1| |%t2|)))) "
							+ "(DECLARE (TYPE (SIGNED-BYTE 32) |x|)) (IF (< |x| 0) (RETURN-FROM |outer%break| NIL)) (SETQ "
							+ "|t| (%MASK-SIGNED-FIELD 32 (+ |t| |x|)))) (SETQ |%t2| (+ |%t2| 1))))) |t|))",
					"(DEFUN |L2$1.run()| (|this|) (PROGN (|Log.line()|) (WRITE-LINE (%DECIMAL-PIECE (|L2$1.val$step$0| "
							+ "|this|)))))",
					"(DEFUN |L2$1.new()| (|%c0|) (LET ((|this| (|%make-L2$1|))) (SETF (|L2$1.val$step$0| |this|) "
							+ "|%c0|) |this|))",
					"(DEFUN |L2.Inner.twice()| (|this|) (%MASK-SIGNED-FIELD 32 (* (THE (SIGNED-BYTE 32) (|L2.base| "
							+ "(|L2.Inner.this$0| |this|))) 2)))",
					"(DEFUN |L2.Inner.new()| (|%outer|) (LET ((|this| (|%make-L2.Inner|))) (SETF (|L2.Inner.this$0| "
							+ "|this|) |%outer|) |this|))",
					"(DEFUN |Log.<clinit>| NIL (IF |Log.<clinit>%done| NIL (PROGN (SETQ |Log.<clinit>%done| T) "
							+ "(WRITE-LINE \"log\"))))",
					"(DEFUN |%initialization| NIL (PROGN (SETQ |Level.LOW| (|Level.new(String,int)| \"LOW\" 0)) (SETQ "
							+ "|Level.HIGH| (|Level.new(String,int)| \"HIGH\" 1)) (SETQ |Level.$VALUES| (VECTOR |Level.LOW| "
							+ "|Level.HIGH|)) (SETQ |Level.ALL| (|Level.values()|))))")
			.contains("(DEFVAR |Level.$VALUES| NIL)", "(DEFVAR |Log.lines| 0)", "(DEFVAR |Log.<clinit>%done| NIL)")
			.containsSubsequence(
					"(%REPORTING-UNCAUGHT #'RONTOLISP::%JAVA-UNCAUGHT-LINE (%HOST-EXIT 1) (|%initialization|) "
							+ "(|L2.main(String[])| (VECTOR)))");
	}

	@Test
	void typeParametersLambdasAndBoxes() {
		// A type parameter is erased: a method's name has its bound, and a member typed
		// by one is converted where the type it is read through gives a box. An override
		// through a type parameter is one entry of the dispatcher, beside the predicates
		// of the library's own values, each testing an argument it takes narrower as a
		// bridge method casts it. A lambda is a function, called by funcall; the
		// lambdas of an interface instanceof asks for are structures holding it. A method
		// reference is the lambda calling the method. A boxed long is the number in a
		// cons, and an unboxing tests for null.
		assertThat(
				lower("""
						import java.util.function.Function;

						interface Named { String name(); default String greet() { return "hi " + name(); } }
						interface Task { void run(); }
						class Version implements Comparable<Version> {
						    final int n;
						    Version(int n) { this.n = n; }
						    public int compareTo(Version o) { return n - o.n; }
						}
						class Box<T extends Comparable<T>> {
						    T held;
						    Box(T held) { this.held = held; }
						    boolean above(T other) { return held.compareTo(other) > 0; }
						    <R> R with(Function<? super T, ? extends R> f) { return f.apply(held); }
						}
						class L3 {
						    static <T> T pick(T a, T b) { return a != null ? a : b; }
						    static long sum(Long a, Integer b) { return a + b; }
						    public static void main(String[] args) {
						        Box<Version> v = new Box<>(new Version(2));
						        Box<String> s = new Box<>("b");
						        Named named = () -> "n";
						        Task task = () -> { };
						        Object o = named;
						        Long big = 5L;
						        int len = s.with(String::length);
						        System.out.println(v.above(new Version(1)) + " " + s.above("a") + (o instanceof Named n ? n.greet() : "") + sum(big, len) + pick(1, 2));
						        task.run();
						    }
						}
						""",
						"L3.java"))
			.contains(
					"(DEFSTRUCT (|Named%lambda| (:CONSTRUCTOR |%make-Named%lambda| (|%f|)) (:PREDICATE |Named%lambda?|) "
							+ "(:COPIER NIL)) |%f|)",
					"(DEFUN |Box.above(Comparable)| (|this| |other|) (> (THE (SIGNED-BYTE 32) "
							+ "(|Comparable.compareTo(Object)%v| (|Box.held| |this|) |other|)) 0))",
					"(DEFUN |Box.with(java.util.function.Function)| (|this| |f|) (FUNCALL (OR |f| (RONTOLISP::%JAVA-NPE)) "
							+ "(|Box.held| |this|)))",
					"(DEFUN |L3.pick(Object,Object)| (|a| |b|) (IF (NOT (NULL |a|)) |a| |b|))",
					"(DEFUN |L3.sum(Long,Integer)| (|a| |b|) (%MASK-SIGNED-FIELD 64 (+ (THE (SIGNED-BYTE 64) (CAR "
							+ "(OR |a| (RONTOLISP::%JAVA-NULL-UNBOX)))) (THE (SIGNED-BYTE 64) (OR |b| "
							+ "(RONTOLISP::%JAVA-NULL-UNBOX))))))",
					"(DEFUN |L3.main(String[])| (|args|) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY T (*))) |args|)) "
							+ "(LET ((|v| (|Box.new(Comparable)| (|Version.new(int)| "
							+ "2)))) (LET ((|s| (|Box.new(Comparable)| \"b\"))) (LET ((|named| (|%make-Named%lambda| "
							+ "(LAMBDA NIL \"n\")))) (LET ((|task| (LAMBDA NIL NIL))) (LET ((|o| |named|)) (LET ((|big| "
							+ "(CONS 5 :LONG))) (LET ((|len| (OR (|Box.with(java.util.function.Function)| (OR |s| "
							+ "(RONTOLISP::%JAVA-NPE)) (LAMBDA (|%t0|) (LENGTH (OR |%t0| (RONTOLISP::%JAVA-NPE))))) "
							+ "(RONTOLISP::%JAVA-NULL-UNBOX)))) (DECLARE (TYPE (SIGNED-BYTE 32) |len|)) (LET ((|n| NIL)) "
							+ "(WRITE-LINE (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT "
							+ "(%STRING-CONCAT (IF (|Box.above(Comparable)| (OR |v| (RONTOLISP::%JAVA-NPE)) "
							+ "(|Version.new(int)| 1)) \"true\" \"false\") \" \") (IF (|Box.above(Comparable)| (OR |s| "
							+ "(RONTOLISP::%JAVA-NPE)) \"a\") \"true\" \"false\")) (OR (IF (|Named%is| |o|) (PROGN (SETQ "
							+ "|n| |o|) (|Named.greet()%v| |n|)) \"\") \"null\")) (%DECIMAL-PIECE (|L3.sum(Long,Integer)| "
							+ "|big| |len|))) (RONTOLISP::%JAVA-TO-STRING (|L3.pick(Object,Object)| 1 2))))) (FUNCALL (OR "
							+ "|task| (RONTOLISP::%JAVA-NPE)))))))))))",
					"(DEFUN |Named.name()%v| (|%o|) (COND ((|Named%lambda?| |%o|) (FUNCALL (|Named%lambda-%f| "
							+ "|%o|))) (T (RONTOLISP::%JAVA-NO-RECEIVER |%o| \"name()\"))))",
					"(DEFUN |Comparable.compareTo(Object)%v| (|%o| |%a0|) (COND ((|Version?| |%o|) (IF (OR (NULL |%a0|) "
							+ "(|Version?| |%a0|)) (|Version.compareTo(Version)| |%o| |%a0|) (|%cast-refused| |%a0| \"Version\"))) "
							+ "((SIMPLE-STRING-P |%o|) (IF (OR (NULL |%a0|) (SIMPLE-STRING-P |%a0|)) (RONTOLISP::%JAVA-STRING-COMPARE "
							+ "|%o| |%a0|) (|%cast-refused| |%a0| \"java.lang.String\"))) ((RONTOLISP::%JAVA-LONG-P |%o|) (IF (OR "
							+ "(NULL |%a0|) (RONTOLISP::%JAVA-LONG-P |%a0|)) (RONTOLISP::%JAVA-COMPARE (CAR |%o|) (CAR |%a0|)) "
							+ "(|%cast-refused| |%a0| \"java.lang.Long\"))) ((INTEGERP |%o|) (IF (OR (NULL |%a0|) (INTEGERP |%a0|)) "
							+ "(RONTOLISP::%JAVA-COMPARE |%o| |%a0|) (|%cast-refused| |%a0| \"java.lang.Integer\"))) (T "
							+ "(RONTOLISP::%JAVA-NO-RECEIVER |%o| \"compareTo(Object)\"))))",
					"(DEFUN |%cast-refused| (|%v| |%t|) (RONTOLISP::%JAVA-THROW (|ClassCastException.new(String)| (IF (OR "
							+ "(FUNCTIONP |%v|) (|Named%lambda?| |%v|)) NIL (%STRING-CONCAT (%STRING-CONCAT \"class \" (|%class-name| "
							+ "|%v|)) (%STRING-CONCAT \" cannot be cast to class \" |%t|)))) #'|Throwable.toString()|))",
					"(DEFUN |Named%is| (|%o|) (OR (|Named%lambda?| |%o|)))");
	}

	@Test
	void theLibraryIsJavaSourceLoweredAsFarAsTheProgramReaches() {
		// A collection is a class of the library's Java source: of ArrayList the program
		// holds the methods it reaches and no other, a call of one is direct while one
		// class can take it, the enhanced for asks an Iterable for its iterator, a
		// native member is its form, a member of a box written in Java a call of the
		// library's own class; the constants of a program comparing two strings by
		// reference are one object a text.
		List<String> forms = lower("""
				import java.util.*;
				class Bag implements Iterable<String> {
				    final List<String> items = new ArrayList<>();
				    public Iterator<String> iterator() { return items.iterator(); }
				}
				class L4 {
				    public static void main(String[] args) {
				        Bag bag = new Bag();
				        bag.items.add("a");
				        String first = "a";
				        for (String item : bag) { System.out.println(item == first); }
				        int[] ns = {2, 1};
				        Arrays.sort(ns);
				        System.out.println(Integer.parseInt("7") + Math.sqrt(ns[0]));
				    }
				}
				""", "L4.java");
		assertThat(forms).contains(
				"(DEFSTRUCT (|java.util.ArrayList| (:INCLUDE |java.util.AbstractList|) (:CONC-NAME |java.util.ArrayList.|) "
						+ "(:CONSTRUCTOR |%make-java.util.ArrayList| NIL) (:PREDICATE |java.util.ArrayList?|) (:COPIER "
						+ "NIL)) (|items| NIL) (|count| 0))",
				"(DEFUN |Bag.iterator()| (|this|) (|java.util.ArrayList.iterator()| (OR (|Bag.items| |this|) "
						+ "(RONTOLISP::%JAVA-NPE))))",
				"(DEFUN |L4.main(String[])| (|args|) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY T (*))) |args|)) "
						+ "(LET ((|bag| (|Bag.new()|))) "
						+ "(|java.util.ArrayList.add(Object)| (OR (|Bag.items| |bag|) (RONTOLISP::%JAVA-NPE)) "
						+ "|%string0|) (LET ((|first| |%string0|)) (LET* ((|%t0| (|Bag.iterator()| (OR |bag| "
						+ "(RONTOLISP::%JAVA-NPE))))) (WHILE (|java.util.ArrayList.Each.hasNext()| |%t0|) (LET "
						+ "((|item| (|java.util.ArrayList.Each.next()| |%t0|))) (WRITE-LINE (IF (EQ |item| |first|) "
						+ "\"true\" \"false\"))))) (LET ((|ns| (MAKE-ARRAY 2 :ELEMENT-TYPE '(UNSIGNED-BYTE 32) "
						+ ":INITIAL-CONTENTS '(2 1)))) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY (UNSIGNED-BYTE 32) "
						+ "(*))) |ns|)) (|java.util.Arrays.sort(int[])| |ns|) (WRITE-LINE "
						+ "(RONTOLISP::%JAVA-DOUBLE-STRING (+ (FLOAT "
						+ "(|rontolisp.internal.Support.parseInt(String,int)| |%string1| 10) 1.0) (%STRICT-MATH :SQRT "
						+ "(FLOAT (%MASK-SIGNED-FIELD 32 (AREF |ns| 0)) 1.0)))))))))",
				"(DEFUN |java.util.Arrays.sort(int[])| (|a|) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY "
						+ "(UNSIGNED-BYTE 32) (*))) |a|)) (RONTOLISP::%JAVA-SORT-INTS |a| 0 (LENGTH (THE (SIMPLE-ARRAY "
						+ "(UNSIGNED-BYTE 32) (*)) (OR |a| (RONTOLISP::%JAVA-NPE))))))");
		// System.out as the receiver of a print is the host's stream, so the program
		// above holds no class of java.io; as a value it is an object of the library's
		// PrintStream, made by the static method the field is read through, and a printf
		// whose value is used is that object's.
		assertThat(forms).noneMatch(form -> form.contains("java.io."));
		assertThat(lower("""
				import java.io.PrintStream;
				class L5 {
				    public static void main(String[] args) {
				        System.out.println(1);
				        System.out.printf("%d%n", 2);
				        PrintStream out = System.out;
				        out.println(3);
				        System.err.printf("%d%n", 4).flush();
				    }
				}
				""", "L5.java")).contains(
				"(DEFUN |L5.main(String[])| (|args|) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY T (*))) |args|)) "
						+ "(PROGN (WRITE-LINE \"1\") (WRITE-STRING (RONTOLISP::%JAVA-FORMAT "
						+ "\"%d%n\" (VECTOR 2) #'|%string| NIL)) (LET ((|out| (|java.io.StandardOutput.out()|))) "
						+ "(|java.io.PrintStream.println(int)| (OR |out| (RONTOLISP::%JAVA-NPE)) 3) "
						+ "(|java.io.StandardOutput.flush()| (OR (|java.io.PrintStream.printf(String,Object[])| "
						+ "(OR (|java.io.StandardOutput.err()|) (RONTOLISP::%JAVA-NPE)) \"%d%n\" (VECTOR 4)) "
						+ "(RONTOLISP::%JAVA-NPE))))))",
				"(DEFUN |java.io.StandardOutput.text(String,boolean)| (|this| |s| |line|) (PROGN (WRITE-STRING |s| (IF "
						+ "(|java.io.StandardOutput.error| |this|) *ERROR-OUTPUT* *STANDARD-OUTPUT*)) (IF |line| "
						+ "(WRITE-CHAR (CODE-CHAR 10) (IF (|java.io.StandardOutput.error| |this|) *ERROR-OUTPUT* "
						+ "*STANDARD-OUTPUT*)))))");
		// The conversions a format leaves to the library are branches of
		// java.util.Conversions.more, which a format is handed only where a control can
		// name one or the program makes what one reads -- the NIL of the program above:
		// a program formatting a BigInteger holds the branch of its digits and no %t, one
		// writing a date of java.time by %t no reading of the host's zone, which only a
		// long or a Date given to a format needs.
		List<String> big = lower("""
				import java.math.BigInteger;
				class L5 {
				    public static void main(String[] args) {
				        System.out.println(String.format("%d", new BigInteger("1")));
				    }
				}
				""", "L5.java");
		assertThat(big).anyMatch(form -> form.contains("#'|%string| (LAMBDA ("))
			.anyMatch(form -> form.startsWith("(DEFUN |java.util.Conversions.more(")
					&& form.contains("(RONTOLISP::%JAVA-FORMAT-EXACT "))
			.noneMatch(form -> form.contains("java.util.Conversions.Moment"));
		assertThat(lower("""
				import java.time.LocalDate;
				class L5 {
				    public static void main(String[] args) {
				        System.out.println(String.format("%tF", LocalDate.of(2026, 11, 21)));
				    }
				}
				""", "L5.java")).anyMatch(form -> form.startsWith("(DEFUN |java.util.Conversions.Accessor.get("))
			.noneMatch(form -> form.contains("rontolisp.internal.Dates.fields("));
		// A charset named by a constant is the charset: the member taking a Charset is
		// called, so a program naming UTF-8 holds no other charset's codec. A name
		// computed where the program runs is looked up among all of them.
		String named = """
				import java.io.*;
				import java.nio.charset.Charset;
				class L6 {
				    public static void main(String[] args) throws IOException {
				        Reader in = new InputStreamReader(System.in, "UTF-8");
				        byte[] bytes = "x".getBytes("utf-8");
				        System.out.println(new String(bytes, "UTF-8") + Charset.forName("UTF-8") + in.read() + %s);
				    }
				}
				""";
		assertThat(lower(named.formatted("1"), "L6.java")).noneMatch(form -> form.contains("Utf16"))
			.anyMatch(
					form -> form.contains("(|rontolisp.internal.Support.encode(String,java.nio.charset.Charset)| \"x\" "
							+ "(|java.nio.charset.Charset.utf8()|))"));
		assertThat(lower(named.formatted("Charset.forName(args[0])"), "L6.java"))
			.anyMatch(form -> form.contains("rontolisp.internal.Utf16.bytes(String)"));
		// A digest or a Mac named by a constant is its algorithm's class the same way, so
		// a program naming SHA-256 holds no other digest; a name computed where the
		// program runs is looked up among all of them.
		String digested = """
				import java.security.MessageDigest;
				import javax.crypto.Mac;
				class L8 {
				    public static void main(String[] args) throws Exception {
				        System.out.println(MessageDigest.getInstance("sha-256").getDigestLength() + Mac.getInstance(%s).getMacLength());
				    }
				}
				""";
		assertThat(lower(digested.formatted("\"HmacSHA256\""), "L8.java"))
			.anyMatch(form -> form.contains("(|rontolisp.internal.Sha256.of(String,int)| \"sha-256\" 3)"))
			.noneMatch(form -> form.contains("rontolisp.internal.Md5") || form.contains("rontolisp.internal.Sha1.")
					|| form.contains("rontolisp.internal.Sha512") || form.contains("rontolisp.internal.Sha3")
					|| form.contains("rontolisp.internal.Md2"));
		assertThat(lower(digested.formatted("\"HmacSHA3-256\""), "L8.java"))
			.anyMatch(form -> form.contains("(|rontolisp.internal.Sha3.of(String,int)| \"HmacSHA3-256\" 10)"));
		assertThat(lower(digested.formatted("args[0]"), "L8.java"))
			.anyMatch(form -> form.startsWith("(DEFSTRUCT (|rontolisp.internal.Md5| "))
			.anyMatch(form -> form.startsWith("(DEFSTRUCT (|rontolisp.internal.Sha512| "))
			.anyMatch(form -> form.startsWith("(DEFSTRUCT (|rontolisp.internal.Sha3| "))
			.anyMatch(form -> form.startsWith("(DEFSTRUCT (|rontolisp.internal.Md2| "));
		// An EnumSet or an EnumMap of an enum its call names -- by a call, a reference,
		// a creation or a class under the map -- takes the constants of that enum alone:
		// another enum whose class object the program holds has no values(). One whose
		// enum the call names by a type variable takes them from every enum's class
		// object, as getEnumConstants does.
		String sets = """
				import java.util.*;
				import java.util.function.Function;
				enum A { X } enum B { X } enum C { X } enum D { X }
				class L15 {
				    static <E extends Enum<E>> EnumSet<E> none(Class<E> type) { return %s; }
				    public static void main(String[] args) {
				        Function<Class<A>, EnumSet<A>> made = EnumSet::noneOf;
				        Object held = D.X;
				        System.out.println(made.apply(A.class) + " " + new EnumMap<B, String>(B.class) + " "
				                + new EnumMap<C, String>(C.class) { } + held.getClass() + none(A.class));
				    }
				}
				""";
		assertThat(lower(sets.formatted("null"), "L15.java")).anyMatch(form -> form.startsWith("(DEFUN |A.values()|"))
			.anyMatch(form -> form.startsWith("(DEFUN |B.values()|"))
			.anyMatch(form -> form.startsWith("(DEFUN |C.values()|"))
			.noneMatch(form -> form.startsWith("(DEFUN |D.values()|"));
		assertThat(lower(sets.formatted("EnumSet.noneOf(type)"), "L15.java"))
			.anyMatch(form -> form.startsWith("(DEFUN |D.values()|"));
		// A pool's afterExecute is handed what a task threw, which takes a catch of
		// every exception around each task: the class holding it is made only in a
		// program one of whose classes declares a hook.
		String pool = """
				import java.util.concurrent.*;
				class L7 {
				    public static void main(String[] args) {
				        new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) %s
				            .execute(() -> { });
				    }
				}
				""";
		assertThat(lower(pool.formatted(""), "L7.java")).noneMatch(form -> form.contains("ThreadPoolExecutor.Hooked"));
		// A formatter's ISO form is made only in a program that reads it; what a
		// formatter reads is a date of another class, which the classes of java.time
		// read through its fields -- as they read a class of the program's own -- only
		// in a program that can hold one.
		String formatted = """
				import java.time.*;
				import java.time.format.DateTimeFormatter;
				class L14 {
				    public static void main(String[] args) {
				        System.out.println(LocalDate.from(LocalDateTime.MIN)%s);
				    }
				}
				""";
		assertThat(
				lower(formatted.formatted(" + LocalDate.parse(args[0], DateTimeFormatter.ISO_LOCAL_DATE).toString()"),
						"L14.java"))
			.anyMatch(form -> form.contains("(SETQ |java.time.format.DateTimeFormatter.ISO_LOCAL_DATE| "
					+ "(|java.time.format.DateTimeFormatterBuilder.formatter("))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.DateTimeFormatter.<clinit>| ")
					&& form.contains("(SETQ |java.time.format.DateTimeFormatter.ISO_INSTANT| NIL)"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.LocalDate.from(")
					&& form.contains("TemporalQueries.localDate()"));
		assertThat(lower(formatted.formatted(""), "L14.java"))
			.noneMatch(form -> form.contains("TemporalQueries") || form.contains("java.time.format."));
		// The letters of weeks, quarters, the modified Julian day, a zone's name in words
		// and a day's period make their parts only in a program whose patterns can name
		// them -- a constant pattern names the letters outside its quotes, a computed one
		// all of them, a localized form the zone's name where its time can be full or
		// long
		// --; a field that is no ChronoField is resolved only in a program that makes
		// one,
		// and a value is asked its calendar only in a program that reaches the question.
		String patterned = """
				import java.time.format.DateTimeFormatter;
				import java.time.temporal.TemporalQueries;
				class L15 {
				    public static void main(String[] args) {
				        System.out.println(DateTimeFormatter.ofPattern(%s).parse("2026Y")%s);
				    }
				}
				""";
		// The same for an offset as GMT and its hours (O, ZZZZ), words, a year of two
		// digits, a fraction, a zone's id, an offset, a pad and an optional section,
		// which a localized form's patterns may hold too; and a formatter's decimal
		// style, the fields it resolves and resolving leniently are in a program that
		// reaches withDecimalStyle, withResolverFields and withResolverStyle, the last
		// with a style that is no read of STRICT or SMART.
		for (String strict : List.of("", ").withResolverStyle(java.time.format.ResolverStyle.STRICT")) {
			assertThat(lower(patterned.formatted("\"uuuu'Y'\"" + strict, ""), "L15.java"))
				.noneMatch(form -> form.contains("WeekFields") || form.contains("IsoFields")
						|| form.contains("JulianFields") || form.contains("Parsed.resolveOthers")
						|| form.contains("IsoChronology") || form.contains("ZoneNames")
						|| form.contains("Step.DayPeriod") || form.contains("OfPeriod()|")
						|| form.contains("Step.GmtOffset") || form.contains("getZeroDigit")
						|| form.contains("Parsed.among")
						|| form.startsWith("(DEFUN |java.time.format.Parsed.weekDay(")
								&& form.contains("java.time.DayOfWeek.of(int)")
						|| form.startsWith("(DEFUN |java.time.format.Parsed.resolveTime()| ")
								&& form.contains("ofNanoOfDay")
						|| form.contains("Step.Words") || form.contains("Step.Reduced")
						|| form.contains("Step.Fraction") || form.contains("Step.ZoneName")
						|| form.contains("Step.Offset") || form.contains("padNext")
						|| form.startsWith("(DEFUN |java.time.format.DateTimeFormatterBuilder.optionalStart()| "));
		}
		assertThat(lower(patterned.formatted("args[0]", ".query(TemporalQueries.chronology())"), "L15.java"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.temporal.WeekFields.of(java.time.DayOfWeek,int)| "))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Parsed.resolveOthers()| "))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Parsed.query(")
					&& form.contains("java.time.chrono.IsoChronology.INSTANCE"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.ZoneNames.read(java.time.format.Reading,"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Parsed.middleOfPeriod()| "))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Step.GmtOffset.parse("))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Step.Words.parse("))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Step.Fraction.parse("));
		assertThat(lower(patterned.formatted("\"uuuu'Y'\").withDecimalStyle(java.time.format.DecimalStyle.STANDARD)"
				+ ".withResolverFields(java.time.temporal.ChronoField.YEAR)"
				+ ".withResolverStyle(java.time.format.ResolverStyle.LENIENT", ""), "L15.java"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Reading.numeral(int)| ")
					&& form.contains("getZeroDigit"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Parsed.resolve(")
					&& form.contains("Parsed.among"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Parsed.weekDay(")
					&& form.contains("java.time.DayOfWeek.of(int)"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Parsed.resolveTime()| ")
					&& form.contains("ofNanoOfDay"));
		// A pattern a static or a private method is given names the letters of what
		// every call passes it (Y a week's), through a lambda that captures it too; a
		// method reached otherwise (a reference) or that assigns it names all of them.
		String passed = """
				import java.time.format.DateTimeFormatter;
				import java.util.function.BiFunction;
				class L19 {
				    static Object read(String pattern, String text) { %sreturn passOn(pattern).apply(text); }
				    private static java.util.function.Function<String, Object> passOn(String pattern) {
				        return text -> DateTimeFormatter.ofPattern(pattern).parse(text);
				    }
				    public static void main(String[] args) {
				        System.out.println(read("uuuu'Y'", "2026Y"));%s
				    }
				}
				""";
		assertThat(lower(passed.formatted("", ""), "L19.java")).noneMatch(form -> form.contains("WeekFields"));
		for (List<String> otherwise : List.of(List.of("", " read(\"YYYY\", \"2026\");"),
				List.of("", " BiFunction<String, String, Object> f = L19::read;"),
				List.of("pattern = pattern.trim(); ", ""))) {
			assertThat(lower(passed.formatted(otherwise.get(0), otherwise.get(1)), "L19.java")).anyMatch(
					form -> form.startsWith("(DEFUN |java.time.temporal.WeekFields.of(java.time.DayOfWeek,int)| "));
		}
		String localized = """
				import java.time.LocalTime;
				import java.time.format.*;
				class L16 {
				    public static void main(String[] args) {
				        System.out.println(DateTimeFormatter.ofLocalizedTime(FormatStyle.%s).format(LocalTime.NOON));
				    }
				}
				""";
		assertThat(lower(localized.formatted("MEDIUM"), "L16.java"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Step.Styled.parts(int)| "))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Step.Words.print("))
			.noneMatch(form -> form.contains("ZoneNames"));
		assertThat(lower(localized.formatted("LONG"), "L16.java"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.ZoneNames.name("));
		// A requested template names the letters its patterns can hold, a computed one
		// all of them; a calendar of no type is refused only in a program with one of its
		// own.
		String templated = """
				import java.time.LocalDate;
				import java.time.format.*;
				class L17 {
				    public static void main(String[] args) {
				        System.out.println(DateTimeFormatter.ofLocalizedPattern(%s).format(LocalDate.MIN));
				    }
				}
				""";
		assertThat(lower(templated.formatted("\"yMMMMEEEEdjm\""), "L17.java"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Step.Templated.parts(int,java.util.Locale)| "))
			.noneMatch(form -> form.contains("WeekFields") || form.contains("IsoFields") || form.contains("ZoneNames")
					|| form.contains("Step.DayPeriod") || form.contains("Texts.typed")
					|| form.contains("DateTimeFormatter.calendared") || form.contains("Parsed.dates()")
					|| form.contains("Texts.eraWords"));
		// A value is written in the formatter's calendar, and a text read in
		// it, only in a program with a calendar of its own; the calendar's
		// zoned date-time is made where it writes only in one that can give a
		// formatter a zone, and is where it reads.
		String calendared = """
				import java.time.LocalDate;
				import java.time.chrono.*;
				import java.time.format.DateTimeFormatter;
				import java.time.temporal.*;
				import java.util.List;
				class L18 {
				    static final class Own extends AbstractChronology {
				        public String getId() { return "Own"; }
				        public String getCalendarType() { return "own"; }
				        public ChronoLocalDate date(int y, int m, int d) { return LocalDate.of(y, m, d); }
				        public ChronoLocalDate dateYearDay(int y, int d) { return LocalDate.ofYearDay(y, d); }
				        public ChronoLocalDate dateEpochDay(long d) { return LocalDate.ofEpochDay(d); }
				        public ChronoLocalDate date(TemporalAccessor t) { return LocalDate.from(t); }
				        public boolean isLeapYear(long y) { return IsoChronology.INSTANCE.isLeapYear(y); }
				        public int prolepticYear(Era era, int y) { return IsoChronology.INSTANCE.prolepticYear(era, y); }
				        public Era eraOf(int era) { return IsoEra.of(era); }
				        public List<Era> eras() { return List.of(IsoEra.values()); }
				        public ValueRange range(ChronoField field) { return field.range(); }
				    }
				    public static void main(String[] args) {
				        DateTimeFormatter written = DateTimeFormatter.ofPattern("uuuu G").withChronology(new Own())%s;
				        System.out.println(written.format(LocalDate.MIN)%s);
				    }
				}
				""";
		assertThat(lower(calendared.formatted("", ""), "L18.java"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.DateTimeFormatter.calendared(")
					&& !form.contains("zonedDateTime"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Texts.eraWords("))
			.noneMatch(form -> form.contains("ChronoZonedDateTimeImpl") || form.contains("Parsed.dates()"));
		assertThat(lower(calendared.formatted(".withZone(java.time.ZoneOffset.UTC)", " + written.parse(\"2026 1\")"),
				"L18.java"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.DateTimeFormatter.calendared(")
					&& form.contains("zonedDateTime"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.chrono.ChronoZonedDateTimeImpl.ofInstant("))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Parsed.dates()| "));
		assertThat(lower(templated.formatted("args[0]"), "L17.java"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.temporal.WeekFields.of(java.time.DayOfWeek,int)| "))
			.anyMatch(form -> form.contains("IsoFields.QUARTER_OF_YEAR"))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.ZoneNames.name("))
			.anyMatch(form -> form.startsWith("(DEFUN |java.time.format.Step.DayPeriod.print("))
			.noneMatch(form -> form.contains("Texts.typed"));
		assertThat(lower(pool.formatted("{ protected void afterExecute(Runnable r, Throwable t) { } }"), "L7.java"))
			.anyMatch(form -> form.contains("(DEFUN |java.util.concurrent.ThreadPoolExecutor.hooked()| NIL "
					+ "(|java.util.concurrent.ThreadPoolExecutor.Hooked.new()|))"));
		// A request of java.net.http is the program's own call of rontolisp:fetch -- the
		// transport splices and a target with none read a program that fetches --, its
		// future held in the run time's exchange, never a value of the program; a URI is
		// cut by the rules the front ends share. Where a thread is a body suspended and
		// resumed, the wait for a reply is one more suspension and the exchange is one
		// the run time reads while the threads take turns. Fetch is handed its two
		// timeouts
		// only by a program that gives a request or a client a time limit, so only such
		// a program names the keywords a transport carries its timeout arm for.
		String client = """
				import java.net.URI;
				import java.net.http.*;
				class L8 {
				    public static void main(String[] args) throws Exception {
				        HttpClient client = HttpClient.newHttpClient();
				        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/"))%s.build();
				        %s
				    }
				}
				""";
		String blocking = "System.out.println(client.send(request, HttpResponse.BodyHandlers.ofString()).body());";
		assertThat(lower(client.formatted("", blocking), "L8.java"))
			.anyMatch(form -> form
				.contains("(HANDLER-CASE (RONTOLISP::%JAVA-HTTP-EXCHANGE (RONTOLISP:FETCH |url| (LIST :METHOD |method| "
						+ ":HEADERS (RONTOLISP::%JAVA-HTTP-FIELDS |fields|) :BODY |body|))) (ERROR "))
			.anyMatch(form -> form.contains("(RONTOLISP::%URI-REFUSAL |str|)"))
			.anyMatch(form -> form.contains("(RONTOLISP::%JAVA-HTTP-REPLY |exchange|)"))
			.noneMatch(form -> form.contains("%JAVA-SUSPENDING") || form.contains("%JAVA-HTTP-LAUNCHED")
					|| form.contains("-TIMEOUT"));
		assertThat(lower(client.formatted(".timeout(java.time.Duration.ofSeconds(5))", blocking), "L8.java"))
			.anyMatch(form -> form.contains(":BODY |body| :CONNECT-TIMEOUT (AND (> ")
					&& form.contains(" :FIRST-BYTE-TIMEOUT (AND (> "));
		String async = "System.out.println(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join().body());";
		assertThat(Java.read(client.formatted("", async), "L8.java", JavaFiles.NONE, null, true)
			.stream()
			.map(LispVal::print)
			.toList())
			.anyMatch(form -> form.contains("(RONTOLISP:AWAIT (RONTOLISP::%JAVA-SUSPENDING-HTTP-REPLY |exchange|))"))
			.anyMatch(form -> form.contains("(HANDLER-CASE (RONTOLISP::%JAVA-HTTP-LAUNCHED (RONTOLISP:FETCH |url| "))
			.noneMatch(form -> form.contains("%JAVA-HTTP-EXCHANGE"));
		// A server of com.sun.net.httpserver is the program's own call of the transport
		// every adapter serves through, handed the function that answers a request; a
		// monitor is held with no Thread in the program, since the handlers run beside
		// the thread it started on. Where the host has threads the program's end waits
		// for the server; on one stack a request is a thread of the program's own
		// scheduler, which serves where no thread can go on: the entry is the
		// scheduler's, nothing follows main, and the request's thread waits for nothing
		// -- the exchange outlives its handler, so what hands it over is no resumable
		// function.
		String server = """
				import com.sun.net.httpserver.HttpServer;
				import java.net.InetSocketAddress;
				class L9 {
				    static int hits;
				    static synchronized int hit() { return ++hits; }
				    public static void main(String[] args) throws Exception {
				        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
				        server.createContext("/", exchange -> exchange.sendResponseHeaders(200 + hit(), -1));
				        server.start();
				    }
				}
				""";
		assertThat(lower(server, "L9.java"))
			.anyMatch(form -> form.contains("(HANDLER-CASE (RONTOLISP::%HTTP-SERVE-BEGIN (LAMBDA (")
					&& form.contains("(RONTOLISP::%JAVA-HTTP-REQUEST (LAMBDA (")
					&& form.contains(") (|rontolisp.internal.HttpServing.answer(Object,Object,Object)| "))
			.anyMatch(
					form -> form.contains("(|L9.main(String[])| (VECTOR)) (|rontolisp.internal.HttpServing.serve()|)"))
			.anyMatch(form -> form
				.startsWith("(DEFUN |L9.hit()| NIL (LET ((|%t0| (RONTOLISP::%JAVA-MONITOR-ENTER '|j%L9|)))"));
		assertThat(Java.read(server, "L9.java", JavaFiles.NONE, null, true).stream().map(LispVal::print).toList())
			.anyMatch(form -> form.contains("(RONTOLISP::%HTTP-SERVE-BEGIN "))
			.anyMatch(form -> form.startsWith("(RONTOLISP::%JAVA-THREAD-MAIN (LAMBDA NIL ")
					&& form.contains("(|L9.main(String[])| (VECTOR)))"))
			.anyMatch(
					form -> form.startsWith("(DEFUN |rontolisp.internal.HttpServing.Server.dispatch(Object,Object)| "))
			.anyMatch(form -> form.startsWith("(DEFUN |L9.hit()|") && form.contains("%JAVA-MONITOR-ENTER"))
			.noneMatch(form -> form.contains("HttpServing.serve()"));
		// A reactor, which a host calls, has no process to end: the exit a Java program
		// ends through -- what main left uncaught, System.exit -- is there the trap
		// that ends the instance, and a program that is none is refused its quit.
		List<LispVal> served = JavaLibrary.process(Java.read(server, "L9.java", JavaFiles.NONE, null, true),
				Features.WASM_REACTOR);
		assertThat(ExitLibrary.process(served, WitExportDirective.Backend.WASM_GC, Features.WASM_REACTOR)
			.stream()
			.map(LispVal::print))
			.contains("(DEFUN %HOST-EXIT (%HE-CODE) (DECLARE (IGNORE %HE-CODE)) (RONTOLISP::%TRAP))");
		assertThatThrownBy(() -> ExitLibrary.process(
				List.of(new LispCons(new LispSymbol("%HOST-EXIT"), am.ik.rontolisp.LispNil.INSTANCE)),
				WitExportDirective.Backend.WASM_GC, Features.WASM_REACTOR))
			.hasMessageContaining("Return from the export instead");
		assertThat(forms).noneMatch(
				form -> form.contains("RONTOLISP:FETCH") || form.contains("%URI-") || form.contains("%HTTP-SERVE"));
		// A collection's stream() is a member like any other: the program above, which
		// calls none, holds no class of the streams. A stream's stage is a function the
		// stage after it calls for the next element -- a funcall, no choice among
		// classes -- and a call of a stream's member is direct, one class being behind
		// each kind of stream; a collection tells how many elements it holds through a
		// function count() asks.
		assertThat(forms).noneMatch(form -> form.contains("java.util.stream.") || form.contains("Flow"));
		assertThat(lower("""
				import java.util.*;
				class L12 {
				    public static void main(String[] args) {
				        List<String> words = new ArrayList<>();
				        System.out.println(words.stream().filter(w -> w.isEmpty()).count());
				    }
				}
				""", "L12.java"))
			.contains("(DEFUN |java.util.Collection.stream()| (|this|) (|rontolisp.internal.ObjectFlow.over(Iterable,"
					+ "rontolisp.internal.Flow.Count)| |this| (LAMBDA NIL (|java.util.ArrayList.size()| |this|))))")
			.anyMatch(form -> form.startsWith("(DEFUN |L12.main(String[])| ")
					&& form.contains("(|rontolisp.internal.ObjectFlow.count()| (OR (|rontolisp.internal.ObjectFlow."
							+ "filter(java.util.function.Predicate)| (OR (|java.util.Collection.stream()| "))
			.anyMatch(form -> form
				.startsWith("(DEFUN |rontolisp.internal.ObjectFlow.filter(java.util.function.Predicate)| ")
					&& form.contains("(LAMBDA NIL ") && form.contains("(FUNCALL (OR |pull| (RONTOLISP::%JAVA-NPE)))"))
			.noneMatch(form -> form.contains("Pull.next()") || form.contains("IntFlow"));
		// Number's values are Lisp values, so a member called through it is the run
		// time's form -- until a class of Java source stands under Number (an atomic, a
		// program's own): then a function chooses the body by the receiver's class,
		// its last arm that form, a member Number has a body for narrows what that
		// function answers, instanceof asks the classes' predicates too and Object's
		// methods go through the program's.
		String numbers = """
				class L10 {
				    static class Half %s {
				        public int intValue() { return 1; }
				        public long longValue() { return 1; }
				        public float floatValue() { return 0.5f; }
				        public double doubleValue() { return 0.5; }
				        public short shortValue() { return 7; }
				    }
				    static String show(Object o) { return o instanceof Number n ? n.intValue() + " " + n.shortValue() + n : ""; }
				    public static void main(String[] args) { System.out.println(show(%s)); }
				}
				""";
		assertThat(lower(numbers.formatted("", "1"), "L10.java")).anyMatch(form -> form
			.startsWith("(DEFUN |L10.show(Object)| (|o|) (LET ((|n| NIL)) (IF "
					+ "(RONTOLISP::%JAVA-NUMBER-P |o|) (PROGN (SETQ |n| |o|) ")
				&& form.contains("(%DECIMAL-PIECE (RONTOLISP::%JAVA-NUMBER-INT (OR |n| (RONTOLISP::%JAVA-NPE))))")
				&& form.contains("(RONTOLISP::%JAVA-TO-STRING |n|)"))
			.noneMatch(form -> form.contains("|Number"));
		assertThat(lower(numbers.formatted("extends Number", "new Half()"), "L10.java")).contains(
				"(DEFUN |L10.show(Object)| (|o|) (LET ((|n| NIL)) (IF (|Number%is| |o|) (PROGN (SETQ |n| |o|) "
						+ "(%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT (%DECIMAL-PIECE (|Number.intValue()%v| "
						+ "(OR |n| (RONTOLISP::%JAVA-NPE)))) \" \") (%DECIMAL-PIECE (|Number.shortValue()%v| (OR |n| "
						+ "(RONTOLISP::%JAVA-NPE))))) (|%string| |n|))) \"\")))",
				"(DEFUN |Number.intValue()%v| (|%o|) (COND ((|L10.Half?| |%o|) (|L10.Half.intValue()| |%o|)) "
						+ "(T (RONTOLISP::%JAVA-NUMBER-INT |%o|))))",
				"(DEFUN |Number.shortValue()%v| (|%o|) (COND ((|L10.Half?| |%o|) (|L10.Half.shortValue()| |%o|)) "
						+ "(T (%MASK-SIGNED-FIELD 16 (|Number.intValue()%v| |%o|)))))",
				"(DEFUN |Number%is| (|%o|) (OR (|L10.Half?| |%o|) (RONTOLISP::%JAVA-NUMBER-P |%o|)))");
		// newUpdater of a field updater is lowered where it is called, of a class
		// literal and a constant name: the updater made holds the field's name -- its
		// accessor's symbol -- and the program's function swaps the field of a name
		// under the lock of
		// every volatile access; a field a JDK refuses when the call runs is its
		// exception thrown there.
		assertThat(
				lower("""
						import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
						class L11 {
						    volatile int hits;
						    static final AtomicIntegerFieldUpdater<L11> HITS = AtomicIntegerFieldUpdater.newUpdater(L11.class, "hits");
						    public static void main(String[] args) {
						        Thread.yield();
						        System.out.println(HITS.compareAndSet(new L11(), 0, 1));
						        AtomicIntegerFieldUpdater.newUpdater(L11.class, "none");
						    }
						}
						""",
						"L11.java"))
			.contains(
					"(DEFUN |%field-swap| (|%n| |%o| |%a0| |%a1|) (CASE |%n| (|L11.hits| (IF (|j%L11?| |%o|) (RONTOLISP:WITH-MUTEX "
							+ "((RONTOLISP::%JAVA-VOLATILE)) (IF (EQL (|L11.hits| |%o|) |%a0|) (PROGN (SETF (|L11.hits| |%o|) "
							+ "|%a1|) T) NIL)) (RONTOLISP::%JAVA-THROW (|ClassCastException.new()|) "
							+ "#'|Throwable.toString()|)))))")
			.anyMatch(form -> form.contains(
					"(SETQ |j%L11.HITS| (|java.util.concurrent.atomic.AtomicIntegerFieldUpdater.of(Object)| '|L11.hits|))"))
			.anyMatch(form -> form.startsWith("(DEFUN |L11.main(String[])| ")
					&& form.endsWith("(|java.util.concurrent.atomic.Fields.missing(String)| \"none\")))"));
		// A class read as a value is one object a type, made before anything of the
		// program runs with what the program asks of one -- here its names and the
		// class above it, and no test of an instance --; getClass() as a value is the
		// function choosing the object by what the value is; two classes compared, and
		// a class asked its name, are their names and make no object.
		List<String> classes = lower("""
				class Kept { }
				class L12 {
				    static String above(Class<?> type) { return type.getSuperclass().getName(); }
				    public static void main(String[] args) {
				        Kept kept = new Kept();
				        Class<?> held = kept.getClass();
				        System.out.println(above(held) + (held == Kept.class) + (kept.getClass() == Kept.class));
				    }
				}
				""", "L12.java");
		String made = "(|Class.new(String,String,String,int,Class,Class[],Class,Object,Object)| ";
		assertThat(classes).contains("(DEFVAR |Kept%class| NIL)",
				"(DEFUN |Kept%class-of| (|%o|) (COND ((|Kept?| |%o|) |Kept%class|) (T (RONTOLISP::%JAVA-NO-CLASS |%o|))))",
				"(DEFUN |L12.main(String[])| (|args|) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY T (*))) |args|)) "
						+ "(LET ((|kept| (|Kept.new()|))) (LET ((|held| (|Kept%class-of| "
						+ "|kept|))) (WRITE-LINE (%STRING-CONCAT (%STRING-CONCAT (OR (|L12.above(Class)| |held|) \"null\") "
						+ "(IF (EQ |held| |Kept%class|) \"true\" \"false\")) (IF (EQUAL (|%class-name| |kept|) \"Kept\") "
						+ "\"true\" \"false\"))))))")
			.containsSubsequence(
					"(SETQ |Object%class| " + made + "\"java.lang.Object\" \"Object\" NIL 0 NIL NIL NIL NIL NIL))",
					"(SETQ |Kept%class| " + made + "\"Kept\" \"Kept\" NIL 0 |Object%class| NIL NIL NIL NIL))",
					"(%REPORTING-UNCAUGHT #'RONTOLISP::%JAVA-UNCAUGHT-LINE (%HOST-EXIT 1) (|L12.main(String[])| "
							+ "(VECTOR)))");
		assertThat(lower("""
				class L13 {
				    public static void main(String[] args) {
				        Object o = "text";
				        System.out.println(o.getClass().getName() + (o.getClass() == String.class));
				    }
				}
				""", "L13.java")).noneMatch(form -> form.contains("%class|") || form.contains("|Class."));
		// A class of the JDK whose object the program holds answers a lookup by a name
		// computed where it runs from its line of a text of the JDK's table, found by the
		// place of its class object, each member's flags its modifiers and a letter of
		// why
		// it may not be called: one a call of the program's reaches is called through the
		// number of its use (length), one it calls nowhere is refused where it runs
		// (isBlank: a), one the library lacks too (l); the type a member answers is a
		// class object, whose own members are not answered. Reflection's own classes
		// answer none of theirs.
		List<String> reflecting = lower("""
				class L14 {
				    public static void main(String[] args) throws Exception {
				        String name = args.length > 0 ? args[0] : "length";
				        System.out.println("text".length() + " " + String.class.getDeclaredMethod(name).getReturnType()
				                + Class.class.getDeclaredMethod(name));
				    }
				}
				""", "L14.java");
		assertThat(reflecting).contains("(DEFUN |%class-method| (|%o| |%n|) "
				+ "(|java.lang.reflect.Method.jdkNamed(Class,String,String,Object[])| |%o| |%n| |%jdk-named-methods| "
				+ "|%jdk-types|))");
		String text = reflecting.stream()
			.filter(form -> form.startsWith("(SETQ |%jdk-named-methods| \""))
			.findFirst()
			.orElseThrow();
		String types = reflecting.stream()
			.filter(form -> form.startsWith("(SETQ |%jdk-types| (VECTOR "))
			.findFirst()
			.orElseThrow();
		List<String> placed = List.of(types.substring(types.indexOf("(VECTOR ") + 8, types.indexOf(")")).split(" "));
		String line = text.split("\n")[placed.indexOf("|String%class|")];
		assertThat(line).startsWith("+0|chars 1a 0 1;")
			.contains(";describeConstable 1l ", ";isBlank 1a ", ";length 1u1 ")
			.doesNotContain("\n");
		assertThat(placed).doesNotContain("|Class%class|").contains("|java.util.stream.Stream%class|");
		List<String> lines = List.of(text.split("\n", -1));
		int stream = placed.indexOf("|java.util.stream.Stream%class|");
		assertThat(stream >= lines.size() || lines.get(stream).isEmpty()).isTrue();
		assertThat(reflecting)
			.contains(
					"(DEFUN |%jdk-methods| (|%k| |%v| |%a|) (COND ((EQL |%k| 0) (IF (= (LENGTH |%v|) 0) T :FALSE)) "
							+ "((EQL |%k| 1) (LENGTH |%v|))))",
					"(DEFUN |%jdk-tests| (|%k| |%v|) (COND ((EQL |%k| 0) (SIMPLE-STRING-P |%v|))))")
			.noneMatch(form -> form.startsWith("(SETQ |%jdk-named-fields| "));
	}

	@Test
	void theLibrarysSourceIsJavaAJdkCompiles() throws IOException {
		// The library is read by this front end only, which takes more than the language
		// does (an access across packages, a checked exception nothing declares): the
		// compiler of the JDK running the test compiles every file of it, as a patch of
		// its own java.base, with no error. A native member is Java too. The runner of
		// tests names JUnit Jupiter's classes, which a run of tests reads from the
		// program's source path: here they are JUnit's jars of this test's class path,
		// which the patch reads and nothing else.
		List<String> jars = List.of("junit-jupiter-api-", "junit-jupiter-params-", "junit-platform-commons-",
				"apiguardian-api-");
		String junit = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
			.filter(entry -> jars.stream().anyMatch(Path.of(entry).getFileName().toString()::startsWith))
			.collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
		Path library = Path.of("src/main/resources/am/ik/rontolisp/java/library");
		List<Path> sources;
		try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(library)) {
			sources = files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
		}
		assertThat(sources).hasSizeGreaterThan(130);
		javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
		javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> reported = new javax.tools.DiagnosticCollector<>();
		Path classes = java.nio.file.Files.createTempDirectory("java-library");
		try (javax.tools.StandardJavaFileManager files = compiler.getStandardFileManager(reported, null,
				StandardCharsets.UTF_8)) {
			compiler.getTask(null, files, reported,
					// java.net.http and java.sql are packages of modules of their own:
					// with
					// the modules limited to java.base each is one of the patch, as every
					// other
					List.of("--limit-modules", "java.base", "--patch-module", "java.base=" + library, "--add-reads",
							"java.base=ALL-UNNAMED", "-classpath", junit, "-d", classes.toString(), "-proc:none",
							"-Xlint:none", "-Xmaxerrs", "1000"),
					null, files.getJavaFileObjectsFromPaths(sources))
				.call();
		}
		finally {
			try (java.util.stream.Stream<Path> written = java.nio.file.Files.walk(classes)) {
				written.sorted(java.util.Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
			}
		}
		assertThat(reported.getDiagnostics()
			.stream()
			.filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)
			.map(diagnostic -> diagnostic.toString())
			.toList()).isEmpty();
		// That compile holds the library's throws clauses to each other, not to the
		// API's: a member declaring a checked exception the API's does not would refuse a
		// valid program, and one declaring none would refuse the catch of it. So every
		// public member of the running JDK's class of the name is asked, by reflection,
		// which checked exceptions it throws, and the member a call of it reaches here
		// has to declare the same.
		JavaClasses table = new JavaClasses(JavaIntrinsics.LIBRARY, JavaFiles.NONE, null, "", JavaTypes::new);
		List<String> names = new ArrayList<>(List.of("java.lang.Object", "java.lang.String", "java.lang.StringBuilder",
				"java.lang.StringBuffer", "java.lang.Math", "java.lang.StrictMath", "java.lang.Number",
				"java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float", "java.lang.Byte",
				"java.lang.Short", "java.lang.Boolean", "java.lang.Character", "java.lang.System",
				"java.nio.charset.StandardCharsets", "java.lang.IO"));
		for (Path source : sources) {
			String name = library.relativize(source).toString().replace('\\', '/');
			name = name.substring(0, name.length() - ".java".length()).replace('/', '.');
			// a class a JDK has: neither the library's own nor a test library's part
			if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("com.sun.")) {
				names.add(name);
			}
		}
		List<JavaClass> all = new ArrayList<>();
		for (String name : names) {
			nested(table.library(name), all);
		}
		List<String> differing = new ArrayList<>();
		int compared = 0;
		for (JavaClass ours : all) {
			Class<?> theirs;
			try {
				theirs = Class.forName(ours.binaryName, false, ClassLoader.getPlatformClassLoader());
			}
			catch (ClassNotFoundException ex) {
				// a class of the library's own
				continue;
			}
			List<java.lang.reflect.Executable> members = new ArrayList<>(List.of(theirs.getMethods()));
			members.addAll(List.of(theirs.getConstructors()));
			for (java.lang.reflect.Executable member : members) {
				if (member.isSynthetic() || (member instanceof java.lang.reflect.Method method && method.isBridge())) {
					continue;
				}
				String name = member instanceof java.lang.reflect.Method ? member.getName() : "<init>";
				List<String> parameters = java.util.Arrays.stream(member.getParameterTypes())
					.map(JavaLoweringTest::erased)
					.toList();
				JavaClass.Method found = member(ours, name, parameters, new java.util.HashSet<>());
				if (found == null) {
					continue;
				}
				compared++;
				java.util.Set<String> expected = new java.util.TreeSet<>();
				for (Class<?> thrown : member.getExceptionTypes()) {
					if (isChecked(thrown)) {
						expected.add(thrown.getName());
					}
				}
				java.util.Set<String> declared = new java.util.TreeSet<>(found.raises);
				for (JavaType thrown : found.thrown) {
					// a type variable is thrown as its bound, which is what the JDK
					// answers
					JavaType erased = thrown instanceof JavaType.Variable variable ? variable.parameter().bounds.get(0)
							: thrown;
					declared.add(((JavaType.ClassType) erased).declaration().binaryName);
				}
				declared.removeIf(thrown -> {
					try {
						return !isChecked(Class.forName(thrown, false, ClassLoader.getPlatformClassLoader()));
					}
					catch (ClassNotFoundException ex) {
						return false;
					}
				});
				if (!declared.equals(expected)) {
					differing.add(ours.name + "." + name + parameters + " throws " + declared + ", the JDK's "
							+ expected + " (found in " + found.owner.name + ")");
				}
			}
		}
		assertThat(compared).isGreaterThan(2000);
		assertThat(differing).isEmpty();
	}

	private static void nested(JavaClass declaration, List<JavaClass> into) {
		if (!into.contains(declaration)) {
			into.add(declaration);
			declaration.members.values().forEach(member -> nested(member, into));
		}
	}

	private static boolean isChecked(Class<?> thrown) {
		return !RuntimeException.class.isAssignableFrom(thrown) && !Error.class.isAssignableFrom(thrown);
	}

	/** A JDK type as the front end spells the erasure of a parameter's type. */
	private static String erased(Class<?> type) {
		if (type.isArray()) {
			return erased(type.getComponentType()) + "[]";
		}
		String name = Objects.requireNonNull(type.isPrimitive() ? type.getName() : type.getCanonicalName());
		return name.startsWith("java.lang.") && name.indexOf('.', 10) < 0 ? name.substring(10) : name;
	}

	/**
	 * The member a call through a class reaches: the class's own of the name and the
	 * erased parameter types, else the one of the nearest class or interface above it.
	 */
	private static JavaClass.@Nullable Method member(JavaClass declaration, String name, List<String> parameters,
			java.util.Set<JavaClass> seen) {
		if (!seen.add(declaration)) {
			return null;
		}
		for (JavaClass.Method method : declaration.methods) {
			List<JavaType> declared = method.parameters.subList(method.hidden, method.parameters.size());
			if (method.name.equals(name) && !method.isPrivate
					&& declared.stream().map(JavaType::erasure).toList().equals(parameters)) {
				return method;
			}
		}
		if (name.equals("<init>")) {
			return null;
		}
		List<JavaClass> above = new ArrayList<>();
		if (declaration.superclass != null) {
			above.add(declaration.superclass);
		}
		above.addAll(declaration.interfaces);
		for (JavaClass up : above) {
			JavaClass.Method found = member(up, name, parameters, seen);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	@Test
	void aNameIsEscapedAndAConstantIsFolded() {
		// A name holding no lowercase letter never meets the pipeline's own; a
		// constant variable is its value wherever it is read; a package is part of the
		// class's name and java.lang's classes are named as a program writes them.
		assertThat(lower("""
				package demo.app;

				class T {
				    static final int NIL = 1 << 4;
				    static final String TEXT = "n=" + NIL + (NIL > 3 ? 'y' : 'n');
				    int CAR;
				    int CDR(int LIST) { return LIST + CAR + NIL; }
				}

				public class Main {
				    public static void main(String[] args) {
				        T T = new T();
				        char c = 'a' + 1;
				        long big = Integer.MAX_VALUE + 1L;
				        System.out.println(T.CDR(2) + demo.app.T.TEXT + c + big + (int) 3.9e10 + -0.0 + 7 / 2 * 1.5);
				    }
				}
				""", "demo/app/Main.java")).contains(
				"(DEFSTRUCT (|demo.app.T| (:CONC-NAME |demo.app.T.|) (:CONSTRUCTOR |%make-demo.app.T| NIL) "
						+ "(:PREDICATE |demo.app.T?|) (:COPIER NIL)) (|j%CAR| 0))",
				"(DEFUN |demo.app.T.CDR(int)| (|this| |j%LIST|) (DECLARE (TYPE (SIGNED-BYTE 32) |j%LIST|)) "
						+ "(%MASK-SIGNED-FIELD 32 (+ (%MASK-SIGNED-FIELD 32 (+ |j%LIST| (THE (SIGNED-BYTE 32) "
						+ "(|demo.app.T.j%CAR| |this|)))) 16)))",
				"(DEFUN |demo.app.Main.main(String[])| (|args|) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY T (*))) "
						+ "|args|)) (LET ((|j%T| (|demo.app.T.new()|))) (LET ((|c| "
						+ "98)) (DECLARE (TYPE (INTEGER 0 1114111) |c|)) (LET ((|big| 2147483648)) (DECLARE (TYPE "
						+ "(SIGNED-BYTE 64) |big|)) (WRITE-LINE (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT "
						+ "(%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT (%DECIMAL-PIECE (|demo.app.T.CDR(int)| (OR "
						+ "|j%T| (RONTOLISP::%JAVA-NPE)) 2)) \"n=16y\") (RONTOLISP::%JAVA-CHAR-STRING |c|)) "
						+ "(%DECIMAL-PIECE |big|)) \"2147483647\") \"-0.0\") \"4.5\"))))))",
				"(SETQ |demo.app.T.NIL| 16)", "(SETQ |demo.app.T.TEXT| \"n=16y\")",
				"(%REPORTING-UNCAUGHT #'RONTOLISP::%JAVA-UNCAUGHT-LINE (%HOST-EXIT 1) (|demo.app.Main.main(String[])| "
						+ "(VECTOR)))");
	}

	@Test
	@SuppressWarnings("unchecked")
	void theThreadsOfTheCorpusHoldNoMonitorWhileTheyAreSuspended() throws IOException {
		// What a thread suspended and resumed costs is decided by two numbers
		// (JavaResumable): the functions a thread can be suspended in, which are
		// lowered as state machines, and the synchronized regions held across a
		// suspension, whose locks make every entry that may be theirs a suspension
		// too. The library is written so that no region of its own is held -- a member
		// that never waits calls nothing that can -- and the analysis follows a
		// function value to the lambdas handed to the parameter it is: over the
		// corpus's thread programs no region is held and no entry may block, but for
		// the one program that sleeps holding a monitor (twice: a thread it starts, and
		// the thread watching another become BLOCKED), and less than a quarter of a
		// program's functions are resumable.
		Map<String, Object> spec;
		try (InputStream in = JavaLoweringTest.class.getResourceAsStream("/java-spec.yaml")) {
			spec = new tools.jackson.dataformat.yaml.YAMLMapper().readValue(am.ik.rontolisp.testsupport.YamlResources
				.safeReader(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8)), Map.class);
		}
		Map<String, JavaResumable.Census> found = new java.util.TreeMap<>();
		for (Map<String, Object> program : (List<Map<String, Object>>) Objects.requireNonNull(spec.get("standalone"))) {
			String file = (String) Objects.requireNonNull(program.get("file"));
			String source = (String) Objects.requireNonNull(program.get("source"));
			if (!file.endsWith(".java") || program.containsKey("files") || program.containsKey("library")) {
				continue;
			}
			String name = file.substring(0, file.length() - ".java".length());
			JavaLowering.lower(JavaParser.parse(source, file, name), source, file, JavaFiles.NONE, null, false, null,
					true, census -> found.put(name, census));
		}
		assertThat(found.keySet()).containsExactly("Dies", "Handing", "Hooks", "MainDies", "Monitors", "Sharing",
				"Timed", "Turns", "Waits");
		found.forEach((name, census) -> {
			assertThat(census.resumable().size() * 4).as(name).isLessThan(census.functions());
			if (name.equals("Turns")) {
				assertThat(census.held()).as(name)
					.containsExactly("Turns.held(): calls Thread.sleep(long)", "a lambda: calls Thread.sleep(long)");
				assertThat(census.blocking()).as(name).isEqualTo(8);
				// the lock a thread sleeps holding: its lock() and the one waiting for it
				assertThat(census.locksHeld()).as(name).containsExactly("a lambda: calls Thread.sleep(long)");
				assertThat(census.locksBlocking()).as(name).isEqualTo(2);
			}
			else {
				assertThat(census.held()).as(name).isEmpty();
				assertThat(census.blocking()).as(name).isZero();
			}
		});
		// what makes no function resumable that cannot suspend: a loop walking an
		// iterator by hand, one over indexes below a size a CopyOnWriteArrayList answers,
		// one its own locals end around a concurrent map's put, a class's initialization
		// called in a loop, a loop of the library's reading a cache of its own (a zone's
		// changes), a class object's test of an instance beside a lambda that sleeps; a
		// bundle read from a Reader beside a socket read through one reads its text
		// first, so no Properties holds its monitor across the read and no hashCode() of
		// the program waits for it. A reader or a stream read through its abstract
		// class reads the classes it may be, beside a socket read through the same
		// class: a Properties loaded from a StringReader it is handed holds no monitor
		// across a suspension, a stream held by a field of the stream around it reads
		// the stream a function answered. A loop whose exit is a concurrent map's answer
		// polls it and takes its turns. A ReentrantLock taken in a lock() followed by a
		// try giving it back is held there alone: such a region beside a socket read
		// takes its lock at once, one holding a socket read keeps the lock() of its lock
		// a wait, and so does a lock taken where no try gives it back (each lock a field
		// of its own). A loop over a result set's rows takes no turn for the log writer
		// an SQLException's constructor reads: what a throw throws decides no loop
		String source = """
				import java.io.*;
				import java.net.Socket;
				import java.sql.*;
				import java.time.*;
				import java.util.*;
				import java.util.concurrent.*;
				import java.util.concurrent.locks.*;

				class Names {
				    static final List<String> ALL = load();
				    static List<String> load() {
				        if (System.getenv("NO_NAMES") != null) { throw new IllegalStateException("no names"); }
				        return List.of("a", "b");
				    }
				    static int size() { return ALL.size(); }
				}

				public class Census {
				    static final ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
				    static int rounds = 2;

				    static int walked(Map<String, Integer> from) {
				        Iterator<String> each = from.keySet().iterator();
				        int n = 0;
				        while (each.hasNext()) {
				            counts.merge(each.next(), 1, Integer::sum);
				            n++;
				        }
				        return n;
				    }

				    static int listed(List<String> list) {
				        int n = 0;
				        for (int i = 0; i < list.size(); i++) {
				            n += list.get(i).length();
				        }
				        return n;
				    }

				    static void counted(String text) {
				        int at = 0;
				        while (at < text.length()) {
				            counts.put(text.substring(at, at + 1), at);
				            at += text.charAt(at) == ' ' ? 2 : 1;
				        }
				    }

				    static int sized() {
				        int total = 0;
				        while (total < rounds) {
				            total += Names.size();
				        }
				        return total;
				    }

				    static void polled() {
				        while (true) {
				            if (counts.get("done") != null) {
				                break;
				            }
				        }
				    }

				    static boolean tested(Object o) {
				        return String.class.isInstance(o);
				    }

				    static String zoned() {
				        return ZonedDateTime.of(2026, 3, 29, 2, 30, 0, 0, ZoneId.of("Europe/Paris")).toString();
				    }

				    static String bundled(Map<Object, String> seen) throws IOException {
				        return seen.get(new PropertyResourceBundle(new StringReader("a=1")).getString("a"));
				    }

				    static int read(Socket socket) throws IOException {
				        return new InputStreamReader(socket.getInputStream()).read();
				    }

				    static String loaded() throws IOException {
				        Properties settings = new Properties();
				        settings.load(new StringReader("port=8080"));
				        return settings.getProperty("port");
				    }

				    static InputStream opened() {
				        return new ByteArrayInputStream(new byte[] { 7 });
				    }

				    static final ReentrantLock guard = new ReentrantLock();
				    static final Lock reading = new ReentrantLock();
				    static final Lock kept = new ReentrantLock();

				    static int guarded() {
				        guard.lock();
				        try {
				            return counts.size();
				        }
				        finally {
				            guard.unlock();
				        }
				    }

				    static int readHolding(Socket socket) throws IOException {
				        reading.lock();
				        try {
				            return socket.getInputStream().read();
				        }
				        finally {
				            reading.unlock();
				        }
				    }

				    static int keeping() {
				        kept.lock();
				        try {
				            return counts.size();
				        }
				        finally {
				            kept.unlock();
				        }
				    }

				    static final class Rows {
				        boolean closed;
				        int at;
				        boolean next() throws SQLException {
				            if (closed) { throw refused(); }
				            if (at < 0) { throw new SQLException("no row", "24000"); }
				            return at++ < 3;
				        }
				        static SQLException refused() {
				            return new SQLNonTransientConnectionException("closed", "08003");
				        }
				    }

				    static int rowed(Rows rows) throws SQLException {
				        int n = 0;
				        while (rows.next()) {
				            n++;
				        }
				        return n;
				    }

				    static int buffered() throws IOException {
				        return new BufferedInputStream(opened()).read();
				    }

				    public static void main(String[] args) throws Exception {
				        Thread slept = new Thread(() -> {
				            try { Thread.sleep(1); } catch (InterruptedException e) { }
				        });
				        slept.start();
				        slept.join();
				        counted("ab");
				        System.out.println(walked(counts) + listed(new CopyOnWriteArrayList<>(List.of("a"))) + sized() + " "
				                + tested("x") + bundled(new HashMap<>()) + zoned() + loaded() + buffered() + guarded()
				                + rowed(new Rows()));
				        polled();
				        kept.lock();
				        System.out.println(read(new Socket("localhost", 1)) + readHolding(new Socket("localhost", 1))
				                + keeping());
				        kept.unlock();
				    }
				}
				""";
		List<JavaResumable.Census> counted = new ArrayList<>();
		JavaLowering.lower(JavaParser.parse(source, "Census.java", "Census"), source, "Census.java", JavaFiles.NONE,
				null, false, null, true, counted::add);
		// the analysis runs once, on the pass whose classes settled: not on each pass
		// the program is lowered again
		assertThat(counted).hasSize(1);
		JavaResumable.Census census = counted.get(0);
		List<String> resumable = census.resumable().stream().map(line -> line.split(": | -> ", 2)[0]).toList();
		assertThat(resumable).contains("Census.main(String[])", "Census.read(java.net.Socket)", "Census.polled()")
			.doesNotContain("Census.walked(java.util.Map)", "Census.listed(java.util.List)", "Census.counted(String)",
					"Census.sized()", "Census.tested(Object)", "Class.isInstance(Object)", "Census.zoned()",
					"java.util.HashMap.get(Object)", "%hash", "Census.loaded()",
					"java.util.Properties.load(java.io.Reader)", "Census.buffered()",
					"java.io.BufferedInputStream.read()", "Census.guarded()", "Census.rowed(Census.Rows)");
		assertThat(census.resumable()).contains(
				"Census.polled(): a loop's turn: its body calls java.util.concurrent.ConcurrentHashMap.get(Object) "
						+ "(enters a monitor)",
				"Census.keeping(): takes a lock Census.main(String[]) takes where no try gives it back");
		assertThat(census.held()).isEmpty();
		assertThat(census.blocking()).isZero();
		assertThat(census.locksHeld())
			.containsExactly("Census.readHolding(java.net.Socket): calls java.io.InputStream.read()%v");
		assertThat(census.locksBlocking()).isEqualTo(2);
	}

	@Test
	void aMonitorIsEnteredAndLeftAndAThreadIsTheRunTimes() {
		// synchronized enters the monitor of its object -- the class's for a static
		// method and a class literal -- and leaves it however the body ends; a volatile
		// field is read and written one access at a time; wait and notify are
		// Thread.java's
		// over the run time's; a started thread runs its run(), reports what it leaves
		// uncaught with its causes and ends; a program that starts one initializes a
		// class on one thread, its state written under a lock no initializer runs under
		// -- the text every later use says of it once the initialization threw -- and
		// ends when its threads have.
		String threads = """
				class Cell {
				    static volatile int hits;
				    volatile Cell next;
				    int held;
				    synchronized void add(Cell other) { held++; next = other.next; }
				    static synchronized int bump() { return hits++; }
				    void twice(Object lock) throws InterruptedException {
				        synchronized (lock) { synchronized (Cell.class) { held += 2; } lock.wait(); lock.notifyAll(); }
				    }
				}
				class Lazy { static int made; static { System.out.println("made"); } }
				class Late { static int made; static { if (made > 0) throw new IllegalStateException("late"); } }
				class T1 {
				    public static void main(String[] args) throws InterruptedException {
				        Cell cell = new Cell();
				        Thread worker = new Thread(() -> cell.add(cell));
				        worker.start();
				        worker.join();
				        cell.twice(cell);
				        System.out.println(Cell.bump() + cell.held + Lazy.made + Late.made);
				    }
				}
				""";
		assertThat(lower(threads, "T1.java")).contains(
				"(DEFUN |Cell.add(Cell)| (|this| |other|) (LET ((|%t1| (RONTOLISP::%JAVA-MONITOR-ENTER |this|))) "
						+ "(UNWIND-PROTECT (PROGN (SETF (|Cell.held| |this|) (%MASK-SIGNED-FIELD 32 (+ (THE "
						+ "(SIGNED-BYTE 32) (|Cell.held| |this|)) 1))) (LET* ((|%t0| (RONTOLISP:WITH-MUTEX "
						+ "((RONTOLISP::%JAVA-VOLATILE)) (|Cell.next| |other|)))) (RONTOLISP:WITH-MUTEX "
						+ "((RONTOLISP::%JAVA-VOLATILE)) (SETF (|Cell.next| |this|) |%t0|)))) "
						+ "(RONTOLISP::%JAVA-MONITOR-EXIT |%t1|))))",
				"(DEFUN |Cell.bump()| NIL (LET ((|%t3| (RONTOLISP::%JAVA-MONITOR-ENTER '|Cell|))) "
						+ "(UNWIND-PROTECT (PROG1 (THE (SIGNED-BYTE 32) (RONTOLISP:WITH-MUTEX "
						+ "((RONTOLISP::%JAVA-VOLATILE)) |Cell.hits|)) (LET* ((|%t2| (%MASK-SIGNED-FIELD 32 (+ (THE "
						+ "(SIGNED-BYTE 32) (RONTOLISP:WITH-MUTEX ((RONTOLISP::%JAVA-VOLATILE)) |Cell.hits|)) 1)))) "
						+ "(RONTOLISP:WITH-MUTEX ((RONTOLISP::%JAVA-VOLATILE)) (SETQ |Cell.hits| |%t2|)))) "
						+ "(RONTOLISP::%JAVA-MONITOR-EXIT |%t3|))))",
				"(DEFUN |Cell.twice(Object)| (|this| |lock|) (LET ((|%t5| (RONTOLISP::%JAVA-MONITOR-ENTER "
						+ "|lock|))) (UNWIND-PROTECT (PROGN (LET ((|%t4| (RONTOLISP::%JAVA-MONITOR-ENTER '|Cell|))) "
						+ "(UNWIND-PROTECT (SETF (|Cell.held| |this|) (%MASK-SIGNED-FIELD 32 (+ (THE (SIGNED-BYTE 32) "
						+ "(|Cell.held| |this|)) 2))) (RONTOLISP::%JAVA-MONITOR-EXIT |%t4|))) "
						+ "(|Thread.await(Object,long)| (OR |lock| (RONTOLISP::%JAVA-NPE)) 0) "
						+ "(|Thread.signal(Object,boolean)| (OR |lock| (RONTOLISP::%JAVA-NPE)) T)) "
						+ "(RONTOLISP::%JAVA-MONITOR-EXIT |%t5|))))",
				"(DEFUN |Thread.start()| (|this|) (PROGN (LET ((|%t7| (RONTOLISP::%JAVA-MONITOR-ENTER |this|))) "
						+ "(UNWIND-PROTECT (PROGN (IF (|Thread.started| |this|) (RONTOLISP::%JAVA-THROW "
						+ "(|IllegalThreadStateException.new()|) #'|Throwable.toString()|)) (SETF (|Thread.started| "
						+ "|this|) T) (RONTOLISP:WITH-MUTEX ((RONTOLISP::%JAVA-VOLATILE)) (SETF (|Thread.alive| |this|) "
						+ "T))) (RONTOLISP::%JAVA-MONITOR-EXIT |%t7|))) (LET ((|%t8| |this|)) "
						+ "(RONTOLISP::%JAVA-THREAD-START (|Thread.carrier| |this|) (|Thread.daemon| |this|) (LAMBDA "
						+ "NIL (UNWIND-PROTECT (HANDLER-CASE (|Thread.body(Thread)| |%t8|) (ERROR (|%t9|) "
						+ "(|Thread.report(Thread,String)| |%t8| (RONTOLISP::%JAVA-TRACED-TEXT |%t9| (LAMBDA "
						+ "(|%t10|) (|Throwable.trace(Throwable)| |%t10|)))))) (|Thread.finish(Thread)| |%t8|)))))))",
				"(DEFUN |Lazy.<clinit>| NIL (IF (EQ |Lazy.<clinit>%done| :ENDED) NIL (LET ((|%t17| "
						+ "(RONTOLISP::%JAVA-SELF))) (IF (EQ |Lazy.<clinit>%done| |%t17|) NIL (PROGN "
						+ "(RONTOLISP::%JAVA-INIT-ENTER) (WHILE (RONTOLISP::%JAVA-INIT-BUSY |Lazy.<clinit>%done|)) "
						+ "(IF |Lazy.<clinit>%done| (RONTOLISP::%JAVA-INIT-LEAVE NIL) (PROGN (SETQ "
						+ "|Lazy.<clinit>%done| |%t17|) (RONTOLISP::%JAVA-INIT-LEAVE NIL) (UNWIND-PROTECT "
						+ "(WRITE-LINE \"made\") (PROGN (RONTOLISP::%JAVA-INIT-ENTER) (SETQ |Lazy.<clinit>%done| "
						+ ":ENDED) (RONTOLISP::%JAVA-INIT-LEAVE T))))))))))",
				"(DEFUN |Late.<clinit>| NIL (IF (EQ |Late.<clinit>%done| :ENDED) NIL (LET ((|%t20| "
						+ "(RONTOLISP::%JAVA-SELF))) (IF (EQ |Late.<clinit>%done| |%t20|) NIL (PROGN "
						+ "(RONTOLISP::%JAVA-INIT-ENTER) (WHILE (RONTOLISP::%JAVA-INIT-BUSY |Late.<clinit>%done|)) (IF "
						+ "|Late.<clinit>%done| (PROGN (RONTOLISP::%JAVA-INIT-LEAVE NIL) (IF (STRINGP "
						+ "|Late.<clinit>%done|) (RONTOLISP::%JAVA-THROW "
						+ "(|NoClassDefFoundError.unusable(String,String)| \"Late\" |Late.<clinit>%done|) "
						+ "#'|Throwable.toString()|) NIL)) (LET ((|%t21| NIL)) (SETQ |Late.<clinit>%done| |%t20|) "
						+ "(RONTOLISP::%JAVA-INIT-LEAVE NIL) (UNWIND-PROTECT (HANDLER-CASE (IF (> (THE (SIGNED-BYTE "
						+ "32) |Late.made|) 0) (RONTOLISP::%JAVA-THROW (|IllegalStateException.new(String)| \"late\") "
						+ "#'|Throwable.toString()|)) ((SATISFIES RONTOLISP::%JAVA-THROWN) (|%t18|) (LET ((|%t19| "
						+ "(RONTOLISP::%JAVA-THROWN |%t18|))) (SETQ |%t21| "
						+ "(|ExceptionInInitializerError.record(Throwable,String)| |%t19| (|Thread.currentName()|))) "
						+ "(RONTOLISP::%JAVA-THROW (|ExceptionInInitializerError.of(Throwable)| |%t19|) "
						+ "#'|Throwable.toString()|)))) (PROGN (RONTOLISP::%JAVA-INIT-ENTER) (SETQ "
						+ "|Late.<clinit>%done| (IF |%t21| |%t21| :ENDED)) (RONTOLISP::%JAVA-INIT-LEAVE T))))))))))",
				"(RONTOLISP::%JAVA-THREAD-MAIN (LAMBDA NIL (PROGN (|%initialization|) (|T1.main(String[])| (VECTOR)))) "
						+ "(LAMBDA (|%t|) (|Thread.finish(Thread)| |%t|)) (LAMBDA (|%o|) (|Throwable.trace(Throwable)| "
						+ "|%o|)))");
		// where the target has one stack a thread is a body suspended and resumed:
		// the functions a thread can wait in are async-defuns and a call of one an
		// await; a function that only ends with such a call answers its future; a
		// monitor no suspended thread can hold is entered as anywhere, and a function
		// that never waits is the function it was
		assertThat(Java.read(threads, "T1.java", JavaFiles.NONE, null, true).stream().map(LispVal::print).toList())
			.contains("(DEFUN |Cell.add(Cell)| (|this| |other|) (LET ((|%t1| (RONTOLISP::%JAVA-MONITOR-ENTER "
					+ "|this|))) (UNWIND-PROTECT (PROGN (SETF (|Cell.held| |this|) (%MASK-SIGNED-FIELD 32 (+ (THE "
					+ "(SIGNED-BYTE 32) (|Cell.held| |this|)) 1))) (LET* ((|%t0| (RONTOLISP:WITH-MUTEX "
					+ "((RONTOLISP::%JAVA-VOLATILE)) (|Cell.next| |other|)))) (RONTOLISP:WITH-MUTEX "
					+ "((RONTOLISP::%JAVA-VOLATILE)) (SETF (|Cell.next| |this|) |%t0|)))) "
					+ "(RONTOLISP::%JAVA-MONITOR-EXIT |%t1|))))",
					"(RONTOLISP:ASYNC-DEFUN |Cell.twice(Object)| (|this| |lock|) (LET ((|%t5| "
							+ "(RONTOLISP::%JAVA-MONITOR-ENTER |lock|))) (UNWIND-PROTECT (PROGN (LET ((|%t4| "
							+ "(RONTOLISP::%JAVA-MONITOR-ENTER '|Cell|))) (UNWIND-PROTECT (SETF (|Cell.held| |this|) "
							+ "(%MASK-SIGNED-FIELD 32 (+ (THE (SIGNED-BYTE 32) (|Cell.held| |this|)) 2))) "
							+ "(RONTOLISP::%JAVA-MONITOR-EXIT |%t4|))) (RONTOLISP:AWAIT (|Thread.await(Object,long)| (OR "
							+ "|lock| (RONTOLISP::%JAVA-NPE)) 0)) (|Thread.signal(Object,boolean)| (OR |lock| "
							+ "(RONTOLISP::%JAVA-NPE)) T)) (RONTOLISP::%JAVA-MONITOR-EXIT |%t5|))))",
					"(DEFUN |Thread.join()| (|this|) (|Thread.join(long)| |this| 0))",
					"(RONTOLISP:ASYNC-DEFUN |T1.main(String[])| (|args|) (DECLARE (TYPE (OR NULL (SIMPLE-ARRAY T "
							+ "(*))) |args|)) (LET ((|cell| (|Cell.new()|))) (LET "
							+ "((|worker| (|Thread.new(Runnable)| (LAMBDA NIL (|Cell.add(Cell)| (OR |cell| "
							+ "(RONTOLISP::%JAVA-NPE)) |cell|))))) (|Thread.start()| (OR |worker| "
							+ "(RONTOLISP::%JAVA-NPE))) (RONTOLISP:AWAIT (|Thread.join()| (OR |worker| "
							+ "(RONTOLISP::%JAVA-NPE)))) (RONTOLISP:AWAIT (|Cell.twice(Object)| (OR |cell| "
							+ "(RONTOLISP::%JAVA-NPE)) |cell|)) (WRITE-LINE (%DECIMAL-PIECE (%MASK-SIGNED-FIELD 32 (+ "
							+ "(%MASK-SIGNED-FIELD 32 (+ (%MASK-SIGNED-FIELD 32 (+ (THE (SIGNED-BYTE 32) (|Cell.bump()|)) "
							+ "(THE (SIGNED-BYTE 32) (|Cell.held| |cell|)))) (THE (SIGNED-BYTE 32) (PROGN "
							+ "(|Lazy.<clinit>|) |Lazy.made|)))) (THE (SIGNED-BYTE 32) (PROGN (|Late.<clinit>|) "
							+ "|Late.made|)))))))))");
		// a loop whose one suspension is its turn is a function of its own, called until
		// it says it ended, so no body a thread is resumed in holds the loop; an exit of
		// a block around the loop is taken once the function has returned
		assertThat(Java.read("""
				class T3 {
				    static volatile boolean stop;
				    static int sum(int[] a) {
				        int n = 0;
				        int i = 0;
				        while (!stop) {
				            if (i == a.length) { return n; }
				            n += a[i++];
				        }
				        return -1;
				    }
				    public static void main(String[] args) throws Exception {
				        Thread t = new Thread(() -> System.out.println(sum(new int[] { 1 })));
				        t.start();
				        t.join();
				    }
				}
				""", "T3.java", JavaFiles.NONE, null, true).stream().map(LispVal::print).toList())
			.anyMatch(form -> form.startsWith("(RONTOLISP:ASYNC-DEFUN |T3.sum(int[])| ")
					&& form.contains("(LAMBDA NIL (BLOCK |%w") && form.contains("(WHILE (FUNCALL |%w")
					&& form.contains(") (RONTOLISP:AWAIT (RONTOLISP::%JAVA-SUSPENDING-TURN)))")
					&& form.contains(
							"(IF (< (SETQ RONTOLISP::%JAVA-SLICE (- RONTOLISP::%JAVA-SLICE 1)) 0) (RETURN-FROM |%w")
					&& form.contains(" 1) (RETURN-FROM |%fn| |%w") && !form.contains("(WHILE (PROGN (IF (< (SETQ"));
		// the run time's thread functions come in three variants under the same names:
		// the host's threads, a loop over the threads on one stack, and under a
		// component the host's events giving the threads their turns
		java.util.function.Function<am.ik.rontolisp.reader.Features, List<String>> defined = target -> JavaLibrary
			.forms(target)
			.stream()
			.map(LispVal::print)
			.filter(form -> form.startsWith("(DEFUN RONTOLISP::%JAVA-THREAD-"))
			.map(form -> form.substring(7, form.indexOf(' ', 7)))
			.toList();
		assertThat(defined.apply(am.ik.rontolisp.reader.Features.JVM)).doesNotContain("RONTOLISP::%JAVA-THREAD-RUN",
				"RONTOLISP::%JAVA-THREAD-PUMP");
		assertThat(defined.apply(am.ik.rontolisp.reader.Features.WASM)).contains("RONTOLISP::%JAVA-THREAD-RUN")
			.doesNotContain("RONTOLISP::%JAVA-THREAD-PUMP");
		assertThat(defined
			.apply(am.ik.rontolisp.reader.Features.WASM.with(List.of(am.ik.rontolisp.reader.Features.COMPONENT))))
			.contains("RONTOLISP::%JAVA-THREAD-PUMP")
			.doesNotContain("RONTOLISP::%JAVA-THREAD-RUN");
		// a program that starts no thread and neither waits nor asks cannot tell a
		// monitor
		// held from one that is not: synchronized leaves the exception a null has, the
		// program ends as any other and its classes are initialized under no lock. A
		// class whose initialization can throw keeps what it threw in place of its
		// state; the name of a class is asked of the program's own classes alone; what
		// nothing caught is reported with its causes where an exception can have one;
		// charAt is one read that tests its index
		assertThat(lower("""
				class Lazy { static int made; static { System.out.println("made"); } }
				class Late { static int made; static { if (made > 0) throw new IllegalStateException("late"); } }
				class T2 {
				    int n;
				    synchronized void add(Object lock, T2 other) {
				        synchronized (lock) { n++; }
				        synchronized (T2.class) { n++; }
				        synchronized (other.self()) { n++; }
				    }
				    T2 self() { return this; }
				    static synchronized void main() {
				        String text = "late";
				        System.out.println(Lazy.made + Late.made + text.charAt(n()));
				        new T2().add("x", new T2());
				    }
				    static int n() { return 1; }
				}
				""", "T2.java")).contains(
				"(DEFUN |T2.add(Object,T2)| (|this| |lock| |other|) (PROGN (PROGN (OR |lock| "
						+ "(RONTOLISP::%JAVA-NPE)) (SETF (|T2.n| |this|) (%MASK-SIGNED-FIELD 32 (+ (THE (SIGNED-BYTE "
						+ "32) (|T2.n| |this|)) 1)))) (SETF (|T2.n| |this|) (%MASK-SIGNED-FIELD 32 (+ (THE "
						+ "(SIGNED-BYTE 32) (|T2.n| |this|)) 1))) (PROGN (OR (|T2.self()| (OR |other| "
						+ "(RONTOLISP::%JAVA-NPE))) (RONTOLISP::%JAVA-NPE)) (SETF (|T2.n| |this|) (%MASK-SIGNED-FIELD "
						+ "32 (+ (THE (SIGNED-BYTE 32) (|T2.n| |this|)) 1))))))",
				"(DEFUN |Lazy.<clinit>| NIL (IF |Lazy.<clinit>%done| NIL (PROGN (SETQ |Lazy.<clinit>%done| T) "
						+ "(WRITE-LINE \"made\"))))",
				"(DEFUN |Late.<clinit>| NIL (IF (EQ |Late.<clinit>%done| T) NIL (IF |Late.<clinit>%done| "
						+ "(RONTOLISP::%JAVA-THROW (|NoClassDefFoundError.unusable(String,String)| \"Late\" "
						+ "|Late.<clinit>%done|) #'|Throwable.toString()|) (PROGN (SETQ |Late.<clinit>%done| T) "
						+ "(HANDLER-CASE (IF (> (THE (SIGNED-BYTE 32) |Late.made|) 0) (RONTOLISP::%JAVA-THROW "
						+ "(|IllegalStateException.new(String)| \"late\") #'|Throwable.toString()|)) ((SATISFIES "
						+ "RONTOLISP::%JAVA-THROWN) (|%t3|) (LET ((|%t4| (RONTOLISP::%JAVA-THROWN |%t3|))) (SETQ "
						+ "|Late.<clinit>%done| (|ExceptionInInitializerError.record(Throwable,String)| |%t4| "
						+ "\"main\")) (RONTOLISP::%JAVA-THROW (|ExceptionInInitializerError.of(Throwable)| |%t4|) "
						+ "#'|Throwable.toString()|))))))))",
				"(DEFUN |T2.main()| NIL (LET ((|text| \"late\")) (WRITE-LINE (%DECIMAL-PIECE (%MASK-SIGNED-FIELD "
						+ "32 (+ (%MASK-SIGNED-FIELD 32 (+ (THE (SIGNED-BYTE 32) (PROGN (|Lazy.<clinit>|) "
						+ "|Lazy.made|)) (THE (SIGNED-BYTE 32) (PROGN (|Late.<clinit>|) |Late.made|)))) (THE "
						+ "(SIGNED-BYTE 32) (LET* ((|%t0| (OR |text| (RONTOLISP::%JAVA-NPE))) (|%t1| (|T2.n()|)) "
						+ "(|%t2| (%STRING-CODE |%t0| |%t1|))) (IF (< |%t2| 0) (RONTOLISP::%JAVA-STRING-INDEX |%t1| "
						+ "|%t0|) |%t2|))))))) (|T2.add(Object,T2)| (OR (|T2.new()|) (RONTOLISP::%JAVA-NPE)) \"x\" "
						+ "(|T2.new()|))))",
				"(DEFUN |%class-name| (|%o|) (COND ((|IllegalStateException?| |%o|) "
						+ "\"java.lang.IllegalStateException\") ((|ExceptionInInitializerError?| |%o|) "
						+ "\"java.lang.ExceptionInInitializerError\") ((|NoClassDefFoundError?| |%o|) "
						+ "\"java.lang.NoClassDefFoundError\") ((|Lazy?| |%o|) \"Lazy\") ((|Late?| |%o|) "
						+ "\"Late\") ((|j%T2?| |%o|) \"T2\") (T (RONTOLISP::%JAVA-NO-RECEIVER |%o| "
						+ "\"getClass()\"))))",
				"(DEFUN |%uncaught| (|%c| |%text|) (RONTOLISP::%JAVA-UNCAUGHT-THROWN-LINE |%c| (LET ((|%o| "
						+ "(RONTOLISP::%JAVA-THROWN |%c|))) (IF |%o| (|Throwable.trace(Throwable)| |%o|) |%text|))))",
				"(%REPORTING-UNCAUGHT #'|%uncaught| (%HOST-EXIT 1) (|T2.main()|))");
	}

	@Test
	void aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition() {
		String[][] refusals = { { "record P(int... xs) {}", "1:17: a variable arity record component" },
				{ "import module java.desktop; void main() { }", "1:1: the module java.desktop" },
				{ "void main() { switch (1L) { default -> { } } }", "1:15: a switch over long" },
				{ "class A { strictfp void m() { } } void main() { }", "1:11: the modifier strictfp" },
				{ "import java.util.concurrent.DelayQueue; void main() { DelayQueue<?> q = null; }",
						"1:55: the class java.util.concurrent.DelayQueue" },
				{ "interface I { default void m() { } } class A implements I { Runnable r = I.super::m; } void main() { }",
						"1:81: a method reference through a qualified super" },
				{ "import java.util.TimeZone; void main() { String[] s = TimeZone.getAvailableIDs(); }",
						"1:64: TimeZone.getAvailableIDs()" },
				{ "void main() { Object b = java.util.prefs.Preferences.userRoot(); }",
						"1:42: the class java.util.prefs.Preferences" },
				{ "void main() { Object o = java.util.Currency.CANADA; }", "1:36: the class java.util.Currency" },
				{ "void main() { Process p = null; }", "1:15: the class java.lang.Process" },
				{ "void main() { var r = new java.util.Random(1); r.nextExponential(); }",
						"1:50: Random.nextExponential()" },
				{ "void main() { IO.println(\"a\".describeConstable()); }", "1:30: String.describeConstable()" },
				{ "void main() throws Exception { javax.crypto.Mac.getInstance(\"SslMacMD5\"); }",
						"1:49: the Mac SslMacMD5" },
				{ "void main() { IO.println(Collections.EMPTY_LIST); }", "1:38: Collections.EMPTY_LIST" },
				{ "void main() { var s = new StringBuilder(); s.codePointAt(0); }",
						"1:46: StringBuilder.codePointAt(int)" },
				{ "void main() { IO.println(Runtime.getRuntime().totalMemory()); }", "1:47: Runtime.totalMemory()" },
				{ "class A { int x; } class B extends A { class C { int y = B.super.x; } } void main() { }",
						"1:66: a field read through a qualified super" },
				{ "import java.util.concurrent.atomic.*; class A { volatile int x; } void main() { String n = \"x\"; "
						+ "AtomicIntegerFieldUpdater.newUpdater(A.class, n); }",
						"1:123: newUpdater of a field named by no constant" },
				{ "import java.util.concurrent.atomic.*; class A { private volatile int x; } class Refused { Object u = "
						+ "AtomicIntegerFieldUpdater.newUpdater(A.class, \"x\"); static void main() { } }",
						"1:128: newUpdater of a field the class calling it cannot read" },
				{ "class A extends Integer { } void main() { }", "1:17: a class extending java.lang.Integer" },
				{ "class String { } class Refused { static void main() { } }",
						"1:7: a class named as java.lang.String, which the program also uses," } };
		for (String[] refusal : refusals) {
			assertThatThrownBy(() -> lower(refusal[0], "Refused.java")).as("%s", refusal[0])
				.isInstanceOf(LispReadException.class)
				.hasMessage("Refused.java:" + refusal[1] + " is not supported by the experimental Java subset yet");
		}
	}

	/** A class whose {@code main} is a program's start, after the classes of a row. */
	private static final String WRONG = " public class Wrong { public static void main(String[] args) { } }";

	@Test
	void anErrorTheLoweringDependsOnIsReportedAtItsPosition() {
		String[][] errors = {
				{ "void main() { int x = \"s\"; }", "1:19: incompatible types: String cannot be converted to int" },
				{ "void main() { int x = 1L; }", "1:19: incompatible types: long cannot be converted to int" },
				{ "void main() { Integer i = 1; String[] a = (String[]) i; }",
						"1:43: incompatible types: Integer cannot be converted to String[]" },
				{ "void main() { char c = 70000; }", "1:20: incompatible types: int cannot be converted to char" },
				{ "void main() { foo(); }", "1:15: cannot find symbol: method foo()" },
				{ "void main() { IO.println(y); }", "1:26: cannot find symbol: variable y" },
				{ "void main() { Foo f = null; }", "1:15: cannot find symbol: class Foo" },
				// JUnit Jupiter is the program's own dependency, which a missing class of
				// it says how to give
				{ "import org.junit.jupiter.api.Test; class T { @Test void t() { } } void main() { }",
						"1:46: the class org.junit.jupiter.api.Test is not on the source path: JUnit Jupiter is a "
								+ "dependency of the program, compiled from its sources (--java-source-dep "
								+ "org.junit.jupiter:junit-jupiter-api:VERSION)" },
				{ "import org.junit.jupiter.params.*; class T { @ParameterizedTest void t(int i) { } } void main() { }",
						"1:46: the class org.junit.jupiter.params.ParameterizedTest is not on the source path: JUnit "
								+ "Jupiter is a dependency of the program, compiled from its sources (--java-source-dep "
								+ "org.junit.jupiter:junit-jupiter-params:VERSION)" },
				// the parameterized tests' artifact brings the API, and what the file
				// imports of both
				{ "import org.junit.jupiter.api.Test; import org.junit.jupiter.params.ParameterizedTest; "
						+ "class T { @Test void t() { } } void main() { }",
						"1:97: the class org.junit.jupiter.api.Test is not on the source path: JUnit Jupiter is a "
								+ "dependency of the program, compiled from its sources (--java-source-dep "
								+ "org.junit.jupiter:junit-jupiter-params:VERSION)" },
				{ "import java.util.*; import java.sql.*; void main() { Date d = null; }",
						"1:54: reference to Date is ambiguous" },
				{ "import module java.sql; void main() { IO.println(Date.from(null)); }",
						"1:50: reference to Date is ambiguous" },
				{ "void main() { int x = 1; int x = 2; }", "1:30: variable x is already defined" },
				{ "class A { synchronized int x; } void main() { }", "1:28: modifier synchronized not allowed here" },
				{ "class A { volatile void m() { } } void main() { }", "1:25: modifier volatile not allowed here" },
				{ "interface I { transient int X = 1; } void main() { }", "1:29: modifier transient not allowed here" },
				{ "interface I { synchronized void m(); } void main() { }",
						"1:33: modifier synchronized not allowed here" },
				{ "class A { final volatile int x = 1; } void main() { }",
						"1:30: illegal combination of modifiers: final and volatile" },
				{ "void main() { synchronized (1) { } }", "1:29: unexpected type: a reference is required, found int" },
				{ "void main() { break; }", "1:15: break outside switch or loop" },
				{ "void main() { a: { continue a; } }", "1:20: not a loop label: a" },
				{ "void main() { break b; }", "1:15: undefined label: b" },
				{ "void main() { yield 1; }", "1:15: yield outside of switch expression" },
				{ "void main() { int x = switch (1) { default -> { return; } }; }",
						"1:49: attempt to return out of a switch expression" },
				{ "void main() { int k = 1; switch (k) { case 1 -> { } case 1 -> { } } }",
						"1:58: duplicate case label" },
				{ "enum E { X, Y } void main() { int k = switch (E.X) { case X -> 1; }; }",
						"1:39: the switch expression does not cover all possible input values" },
				{ "sealed interface S { } record A() implements S { } record B() implements S { } void main() { S s = new A(); int k = switch (s) { case A a -> 1; }; }",
						"1:117: the switch expression does not cover all possible input values" },
				{ "sealed interface S permits A { } record A() implements S { } record B() implements S { } void main() { }",
						"1:69: class is not allowed to extend sealed class: S (as it is not listed in its 'permits' clause)" },
				{ "sealed interface S { } class A implements S { } void main() { }",
						"1:30: sealed, non-sealed or final modifiers expected" },
				{ "final class A { } class B extends A { } void main() { }", "1:25: cannot inherit from final A" },
				{ "enum E { X, Y } void main() { E e = new E(); }", "1:37: enum classes may not be instantiated" },
				{ "record R(int x) { int y; } void main() { }", "1:23: field declaration must be static" },
				{ "record P(int x, int y) { } void main() { Object o = null; if (o instanceof P(int a)) { } }",
						"1:76: incorrect number of nested patterns" },
				{ "void main() { Object o = \"s\"; if (o instanceof String s) { } IO.println(s); }",
						"1:73: cannot find symbol: variable s" },
				{ "void main() { Object o = \"s\"; if (o instanceof String s || s.isEmpty()) { } }",
						"1:60: cannot find symbol: variable s" },
				{ "class A { private int x; } class B { int f(A a) { return a.x; } } public class Wrong { public static void main(String[] args) { } }",
						"1:60: x has private access in A" },
				{ "class A { final int x = 1; void f() { x = 2; } } void main() { }",
						"1:39: cannot assign a value to final variable x" },
				{ "void main() { final int x = 1; x = 2; }", "1:32: cannot assign a value to final variable x" },
				{ "void main() { int x = 1; x++; Runnable r = new Runnable() { public void run() { IO.println(x); } }; }",
						"1:92: local variables referenced from an inner class must be final or effectively final" },
				{ "void main() { int x; if (\"a\".isEmpty()) x = 1; IO.println(x); }",
						"1:59: variable x might not have been initialized" },
				{ "int f(int a) { if (a > 0) return 1; } void main() { }", "1:37: missing return statement" },
				{ "void main() { return; IO.println(); }", "1:23: unreachable statement" },
				{ "class A { A() { int y = f(); super(); } int f() { return 1; } } void main() { }",
						"1:25: cannot reference this before supertype constructor has been called" },
				{ "class A { void f() { super(); } } void main() { }",
						"1:22: explicit constructor invocation may only appear within a constructor body" },
				{ "interface I { void m() { } } void main() { }", "1:20: interface abstract methods cannot have body" },
				{ "abstract class A { abstract void m(); } void main() { A a = new A() { }; }",
						"1:61: <anonymous Wrong$1> is not abstract and does not override abstract method m() in A" },
				{ "class O { class I { } static void f() { new I(); } } void main() { }",
						"1:41: non-static variable this cannot be referenced from a static context" },
				{ "class O { int x; static class N { int f() { return x; } } } void main() { }",
						"1:52: non-static variable x cannot be referenced from a static context" },
				{ "class A { static int a = b; static int b = 2; } void main() { }",
						"1:26: illegal forward reference" },
				{ "class A { static { return; } } void main() { }", "1:20: return outside method" },
				{ "class A { void f(int... xs, int y) { } } void main() { }",
						"1:27: a variable arity parameter must be the last parameter" },
				{ "void main() { IO.println(_); }",
						"1:26: the unnamed _ is allowed only where a local, a parameter of a lambda or of a catch, or a pattern is declared" },
				{ "void main() { String s = \"\"\"x\"\"\"; }",
						"1:26: illegal text block open delimiter sequence, missing line terminator" },
				{ "void main() { 1 + 2; }", "1:17: not a statement" },
				{ "int f() { return; } void main() { }", "1:11: incompatible types: missing return value" },
				{ "void main() { String s = (String) 1; }",
						"1:26: incompatible types: int cannot be converted to String" },
				{ "class A { } class B { } void main() { A a = new A(); B b = (B) a; }",
						"1:60: incompatible types: A cannot be converted to B" },
				{ "static void f(int x) { } static void f(long x, int y) { } void main() { f(\"s\"); }",
						"1:73: no suitable method found for f(String)" },
				{ "static void f(int x, long y) { } static void f(long x, int y) { } void main() { f(1, 2); }",
						"1:81: reference to f is ambiguous" },
				{ "void main() { IO.println(() -> 1); }", "1:18: no suitable method found for println(<lambda>)" },
				{ "interface I { void m(); } class A implements I { } void main() { }",
						"1:33: A is not abstract and does not override abstract method m() in I" },
				{ "abstract class A { } void main() { new A(); }", "1:36: A is abstract; cannot be instantiated" },
				{ "class A { int x; static int f() { return x; } } void main() { }",
						"1:42: non-static variable this cannot be referenced from a static context" },
				{ "class A { }",
						"1:7: no main method found in A: a program starts at the main of the first class of the file or of the "
								+ "class named as the file; with none it is a reactor, its entry points the public static methods "
								+ "of that class, which a module compiled -o out.wasm --no-wasi exports, or the exports of the "
								+ "@WitExport classes of its source tree, which a component exports" },
				{ "void main() { int x = 2147483648; }", "1:23: integer number too large" },
				{ "void main() { IO.println(1 +); }", "1:29: unexpected ')'" },
				{ "void main() { String s = \"abc", "1:30: unterminated string literal" },
				{ "void main() { char c = 'ab'; }", "1:24: unterminated character literal" },
				{ "void main() { int x = 1 # 2; }", "1:25: illegal character: '#'" },
				// a checked exception is said where a compiler says it: at a call's
				// parenthesis, a creation's new, the class whose default constructor
				// throws it, the resource whose close() does, the clause, the reference
				{ "void main() { Thread.sleep(1); }",
						"1:27: unreported exception InterruptedException; must be caught or declared to be thrown" },
				{ "class A { A() throws Exception { } } void main() { new A(); }",
						"1:52: unreported exception Exception; must be caught or declared to be thrown" },
				{ "class A { A() throws Exception { } } class B extends A { } void main() { }",
						"1:38: unreported exception Exception in default constructor" },
				{ "void main() { try (java.io.Reader r = new java.io.StringReader(\"x\")) { } }",
						"1:35: unreported exception IOException; must be caught or declared to be thrown; "
								+ "exception thrown from implicit call to close() on resource variable 'r'" },
				{ "void main() { try { Thread.sleep(1); } catch (InterruptedException | RuntimeException e) { } catch (IllegalStateException e) { } }",
						"1:94: exception IllegalStateException has already been caught" },
				{ "void main() { java.util.function.LongConsumer nap = Thread::sleep; }",
						"1:53: incompatible thrown types InterruptedException in functional expression" },
				{ "class A { void m() { } } class B extends A { void m() throws Exception { } } void main() { }",
						"1:51: m() in Wrong.B cannot override m() in Wrong.A; overridden method does not throw Exception" },
				// a static member class is named alone
				{ "class W { static class A { final void m() { } } static class B extends A { void m() { } } } void main() { }",
						"1:81: m() in B cannot override m() in A; overridden method is final" },
				{ "class A { protected void m() { } } class B extends A { void m() { } } void main() { }",
						"1:61: m() in Wrong.B cannot override m() in Wrong.A; attempting to assign weaker access privileges; was protected" },
				// a receiver parameter: of an instance method, the class itself; of an
				// inner
				// class's constructor, the class around it; first, neither final nor of
				// variable arity
				{ "class A { static void m(A this) { } }" + WRONG,
						"1:27: non-static variable this cannot be referenced from a static context" },
				{ "class A { void m(String this) { } }" + WRONG,
						"1:18: the receiver type does not match the enclosing class type" },
				{ "class B { } class A { void m(A B.this) { } }" + WRONG, "1:33: not an enclosing class: B" },
				{ "class A { class I { void m(I A.this) { } } }" + WRONG,
						"1:31: the receiver name does not match the enclosing class type" },
				{ "class A { A(A this) { } }" + WRONG,
						"1:15: receiver parameter not applicable for constructor of top-level class" },
				{ "class A { class I { I(I A.this) { } } }" + WRONG,
						"1:23: the receiver type does not match the enclosing outer class type" },
				{ "class A { class I { I(A this) { } } }" + WRONG,
						"1:25: the receiver name does not match the enclosing outer class type" },
				{ "class A { void m(int a, A this) { } }" + WRONG,
						"1:27: as of release 8, 'this' is allowed as the parameter name for the receiver type only" },
				{ "class A { void m(final A this) { } }" + WRONG, "1:26: modifier final not allowed here" },
				{ "class A { void m(A... this) { } }" + WRONG,
						"1:27: varargs notation not allowed on receiver parameter" },
				// a type annotation ahead of a class only scoping a static member, which
				// javac
				// refuses: one the language has no type of
				{ "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface U { } class A { static class N { } @U A.N n; }"
						+ WRONG, "1:119: type annotation @U is not expected here" },
				// of the flow, the first problem a compiler reports: a class and the
				// classes
				// in it typed first; of each kind its own order -- a local class where it
				// is
				// declared, a member class's unreachable statement ahead, its unassigned
				// variable after, an instance initializer's unreachable statement ahead
				// of the
				// methods, the captures as written
				{ "class A { void a() { Thread.sleep(1); } void b() { class L { void m() { Thread.sleep(1); } } } } void main() { }",
						"1:34: unreported exception InterruptedException; must be caught or declared to be thrown" },
				{ "class A { void b() { class L { void m() { Thread.sleep(1); } } } void c() { foo(); } } void main() { }",
						"1:77: cannot find symbol: method foo()" },
				{ "class A { void a() { return; IO.println(); } class M { void k() { return; IO.println(); } } } void main() { }",
						"1:75: unreachable statement" },
				{ "class A { static class M { void j() { int y; y++; } } void a() { int x; x++; } } void main() { }",
						"1:73: variable x might not have been initialized" },
				{ "class A { void a() { return; IO.println(); } A() { } Runnable r = () -> { return; IO.println(); }; } void main() { }",
						"1:83: unreachable statement" },
				{ "class A { void a() { int z = 0; z = 1; Runnable r = () -> IO.println(z); } static { int s = 0; s = 1; Runnable r = () -> IO.println(s); } } void main() { }",
						"1:70: local variables referenced from a lambda expression must be final or effectively final" },
				{ "class A { void b() { int p; class L { void j() { int y; y++; } void k() { IO.println(p); } } } } void main() { }",
						"1:57: variable y might not have been initialized" } };
		for (String[] error : errors) {
			assertThatThrownBy(() -> lower(error[0], "Wrong.java")).as("%s", error[0])
				.isInstanceOf(LispReadException.class)
				.hasMessage("Wrong.java:" + error[1]);
		}
	}

	@Test
	void aLibraryOfTheSourcePathIsLoweredAsFarAsTheProgramReaches() {
		// The roots of the source path: a library, some of whose declarations name
		// classes nobody has.
		Map<String, String> library = Map.of("lib/Lists.java", """
				package lib;
				import java.util.Currency;
				import org.marks.Checked;
				public class Lists {
				    @Checked public static int twice(int x) { return 2 * x; }
				    public static int unused(int x) { return Hidden.three() * x; }
				    public static Currency today() {
				        return Currency.getInstance("JPY");
				    }
				    public static Currency none() { return null; }
				    public static String describe(Currency money, Clocked clocked) { return "a day"; }
				    public static int deep() { return today() == null ? 0 : 1; }
				}
				""", "lib/Extra.java", """
				package lib;
				public class Extra { }
				class Hidden { static int three() { return 3; } }
				""", "lib/Clocked.java", """
				package lib;
				public class Clocked implements java.util.Observer { }
				""", "lib/Mark.java",
				"package lib; public @interface Mark { String value() default \"\"; int[] at() default { 1 }; }",
				// a file refused once its members named a class under its own, which is
				// taken out with it
				"lib/Base.java", """
						package lib;
						import static lib.gone.Nowhere.thing;
						public class Base { public static Sub made() { return null; } }
						""", "lib/Sub.java", "package lib; public class Sub extends Base { }", "lib/Walk.java", """
						package lib;
						public class Walk {
						    public static String run(Object o) { return o instanceof Base ? "base" : "none"; }
						}
						""");
		JavaFiles files = new JavaFiles() {
			@Override
			public JavaFiles.@Nullable Source find(String from, String path) {
				return null;
			}

			@Override
			public JavaFiles.@Nullable Source library(String path) {
				String text = library.get(path);
				return text == null ? null : new JavaFiles.Source("lib.jar!/" + path, text);
			}

			@Override
			public List<JavaFiles.Source> libraryPackage(String directory) {
				return library.entrySet()
					.stream()
					.filter(file -> file.getKey().startsWith(directory)
							&& file.getKey().indexOf('/', directory.length()) < 0)
					.sorted(Map.Entry.comparingByKey())
					.map(file -> new JavaFiles.Source("lib.jar!/" + file.getKey(), file.getValue()))
					.toList();
			}
		};
		// Only what is reached is lowered: the method called, not the one beside it; a
		// method whose parameters name classes nobody has is called while nothing asks
		// what they are. An annotation of the library's own type is dropped.
		List<String> forms = Java.read("""
				package app;
				import lib.Lists;
				import lib.Mark;
				@Mark(value = "m", at = { 1, 2 })
				public class Main { public static void main(String[] a) {
				    IO.println(Lists.twice(4) + Lists.describe(null, null)); } }
				""", "app/Main.java", files).stream().map(LispVal::print).toList();
		assertThat(forms)
			.contains("(DEFUN |lib.Lists.twice(int)| (|x|) (DECLARE (TYPE (SIGNED-BYTE 32) |x|)) (%MASK-SIGNED-FIELD "
					+ "32 (* 2 |x|)))");
		assertThat(String.join("\n", forms)).doesNotContain("lib.Lists.unused", "lib.Hidden", "lib.Lists.today");
		// a statement of a body that is reached naming what the subset lacks throws
		// where it runs, as a JVM links code where it first runs; the compilation says
		// where, what, and how the program comes there
		List<String> warned = new java.util.ArrayList<>();
		String today = Java
			.read("package app; public class Main { public static void main(String[] a) {\n"
					+ "        IO.println(lib.Lists.deep()); } }", "app/Main.java", files, warned::add)
			.stream()
			.map(LispVal::print)
			.filter(form -> form.startsWith("(DEFUN |lib.Lists.today()|"))
			.findFirst()
			.orElseThrow();
		assertThat(today).isEqualTo("(DEFUN |lib.Lists.today()| NIL (RONTOLISP::%JAVA-THROW"
				+ " (|NoClassDefFoundError.new(String)| \"the class java.util.Currency is not supported by the"
				+ " experimental Java subset yet\") #'|Throwable.toString()|))");
		assertThat(warned).containsExactly("warning: lib.jar!/lib/Lists.java:8:16: the class java.util.Currency is"
				+ " not supported by the experimental Java subset yet; where it runs, it throws"
				+ " java.lang.NoClassDefFoundError\n  reached through lib.Lists.today()\n  reached through"
				+ " lib.Lists.deep()\n");
		String[][] errors = {
				// a type test of a class whose file was refused is false, no value being
				// one; the class read under it meanwhile is taken out with it, which no
				// question of the whole program asks of again
				{ "Object o = \"x\"; IO.println(lib.Walk.run(o) + (o instanceof Runnable));", null },
				// what a class nobody has is made of is refused where the program asks
				{ "IO.println(lib.Lists.none().getSymbol());",
						"app/Main.java:2:37: the class java.util.Currency is not supported by the experimental"
								+ " Java subset yet" },
				// a class under one nobody has is one nobody has
				{ "new lib.Clocked();",
						"lib.jar!/lib/Clocked.java:2:33: the class java.util.Observer is not supported by"
								+ " the experimental Java subset yet" },
				// the library's classes are checked for access as the program's are; one
				// declared in the file of another is found, as its class file would be
				{ "lib.Hidden.three();",
						"app/Main.java:2:13: Hidden is not public in lib; cannot be accessed from outside package" },
				{ "IO.println(lib.Lists.unused(1));", null },
				{ "@org.marks.Checked int x = 1;", "app/Main.java:2:9: cannot find symbol: class org.marks.Checked" },
				{ "@lib.Lists int x = 1;",
						"app/Main.java:2:9: incompatible types: Lists cannot be converted to Annotation" } };
		List<@Nullable String> reported = new java.util.ArrayList<>();
		for (String[] error : errors) {
			try {
				Java.read("package app; public class Main { public static void main(String[] a) {\n        " + error[0]
						+ " } }", "app/Main.java", files);
				reported.add(null);
			}
			catch (LispReadException ex) {
				reported.add(ex.getMessage());
			}
		}
		assertThat(reported).containsExactlyElementsOf(java.util.Arrays.stream(errors).map(error -> error[1]).toList());
	}

	@Test
	void aProgramOfSeveralFilesIsReadFromItsSourceTree() {
		// The files of the source tree, rooted where the starting file's package says.
		Map<String, String> tree = Map.of("p/U.java", """
				package p;
				public class U {
				    public static int F = 3;
				    int hidden;
				    public static int yes() { return 1; }
				    public int inst() { return 2; }
				    void pkg() { }
				    protected void prot() { }
				    public static class Inner { }
				}
				""", "p/B.java", "package p; class B { public static int v() { return 1; } }", "p/Q.java",
				"package other; public class Q { }", "app/Helper.java", """
						package app;
						class Helper { static int twice(int x) { return 2 * x; } }
						""", "c/Chrono.java", "package c; public interface Chrono<D extends Base> { }", "c/Base.java",
				"package c; public interface Base { Holder held(); }", "c/Holder.java",
				"package c; public class Holder implements Chrono<Impl> { }", "c/Impl.java",
				"package c; public class Impl implements Base { public Holder held() { return null; } }", "c/Bad.java",
				"package c; public class Bad implements Chrono<String> { }");
		JavaFiles files = new JavaFiles() {
			@Override
			public JavaFiles.@Nullable Source find(String from, String path) {
				String resolved = Path.of(from).resolveSibling(path).normalize().toString();
				String text = tree.get(resolved);
				return text == null ? null : new JavaFiles.Source(resolved, text);
			}

			// beside the classes, a directory of more files than a program carries
			@Override
			public @Nullable List<String> findListing(String from, String path) {
				String resolved = Path.of(from).resolveSibling(path).normalize().toString();
				return resolved.isEmpty() ? List.of("big/") : resolved.equals("big")
						? java.util.stream.IntStream.rangeClosed(0, 10_000).mapToObj(i -> "f" + i).toList() : null;
			}

			@Override
			public byte @Nullable [] findResource(String from, String path) {
				return Path.of(from).resolveSibling(path).normalize().startsWith("big") ? new byte[] { 1 } : null;
			}
		};
		// A class beside the file, a class of another package and a static import are
		// lowered with it, each from its own file.
		assertThat(Java.read("""
				package app;
				import static p.U.*;
				public class Main { public static void main(String[] a) { IO.println(Helper.twice(yes() + F)); } }
				""", "app/Main.java", files).stream().map(LispVal::print).toList())
			.contains("(DEFUN |app.Helper.twice(int)| (|x|) (DECLARE (TYPE (SIGNED-BYTE 32) |x|)) (%MASK-SIGNED-FIELD "
					+ "32 (* 2 |x|)))", "(DEFUN |p.U.yes()| NIL 1)");
		String[][] errors = {
				{ "x/Main.java", "package app; class Main { }",
						"x/Main.java:1:1: end of path to source file does not match its package name app" },
				{ "app/Main.java", "package app; class Main { void main() { IO.println(p.B.v()); } }",
						"app/Main.java:1:54: B is not public in p; cannot be accessed from outside package" },
				{ "app/Main.java", "package app; import p.B; class Main { void main() { } }",
						"app/Main.java:1:21: B is not public in p; cannot be accessed from outside package" },
				{ "app/Main.java", "package app; class Main { void main() { IO.println(new p.U().hidden); } }",
						"app/Main.java:1:62: hidden is not public in U; cannot be accessed from outside package" },
				{ "app/Main.java", "package app; class Main { void main() { new p.U().pkg(); } }",
						"app/Main.java:1:51: pkg() is not public in U; cannot be accessed from outside package" },
				{ "app/Main.java", "package app; class Main { void main() { new p.U().prot(); } }",
						"app/Main.java:1:51: prot() has protected access in U" },
				{ "app/Main.java", "package app; class Main extends p.U { void main() { prot(); } }", null },
				// a member class imported by its canonical name, named in the
				// declarations
				// of a member class: they are read before the file's imports are
				{ "app/Main.java",
						"package app; import p.U.Inner; class Main { record R(Inner inner) { } void main() { } }",
						null },
				{ "app/Main.java", "package app; import static p.U.nope; class Main { void main() { } }",
						"app/Main.java:1:14: cannot find symbol: static nope" },
				{ "app/Main.java", "package app; import static p.Nope.*; class Main { void main() { } }",
						"app/Main.java:1:28: cannot find symbol: class Nope" },
				{ "app/Main.java", "package app; import static p.U.*; class Main { void main() { inst(); } }",
						"app/Main.java:1:62: cannot find symbol: method inst()" },
				{ "app/Main.java", "package app; class Main { void main() { new p.Q(); } }",
						"p/Q.java:1:1: bad source file: p/Q.java: file does not contain class p.Q" },
				// a type argument is checked once the supertypes of every class it names
				// are read: Impl's, here, are being read when Holder is
				{ "app/Main.java", "package app; class Main { void main() { new c.Impl(); } }", null },
				{ "app/Main.java", "package app; class Main { void main() { new c.Bad(); } }",
						"c/Bad.java:1:47: type argument String is not within bounds of type-variable D" },
				// a name computed where the program runs carries the files of the tree,
				// as many as there may be; one of a constant prefix and suffix those it
				// may name: big/f1*, a thousand and some, and no .txt
				{ "app/Main.java",
						"package app; class Main { void main(String[] a) { Main.class.getResourceAsStream(a[0]); } }",
						"app/Main.java:1:62: a resource named where the program runs while the source tree holds more"
								+ " than 10000 files to carry is not supported by the experimental Java subset yet" },
				{ "app/Main.java",
						"""
								package app; class Main { void main(String[] a) { Main.class.getResourceAsStream("/big/f1" + a[0]);
								Main.class.getClassLoader().getResourceAsStream("big/" + a[0] + ".txt"); } }""",
						null } };
		List<@Nullable String> reported = new java.util.ArrayList<>();
		for (String[] error : errors) {
			try {
				Java.read(error[1], error[0], files);
				reported.add(null);
			}
			catch (LispReadException ex) {
				reported.add(ex.getMessage());
			}
		}
		assertThat(reported).containsExactlyElementsOf(java.util.Arrays.stream(errors).map(error -> error[2]).toList());
	}

}
