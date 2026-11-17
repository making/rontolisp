package am.ik.rontolisp.java;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
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

	private static List<String> lower(String source, String file) {
		return Java.read(source, file).stream().map(LispVal::print).toList();
	}

	@Test
	void theLoweringTable() {
		// A class is a structure, extends its :include, a field a slot with its type's
		// default, a static field a global set before main; a method's function has
		// its signature in its name; a call is direct with one body and a function
		// over the structure predicates with several, a function tested first where
		// the interface is a lambda's; + is by the operands' static types; a library
		// class is read where the program first names it, here inside method bodies.
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
						        if (loop(new int[] {1, 2}) == 3) throw new IllegalStateException("x");
						    }
						}
						""",
						"L1.java"))
			.contains(
					"(DEFSTRUCT (|Base| (:CONC-NAME |Base.|) (:CONSTRUCTOR |%make-Base| NIL) (:PREDICATE |Bas"
							+ "e?|) (:COPIER NIL)) (|label| NIL))",
					"(DEFSTRUCT (|Circle| (:INCLUDE |Base|) (:CONC-NAME |Circle.|) (:CONSTRUCTOR |%make-Circl"
							+ "e| NIL) (:PREDICATE |Circle?|) (:COPIER NIL)) (|r| 0.0))",
					"(DEFSTRUCT (|j%L1| (:CONC-NAME L1.) (:CONSTRUCTOR |%make-L1| NIL) (:PREDICATE |j%L1?|) ("
							+ ":COPIER NIL)))",
					"(DEFVAR |Base.count|)",
					"(DEFUN |Base.<init>(String)| (|this| |label|) (PROGN (SETF (|Base.label| |this|) \"b\") (S"
							+ "ETF (|Base.label| |this|) |label|) (SETQ |Base.count| (- (LOGAND (+ (+ |Base.count| 1) 2"
							+ "147483648) 4294967295) 2147483648))))",
					"(DEFUN |Base.toString()| (|this|) (%STRING-CONCAT (OR (|Base.label| |this|) \"null\") (RON"
							+ "TOLISP::%JAVA-DOUBLE-STRING (|Base.area()%v| |this|))))",
					"(DEFUN |Circle.new(double)| (|r|) (LET ((|this| (|%make-Circle|))) (|Circle.<init>(doubl"
							+ "e)| |this| |r|) |this|))",
					"(DEFUN |Circle.area()| (|this|) (* (* 3.0 (|Circle.r| |this|)) (|Circle.r| |this|)))",
					"(DEFUN |Rect.new()| NIL (LET ((|this| (|%make-Rect|))) (|Rect.<init>()| |this|) |this|))",
					"(DEFUN |L1.twice(int)| (|x|) (- (LOGAND (+ (* |x| 2) 2147483648) 4294967295) 2147483648)" + ")",
					"(DEFUN |L1.wide(long,int)| (|x| |y|) (- (LOGAND (+ (ASH (- (LOGAND (+ (+ |x| |y|) 922337"
							+ "2036854775808) 18446744073709551615) 9223372036854775808) 3) 9223372036854775808) 184467"
							+ "44073709551615) 9223372036854775808))",
					"(DEFUN |L1.show(Shape,Object,char,boolean)| (|s| |o| |c| |b|) (%STRING-CONCAT (%STRING-C"
							+ "ONCAT (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT \"s=\" (|%string| |s|)) (|%string| |"
							+ "o|)) (RONTOLISP::%JAVA-CHAR-STRING |c|)) (IF |b| \"true\" \"false\")) (RONTOLISP::%JAVA-DOUB"
							+ "LE-STRING (|Shape.area()%v| |s|))))",
					"(DEFUN |L1.loop(int[])| (|xs|) (LET ((|sum| 0)) (LET* ((|i| 0)) (BLOCK |%break| (WHILE ("
							+ "< |i| (LENGTH |xs|)) (BLOCK |%continue| (IF (< (AREF |xs| |i|) 0) (RETURN-FROM |%continu"
							+ "e| NIL)) (IF (> (AREF |xs| |i|) 99) (RETURN-FROM |%break| NIL)) (SETQ |sum| (- (LOGAND ("
							+ "+ (+ |sum| (AREF |xs| |i|)) 2147483648) 4294967295) 2147483648))) (SETQ |i| (- (LOGAND ("
							+ "+ (+ |i| 1) 2147483648) 4294967295) 2147483648))))) |sum|))",
					"(DEFUN |L1.guarded(Object,int)| (|o| |d|) (BLOCK |%fn| (UNWIND-PROTECT (HANDLER-CASE (LE"
							+ "T ((|c| (LET ((|%t0| |o|)) (IF (OR (NULL |%t0|) (|Circle?| |%t0|)) |%t0| (RONTOLISP::%JA"
							+ "VA-THROW (|ClassCastException.new()|) #'|Throwable.toString()|))))) (RETURN-FROM |%fn| ("
							+ "- (LOGAND (+ (TRUNCATE (RONTOLISP::%JAVA-DOUBLE-TO-INT (|Circle.r| |c|)) |d|) 2147483648"
							+ ") 4294967295) 2147483648))) (ERROR (|%t1|) (LET ((|%t2| (OR (RONTOLISP::%JAVA-THROWN |%t"
							+ "1|) (|%lisp-error| |%t1|)))) (COND ((|ArithmeticException?| |%t2|) (LET ((|e| |%t2|)) (R"
							+ "ETURN-FROM |%fn| -1))) (T (ERROR |%t1|)))))) (SETQ |Base.count| 10))))",
					"(DEFUN |L1.adder(int)| (|n|) (LAMBDA (|x|) (- (LOGAND (+ (+ |x| |n|) 2147483648) 4294967"
							+ "295) 2147483648)))",
					"(DEFUN |L1.main(String[])| (|args|) (LET ((|s| (|Circle.new(double)| 1.5))) (LET ((|boxe"
							+ "d| (|L1.twice(int)| 3))) (LET ((|flag| (IF (> |boxed| 3) T :FALSE))) (WRITE-LINE (%STRIN"
							+ "G-CONCAT (%STRING-CONCAT (%STRING-CONCAT (OR (|L1.show(Shape,Object,char,boolean)| |s| |"
							+ "boxed| 99 (RONTOLISP::%JAVA-UNBOX-BOOLEAN |flag|)) \"null\") (%DECIMAL-PIECE (FUNCALL (|L1"
							+ ".adder(int)| 1) 2))) (IF (|Rect?| |s|) \"true\" \"false\")) (IF (NOT (NULL |s|)) \"true\" \"fal"
							+ "se\"))) (IF (= (|L1.loop(int[])| (MAKE-ARRAY 2 :ELEMENT-TYPE '(SIGNED-BYTE 32) :INITIAL-C"
							+ "ONTENTS (LIST 1 2))) 3) (RONTOLISP::%JAVA-THROW (|IllegalStateException.new(String)| \"x\""
							+ ") #'|Throwable.toString()|))))))",
					"(DEFUN |ClassCastException.new()| NIL (|%make-ClassCastException|))",
					"(DEFUN |Throwable.toString()| (|this|) (LET ((|name| (|%class-name| |this|))) (IF (NULL "
							+ "(|Throwable.message| |this|)) |name| (%STRING-CONCAT (%STRING-CONCAT (OR |name| \"null\") "
							+ "\": \") (OR (|Throwable.message| |this|) \"null\")))))",
					"(DEFUN |Base.area()%v| (|%o|) (COND ((|Circle?| |%o|) (|Circle.area()| |%o|)) ((|Rect?| "
							+ "|%o|) (|Rect.area()| |%o|)) (T (RONTOLISP::%JAVA-NO-RECEIVER |%o| \"area()\"))))",
					"(DEFUN |Shape.area()%v| (|%o|) (COND ((FUNCTIONP |%o|) (FUNCALL |%o|)) ((|Circle?| |%o|)"
							+ " (|Circle.area()| |%o|)) ((|Rect?| |%o|) (|Rect.area()| |%o|)) (T (RONTOLISP::%JAVA-NO-R"
							+ "ECEIVER |%o| \"area()\"))))",
					"(DEFUN |%string| (|%o|) (COND ((|Base?| |%o|) (|Base.toString()| |%o|)) ((|Throwable?| |"
							+ "%o|) (|Throwable.toString()| |%o|)) (T (RONTOLISP::%JAVA-TO-STRING |%o| #'|%string|))))",
					"(DEFUN |%lisp-error| (|%c|) (IF (RONTOLISP::%JAVA-DIVISION-BY-ZERO-P |%c|) (|ArithmeticE"
							+ "xception.new(String)| \"/ by zero\") (|RuntimeException.new(String)| (RONTOLISP::%JAVA-CON"
							+ "DITION-TEXT |%c|))))",
					"(SETQ |Base.count| 0)", "(UNWIND-PROTECT (|L1.main(String[])| (VECTOR)) NIL)");
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
				""", "Main.java")).contains(
				"(DEFSTRUCT (|demo.app.T| (:CONC-NAME |demo.app.T.|) (:CONSTRUCTOR |%make-demo.app.T| NIL)"
						+ " (:PREDICATE |demo.app.T?|) (:COPIER NIL)) (|j%CAR| 0))",
				"(DEFUN |demo.app.T.CDR(int)| (|this| |j%LIST|) (- (LOGAND (+ (+ (- (LOGAND (+ (+ |j%LIST|"
						+ " (|demo.app.T.j%CAR| |this|)) 2147483648) 4294967295) 2147483648) 16) 2147483648)"
						+ " 4294967295) 2147483648))",
				"(DEFUN |demo.app.Main.main(String[])| (|args|) (LET ((|j%T| (|demo.app.T.new()|))) (LET ((|c| 98))"
						+ " (LET ((|big| 2147483648)) (WRITE-LINE (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT"
						+ " (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT (%DECIMAL-PIECE"
						+ " (|demo.app.T.CDR(int)| |j%T| 2)) \"n=16y\") (RONTOLISP::%JAVA-CHAR-STRING |c|))"
						+ " (%DECIMAL-PIECE |big|)) \"2147483647\") \"-0.0\") \"4.5\"))))))",
				"(SETQ |demo.app.T.NIL| 16)", "(SETQ |demo.app.T.TEXT| \"n=16y\")",
				"(|demo.app.Main.main(String[])| (VECTOR))");
	}

	@Test
	void aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition() {
		String[][] refusals = { { "void main() { switch (1) { default: } }", "1:15: a switch" },
				{ "enum E { A }", "1:1: an enum" }, { "record P(int x) {}", "1:1: a record" },
				{ "class A<T> {}", "1:8: a generic class" },
				{ "class A { <T> void m() { } }", "1:11: a generic method" },
				{ "class A { class B {} }", "1:11: a nested class" },
				{ "class A { { } }", "1:11: an initializer block" },
				{ "interface I { default void m() { } }", "1:15: a default method" },
				{ "interface I { int N = 1; }", "1:19: an interface constant" },
				{ "sealed interface I permits A { }", "1:1: a sealed type" },
				{ "class A { synchronized void m() { } }", "1:11: the modifier synchronized" },
				{ "class A { volatile int x; }", "1:11: the modifier volatile" },
				{ "void m(int... xs) { }", "1:11: a variable arity parameter" },
				{ "import static java.lang.Math.max; void main() { }", "1:8: a static import" },
				{ "import module java.sql; void main() { }", "1:1: the module java.sql" },
				{ "void main() { var r = new Object() { }; }", "1:36: an anonymous class" },
				{ "void main() { class Local { } }", "1:15: a local class" },
				{ "void main() { float f = 1; }", "1:15: the type float" },
				{ "void main() { double f = 1.0f; }", "1:26: the type float" },
				{ "void main() { byte b = 1; }", "1:15: the type byte" },
				{ "void main() { String s = \"\"\"\n x\"\"\"; }", "1:26: a text block" },
				{ "void main() { int[][] g = new int[2][3]; }",
						"1:37: an array of several dimensions made with its lengths" },
				{ "void main() { synchronized (this) { } }", "1:15: a synchronized statement" },
				{ "void main() { assert true; }", "1:15: an assert statement" },
				{ "void main() { l: for (;;) { break l; } }", "1:15: a labeled statement" },
				{ "void main() { for (;;) { continue l; } }", "1:35: a labeled continue" },
				{ "void main() { try (var r = open()) { } }", "1:19: a try-with-resources statement" },
				{ "void main() { try { } catch (IllegalStateException | ArithmeticException e) { } }",
						"1:52: a multi-catch" },
				{ "void main() { try { } finally { return; } }", "1:33: a return inside a finally block" },
				{ "void main() { for (;;) { try { } finally { break; } } }", "1:44: a break leaving a finally block" },
				{ "void main() { int _ = 1; }", "1:19: the unnamed variable _" },
				{ "void main() { Object o = \"s\"; if (o instanceof String s) { } }", "1:55: a pattern" },
				{ "void main() { Runnable r = this::main; }", "1:32: a method reference" },
				{ "void main() { Object o = int.class; }", "1:26: a class literal" },
				{ "void main() { String a = \"a\", b = \"b\"; IO.println(a == b); }",
						"1:53: == of two strings, which compares references (equals compares text)," },
				{ "void main() { Integer a = 1, b = 1; IO.println(a != b); }",
						"1:50: != of two boxed values, which compares references," },
				{ "void main() { java.util.function.Function<String, String> f = x -> x; }",
						"1:15: the class java.util.function.Function" },
				{ "import java.util.Scanner; void main() { Scanner s = null; }", "1:41: the class java.util.Scanner" },
				{ "void main() { var xs = new ArrayList<String>(); xs.forEach(x -> IO.println(x)); }",
						"1:52: ArrayList.forEach(<lambda>)" },
				{ "void main() { List<String> xs = List.of(\"a\"); }", "1:38: List.of(String)" },
				{ "void main() { var s = new String(\"a\"); }", "1:23: new String(String)" },
				{ "void main() { Map<Double, String> m = new HashMap<>(); }", "1:15: a map keyed by Double" },
				{ "void main() { List xs = null; }", "1:15: the raw type List" },
				{ "void main() { var m = new HashMap<String, Integer>(); IO.println(m); }", "1:58: the text of a map" },
				{ "void main() { var xs = new ArrayList<String>(); IO.println(xs.equals(xs)); }",
						"1:63: ArrayList.equals(Object)" },
				{ "void main() { IO.println(getClass()); }",
						"1:26: getClass() but for its getName() and getSimpleName()" },
				{ "class A { int x; } class B extends A { int x; } void main() { }", "1:44: a field hiding A.x" },
				{ "class A { static int x = f(); static int f() { return 1; } } void main() { }",
						"1:26: a static field initializer that runs the program's code" },
				{ "class A { static int x = B.y; } class B { static int y = 2; } void main() { }",
						"1:28: a static field initializer reading B.y, which is set by the time it runs only if the class was initialized first," },
				{ "class A { A() { int x = 1; this(x); } A(int x) { } } void main() { }",
						"1:28: a statement ahead of this(..) or super(..)" },
				{ "class A extends ArrayList<String> { } void main() { }", "1:17: a parameterized supertype" },
				{ "class String { } void main() { }",
						"1:7: a class named as java.lang.String, which the program also uses," },
				{ "interface F { void f(); } interface G { void g(); } void main() { F f = () -> { }; G g = () -> { }; Object o = f; IO.println(o instanceof F); }",
						"1:128: instanceof or a cast to F, which some lambdas of the program implement and others do not," } };
		for (String[] refusal : refusals) {
			assertThatThrownBy(() -> lower(refusal[0], "Refused.java")).as("%s", refusal[0])
				.isInstanceOf(LispReadException.class)
				.hasMessage("Refused.java:" + refusal[1] + " is not supported by the experimental Java subset yet");
		}
	}

	@Test
	void anErrorTheLoweringDependsOnIsReportedAtItsPosition() {
		String[][] errors = {
				{ "void main() { int x = \"s\"; }", "1:19: incompatible types: String cannot be converted to int" },
				{ "void main() { int x = 1L; }", "1:19: incompatible types: long cannot be converted to int" },
				{ "void main() { char c = 70000; }", "1:20: incompatible types: int cannot be converted to char" },
				{ "void main() { foo(); }", "1:15: cannot find symbol: method foo()" },
				{ "void main() { IO.println(y); }", "1:26: cannot find symbol: variable y" },
				{ "void main() { Foo f = null; }", "1:15: cannot find symbol: class Foo" },
				{ "class A extends Thread { } void main() { }", "1:17: cannot find symbol: class Thread" },
				{ "void main() { int x = 1; int x = 2; }", "1:30: variable x is already defined" },
				{ "void main() { break; }", "1:15: break outside of loop" },
				{ "void main() { 1 + 2; }", "1:17: not a statement" },
				{ "int f() { return; } void main() { }", "1:11: incompatible types: missing return value" },
				{ "void main() { String s = (String) 1; }",
						"1:26: incompatible types: int cannot be converted to String" },
				{ "class A { } class B { } void main() { A a = new A(); B b = (B) a; }",
						"1:60: incompatible types: A cannot be converted to B" },
				{ "static void f(int x) { } static void f(long x, int y) { } void main() { f(\"s\"); }",
						"1:73: no suitable method f found for the arguments (String)" },
				{ "static void f(int x, long y) { } static void f(long x, int y) { } void main() { f(1, 2); }",
						"1:81: reference to method f is ambiguous" },
				{ "interface I { void m(); } class A implements I { } void main() { }",
						"1:33: A is not abstract and does not override abstract method m() in I" },
				{ "abstract class A { } void main() { new A(); }", "1:36: A is abstract; cannot be instantiated" },
				{ "class A { int x; static int f() { return x; } } void main() { }",
						"1:42: non-static variable this cannot be referenced from a static context" },
				{ "class A { }",
						"1:7: no main method found in A: the program starts at the first class of the file or the class named as the file" },
				{ "void main() { int x = 2147483648; }", "1:23: integer number too large" },
				{ "void main() { IO.println(1 +); }", "1:29: unexpected ')'" },
				{ "void main() { String s = \"abc", "1:30: unterminated string literal" },
				{ "void main() { char c = 'ab'; }", "1:24: unterminated character literal" },
				{ "void main() { int x = 1 # 2; }", "1:25: illegal character: '#'" } };
		for (String[] error : errors) {
			assertThatThrownBy(() -> lower(error[0], "Wrong.java")).as("%s", error[0])
				.isInstanceOf(LispReadException.class)
				.hasMessage("Wrong.java:" + error[1]);
		}
	}

}
