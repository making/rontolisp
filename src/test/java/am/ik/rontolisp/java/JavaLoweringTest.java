package am.ik.rontolisp.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.rontolisp.LispVal;
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
						        if (loop(new int[] {1, 2}) == 3) throw new IllegalStateException("x");
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
					"(DEFVAR |Base.count|)",
					"(DEFUN |Base.<init>(String)| (|this| |label|) (PROGN (SETF (|Base.label| |this|) \"b\") (SETF "
							+ "(|Base.label| |this|) |label|) (SETQ |Base.count| (- (LOGAND (+ (+ |Base.count| 1) 2147483648) "
							+ "4294967295) 2147483648))))",
					"(DEFUN |Base.toString()| (|this|) (%STRING-CONCAT (OR (|Base.label| |this|) \"null\") (RONTOLISP::%JAVA-DOUBLE-STRING "
							+ "(|Base.area()%v| |this|))))",
					"(DEFUN |Circle.new(double)| (|r|) (LET ((|this| (|%make-Circle|))) (|Circle.<init>(double)| "
							+ "|this| |r|) |this|))",
					"(DEFUN |Circle.area()| (|this|) (* (* 3.0 (|Circle.r| |this|)) (|Circle.r| |this|)))",
					"(DEFUN |Rect.new()| NIL (LET ((|this| (|%make-Rect|))) (|Rect.<init>()| |this|) |this|))",
					"(DEFUN |L1.twice(int)| (|x|) (- (LOGAND (+ (* |x| 2) 2147483648) 4294967295) 2147483648))",
					"(DEFUN |L1.wide(long,int)| (|x| |y|) (%MASK-SIGNED-FIELD 64 (ASH (%MASK-SIGNED-FIELD 64 "
							+ "(+ |x| |y|)) 3)))",
					"(DEFUN |L1.show(Shape,Object,char,boolean)| (|s| |o| |c| |b|) (%STRING-CONCAT (%STRING-CONCAT "
							+ "(%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT \"s=\" (|%string| |s|)) (|%string| |o|)) (RONTOLISP::%JAVA-CHAR-STRING "
							+ "|c|)) (IF |b| \"true\" \"false\")) (RONTOLISP::%JAVA-DOUBLE-STRING (|Shape.area()%v| |s|))))",
					"(DEFUN |L1.loop(int[])| (|xs|) (LET ((|sum| 0)) (LET* ((|i| 0)) (BLOCK |%break| (WHILE (< "
							+ "|i| (LENGTH (OR |xs| (RONTOLISP::%JAVA-NPE)))) (BLOCK |%continue| (IF (< (- (LOGAND (+ (AREF "
							+ "|xs| |i|) 2147483648) 4294967295) 2147483648) 0) (RETURN-FROM |%continue| NIL)) (IF (> (- "
							+ "(LOGAND (+ (AREF |xs| |i|) 2147483648) 4294967295) 2147483648) 99) (RETURN-FROM |%break| "
							+ "NIL)) (SETQ |sum| (- (LOGAND (+ (+ |sum| (- (LOGAND (+ (AREF |xs| |i|) 2147483648) 4294967295) "
							+ "2147483648)) 2147483648) 4294967295) 2147483648))) (SETQ |i| (- (LOGAND (+ (+ |i| 1) 2147483648) "
							+ "4294967295) 2147483648))))) |sum|))",
					"(DEFUN |L1.guarded(Object,int)| (|o| |d|) (BLOCK |%fn| (UNWIND-PROTECT (HANDLER-CASE (LET "
							+ "((|c| (LET ((|%t0| |o|)) (IF (OR (NULL |%t0|) (|Circle?| |%t0|)) |%t0| (RONTOLISP::%JAVA-THROW "
							+ "(|ClassCastException.new(String)| (IF (FUNCTIONP |%t0|) NIL (%STRING-CONCAT (%STRING-CONCAT "
							+ "\"class \" (|%class-name| |%t0|)) \" cannot be cast to class Circle\"))) #'|Throwable.toString()|))))) "
							+ "(RETURN-FROM |%fn| "
							+ "(- (LOGAND (+ (TRUNCATE (RONTOLISP::%JAVA-DOUBLE-TO-INT (|Circle.r| |c|)) |d|) 2147483648) "
							+ "4294967295) 2147483648))) ((SATISFIES |%catch0|) (|%t1|) (LET ((|e| (OR "
							+ "(RONTOLISP::%JAVA-THROWN |%t1|) (|%lisp-error| |%t1|)))) (RETURN-FROM |%fn| -1)))) "
							+ "(SETQ |Base.count| 10))))",
					// a catch clause's type is its test: a condition no clause takes goes
					// on as it came, signalled by nothing
					"(DEFUN |%catch0| (|%c|) (|ArithmeticException?| (OR (RONTOLISP::%JAVA-THROWN |%c|) "
							+ "(|%lisp-error| |%c|))))",
					"(DEFUN |L1.adder(int)| (|n|) (LAMBDA (|x|) (- (LOGAND (+ (+ |x| |n|) 2147483648) 4294967295) "
							+ "2147483648)))",
					"(DEFUN |L1.main(String[])| (|args|) (LET ((|s| (|Circle.new(double)| 1.5))) (LET ((|boxed| "
							+ "(|L1.twice(int)| 3))) (LET ((|flag| (IF (> (OR |boxed| (RONTOLISP::%JAVA-NULL-UNBOX)) 3) "
							+ "T :FALSE))) (WRITE-LINE (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT (OR (|L1.show(Shape,Object,char,boolean)| "
							+ "|s| |boxed| 99 (RONTOLISP::%JAVA-UNBOX-BOOLEAN |flag|)) \"null\") (%DECIMAL-PIECE (FUNCALL "
							+ "(OR (|L1.adder(int)| 1) (RONTOLISP::%JAVA-NPE)) 2))) (IF (|Rect?| |s|) \"true\" \"false\")) "
							+ "(IF (NOT (NULL |s|)) \"true\" \"false\"))) (IF (= (|L1.loop(int[])| (MAKE-ARRAY 2 :ELEMENT-TYPE "
							+ "'(UNSIGNED-BYTE 32) :INITIAL-CONTENTS (LIST 1 2))) 3) (RONTOLISP::%JAVA-THROW (|IllegalStateException.new(String)| "
							+ "\"x\") #'|Throwable.toString()|))))))",
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
					"(%REPORTING-UNCAUGHT #'RONTOLISP::%JAVA-UNCAUGHT-THROWN-LINE (%HOST-EXIT 1) (SETQ |Base.count| 0) "
							+ "(SETQ |Base.LIMIT| 10) (|L1.main(String[])| (VECTOR)))");
		// every exception class the run time's table names is one the lowering can make
		Matcher named = Pattern.compile("\"(java\\.(?:lang|util)\\.\\w+Exception)\"").matcher(runtimeLibrary());
		while (named.find()) {
			assertThat(JavaLowering.RAISED).as("java.lisp names %s", named.group(1)).contains(named.group(1));
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
		// before main.
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
					"(DEFSTRUCT (|Circle| (:INCLUDE |Record|) (:CONC-NAME |Circle.|) (:CONSTRUCTOR |%make-Circle| "
							+ "NIL) (:PREDICATE |Circle?|) (:COPIER NIL)) (|r| 0.0))",
					"(DEFSTRUCT (|L2.Inner| (:CONC-NAME |L2.Inner.|) (:CONSTRUCTOR |%make-L2.Inner| NIL) (:PREDICATE "
							+ "|L2.Inner?|) (:COPIER NIL)) (|this$0| NIL))",
					"(DEFSTRUCT (|j%L2$1| (:CONC-NAME L2$1.) (:CONSTRUCTOR |%make-L2$1| NIL) (:PREDICATE |j%L2$1?|) "
							+ "(:COPIER NIL)) (|val$step$0| NIL))",
					"(DEFUN |Circle.toString()| (|this|) (%STRING-CONCAT (%STRING-CONCAT \"Circle[r=\" (RONTOLISP::%JAVA-DOUBLE-STRING "
							+ "(|Circle.r| |this|))) \"]\"))",
					"(DEFUN |Circle.equals(Object)| (|this| |$o|) (LET ((|$that| NIL)) (AND (AND (|Circle?| |$o|) "
							+ "(PROGN (SETQ |$that| |$o|) T)) (= (RONTOLISP::%JAVA-DOUBLE-COMPARE (|Circle.r| |this|) (|Circle.r| "
							+ "|$that|)) 0))))",
					"(DEFUN |Log.line()| NIL (PROGN (|Log.<clinit>|) (SETQ |Log.lines| (- (LOGAND (+ (+ |Log.lines| "
							+ "1) 2147483648) 4294967295) 2147483648))))",
					"(DEFUN |L2.name(int)| (|n|) (COND ((EQL |n| 1) \"one\") ((OR (EQL |n| 2) (EQL |n| 3)) \"few\") "
							+ "(T \"many\")))",
					"(DEFUN |L2.fall(int)| (|n|) (LET ((|acc| 0)) (BLOCK |%break| (LET ((|%t0| (COND ((EQL |n| "
							+ "1) 0) ((EQL |n| 2) 1) (T 2)))) (IF (<= |%t0| 0) (SETQ |acc| (- (LOGAND (+ (+ |acc| 1) 2147483648) "
							+ "4294967295) 2147483648))) (IF (<= |%t0| 1) (PROGN (SETQ |acc| (- (LOGAND (+ (+ |acc| 10) "
							+ "2147483648) 4294967295) 2147483648)) (RETURN-FROM |%break| NIL))) (IF (<= |%t0| 2) (SETQ "
							+ "|acc| -1)))) |acc|))",
					"(DEFUN |L2.area(Shape)| (|s|) (LET ((|c| NIL) (|side| 0) (|q| NIL)) (COND ((NULL |s|) (RONTOLISP::%JAVA-NULL-SWITCH)) "
							+ "((AND (|Circle?| |s|) (PROGN (SETQ |c| |s|) T)) (* (* 3.0 (|Circle.r()| (OR |c| (RONTOLISP::%JAVA-NPE)))) "
							+ "(|Circle.r()| (OR |c| (RONTOLISP::%JAVA-NPE))))) ((AND (AND (|Square?| |s|) (PROGN (SETQ "
							+ "|side| (|Square.side| |s|)) T)) (> |side| 1)) (FLOAT (- (LOGAND (+ (* |side| |side|) 2147483648) "
							+ "4294967295) 2147483648) 1.0)) ((AND (|Square?| |s|) (PROGN (SETQ |q| |s|) T)) 1.0) (T (RONTOLISP::%JAVA-NO-MATCH)))))",
					"(DEFUN |L2.rank(Level)| (|l|) (COND ((EQ |l| |Level.LOW|) 1) ((EQ |l| |Level.HIGH|) (LET "
							+ "((|r| 2)) (- (LOGAND (+ (* |r| 2) 2147483648) 4294967295) 2147483648))) ((NULL |l|) (RONTOLISP::%JAVA-NULL-SWITCH)) "
							+ "(T (RONTOLISP::%JAVA-NO-MATCH))))",
					"(DEFUN |L2.size(Object)| (|o|) (LET ((|s| NIL)) (IF (NOT (AND (SIMPLE-STRING-P |o|) (PROGN "
							+ "(SETQ |s| |o|) T))) -1 (LENGTH (OR |s| (RONTOLISP::%JAVA-NPE))))))",
					"(DEFUN |L2.sum(int[])| (|xs|) (LET ((|t| 0)) (BLOCK |outer%break| (LET* ((|%t1| (OR |xs| "
							+ "(RONTOLISP::%JAVA-NPE))) (|%t3| (LENGTH |%t1|)) (|%t2| 0)) (WHILE (< |%t2| |%t3|) (LET ((|x| "
							+ "(- (LOGAND (+ (AREF |%t1| |%t2|) 2147483648) 4294967295) 2147483648))) (IF (< |x| 0) (RETURN-FROM "
							+ "|outer%break| NIL)) (SETQ |t| (- (LOGAND (+ (+ |t| |x|) 2147483648) 4294967295) 2147483648))) "
							+ "(SETQ |%t2| (+ |%t2| 1))))) |t|))",
					"(DEFUN |L2$1.run()| (|this|) (PROGN (|Log.line()|) (WRITE-LINE (%DECIMAL-PIECE (|L2$1.val$step$0| "
							+ "|this|)))))",
					"(DEFUN |L2$1.new()| (|%c0|) (LET ((|this| (|%make-L2$1|))) (SETF (|L2$1.val$step$0| |this|) "
							+ "|%c0|) |this|))",
					"(DEFUN |L2.Inner.twice()| (|this|) (- (LOGAND (+ (* (|L2.base| (|L2.Inner.this$0| |this|)) "
							+ "2) 2147483648) 4294967295) 2147483648))",
					"(DEFUN |L2.Inner.new()| (|%outer|) (LET ((|this| (|%make-L2.Inner|))) (SETF (|L2.Inner.this$0| "
							+ "|this|) |%outer|) |this|))",
					"(DEFUN |Log.<clinit>| NIL (IF |Log.<clinit>%done| NIL (PROGN (SETQ |Log.<clinit>%done| T) "
							+ "(WRITE-LINE \"log\"))))",
					"(%REPORTING-UNCAUGHT #'RONTOLISP::%JAVA-UNCAUGHT-LINE (%HOST-EXIT 1) (SETQ |Level.$VALUES| NIL) (SETQ |Log.lines| 0) (SETQ |Level.LOW| (|Level.new(String,int)| "
							+ "\"LOW\" 0)) (SETQ |Level.HIGH| (|Level.new(String,int)| \"HIGH\" 1)) (SETQ |Level.$VALUES| (VECTOR "
							+ "|Level.LOW| |Level.HIGH|)) (SETQ |Level.ALL| (|Level.values()|)) (SETQ |Log.<clinit>%done| "
							+ "NIL) (|L2.main(String[])| (VECTOR)))");
	}

	@Test
	void typeParametersLambdasAndBoxes() {
		// A type parameter is erased: a method's name has its bound, and a member typed
		// by one is converted where the type it is read through gives a box. An override
		// through a type parameter is one entry of the dispatcher, beside the predicates
		// of the library's own values. A lambda is a function, called by funcall; the
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
					"(DEFUN |Box.above(Comparable)| (|this| |other|) (> (|Comparable.compareTo(Object)%v| (|Box.held| "
							+ "|this|) |other|) 0))",
					"(DEFUN |Box.with(java.util.function.Function)| (|this| |f|) (FUNCALL (OR |f| (RONTOLISP::%JAVA-NPE)) "
							+ "(|Box.held| |this|)))",
					"(DEFUN |L3.pick(Object,Object)| (|a| |b|) (IF (NOT (NULL |a|)) |a| |b|))",
					"(DEFUN |L3.sum(Long,Integer)| (|a| |b|) (%MASK-SIGNED-FIELD 64 (+ (CAR (OR |a| (RONTOLISP::%JAVA-NULL-UNBOX))) "
							+ "(OR |b| (RONTOLISP::%JAVA-NULL-UNBOX)))))",
					"(DEFUN |L3.main(String[])| (|args|) (LET ((|v| (|Box.new(Comparable)| (|Version.new(int)| "
							+ "2)))) (LET ((|s| (|Box.new(Comparable)| \"b\"))) (LET ((|named| (|%make-Named%lambda| (LAMBDA "
							+ "NIL \"n\")))) (LET ((|task| (LAMBDA NIL NIL))) (LET ((|o| |named|)) (LET ((|big| (CONS 5 :LONG))) "
							+ "(LET ((|len| (OR (|Box.with(java.util.function.Function)| (OR |s| (RONTOLISP::%JAVA-NPE)) "
							+ "(LAMBDA (|%t0|) (LENGTH (OR |%t0| (RONTOLISP::%JAVA-NPE))))) (RONTOLISP::%JAVA-NULL-UNBOX)))) "
							+ "(LET ((|n| NIL)) (WRITE-LINE (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT "
							+ "(%STRING-CONCAT (IF (|Box.above(Comparable)| (OR |v| (RONTOLISP::%JAVA-NPE)) (|Version.new(int)| "
							+ "1)) \"true\" \"false\") \" \") (IF (|Box.above(Comparable)| (OR |s| (RONTOLISP::%JAVA-NPE)) \"a\") "
							+ "\"true\" \"false\")) (OR (IF (|Named%is| |o|) (PROGN (SETQ |n| |o|) (|Named.greet()%v| "
							+ "|n|)) \"\") \"null\")) (%DECIMAL-PIECE (|L3.sum(Long,Integer)| |big| |len|))) (RONTOLISP::%JAVA-TO-STRING "
							+ "(|L3.pick(Object,Object)| 1 2))))) (FUNCALL (OR |task| (RONTOLISP::%JAVA-NPE)))))))))))",
					"(DEFUN |Named.name()%v| (|%o|) (COND ((|Named%lambda?| |%o|) (FUNCALL (|Named%lambda-%f| "
							+ "|%o|))) (T (RONTOLISP::%JAVA-NO-RECEIVER |%o| \"name()\"))))",
					"(DEFUN |Comparable.compareTo(Object)%v| (|%o| |%a0|) (COND ((|Version?| |%o|) (|Version.compareTo(Version)| "
							+ "|%o| |%a0|)) ((SIMPLE-STRING-P |%o|) (RONTOLISP::%JAVA-STRING-COMPARE |%o| |%a0|)) ((RONTOLISP::%JAVA-LONG-P "
							+ "|%o|) (RONTOLISP::%JAVA-COMPARE (CAR |%o|) (CAR |%a0|))) ((INTEGERP |%o|) (RONTOLISP::%JAVA-COMPARE "
							+ "|%o| |%a0|)) (T (RONTOLISP::%JAVA-NO-RECEIVER |%o| \"compareTo(Object)\"))))",
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
						+ "NIL)) (|items| NIL) (|count| 0) (|changes| 0))",
				"(DEFUN |Bag.iterator()| (|this|) (|java.util.ArrayList.iterator()| (OR (|Bag.items| |this|) "
						+ "(RONTOLISP::%JAVA-NPE))))",
				"(DEFUN |L4.main(String[])| (|args|) (LET ((|bag| (|Bag.new()|))) (|java.util.ArrayList.add(Object)| "
						+ "(OR (|Bag.items| |bag|) (RONTOLISP::%JAVA-NPE)) |%string0|) (LET ((|first| |%string0|)) "
						+ "(LET* ((|%t0| (|Bag.iterator()| (OR |bag| (RONTOLISP::%JAVA-NPE))))) (WHILE (|java.util.ArrayList.Each.hasNext()| "
						+ "|%t0|) (LET ((|item| (|java.util.ArrayList.Each.next()| |%t0|))) (WRITE-LINE (IF (EQ |item| "
						+ "|first|) \"true\" \"false\"))))) (LET ((|ns| (MAKE-ARRAY 2 :ELEMENT-TYPE '(UNSIGNED-BYTE 32) "
						+ ":INITIAL-CONTENTS (LIST 2 1)))) (|java.util.Arrays.sort(int[])| |ns|) (WRITE-LINE (RONTOLISP::%JAVA-DOUBLE-STRING "
						+ "(+ (FLOAT (|rontolisp.internal.Support.parseInt(String,int)| |%string1| 10) 1.0) (%STRICT-MATH "
						+ ":SQRT (FLOAT (- (LOGAND (+ (AREF |ns| 0) 2147483648) 4294967295) 2147483648) 1.0)))))))))",
				"(DEFUN |java.util.Arrays.sort(int[])| (|a|) (RONTOLISP::%JAVA-SORT-INTS |a| 0 (LENGTH (OR "
						+ "|a| (RONTOLISP::%JAVA-NPE)))))");
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
				"(DEFUN |L5.main(String[])| (|args|) (PROGN (WRITE-LINE \"1\") (WRITE-STRING (RONTOLISP::%JAVA-FORMAT "
						+ "\"%d%n\" (VECTOR 2) #'|%string|)) (LET ((|out| (|java.io.StandardOutput.out()|))) "
						+ "(|java.io.PrintStream.println(int)| (OR |out| (RONTOLISP::%JAVA-NPE)) 3) "
						+ "(|java.io.StandardOutput.flush()| (OR (|java.io.PrintStream.printf(String,Object[])| "
						+ "(OR (|java.io.StandardOutput.err()|) (RONTOLISP::%JAVA-NPE)) \"%d%n\" (VECTOR 4)) "
						+ "(RONTOLISP::%JAVA-NPE))))))",
				"(DEFUN |java.io.StandardOutput.text(String,boolean)| (|this| |s| |line|) (PROGN (WRITE-STRING |s| (IF "
						+ "(|java.io.StandardOutput.error| |this|) *ERROR-OUTPUT* *STANDARD-OUTPUT*)) (IF |line| "
						+ "(WRITE-CHAR (CODE-CHAR 10) (IF (|java.io.StandardOutput.error| |this|) *ERROR-OUTPUT* "
						+ "*STANDARD-OUTPUT*)))))");
	}

	@Test
	void theLibrarysSourceIsJavaAJdkCompiles() throws IOException {
		// The library is read by this front end only, which takes more than the language
		// does (an access across packages, a checked exception nothing declares): the
		// compiler of the JDK running the test compiles every file of it, as a patch of
		// its own java.base, with no error. A native member is Java too.
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
			compiler
				.getTask(null, files, reported,
						List.of("--patch-module", "java.base=" + library, "-d", classes.toString(), "-proc:none",
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
				"(DEFUN |demo.app.T.CDR(int)| (|this| |j%LIST|) (- (LOGAND (+ (+ (- (LOGAND (+ (+ |j%LIST| "
						+ "(|demo.app.T.j%CAR| |this|)) 2147483648) 4294967295) 2147483648) 16) 2147483648) 4294967295) "
						+ "2147483648))",
				"(DEFUN |demo.app.Main.main(String[])| (|args|) (LET ((|j%T| (|demo.app.T.new()|))) (LET "
						+ "((|c| 98)) (LET ((|big| 2147483648)) (WRITE-LINE (%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT "
						+ "(%STRING-CONCAT (%STRING-CONCAT (%STRING-CONCAT (%DECIMAL-PIECE (|demo.app.T.CDR(int)| (OR "
						+ "|j%T| (RONTOLISP::%JAVA-NPE)) 2)) \"n=16y\") (RONTOLISP::%JAVA-CHAR-STRING |c|)) (%DECIMAL-PIECE "
						+ "|big|)) \"2147483647\") \"-0.0\") \"4.5\"))))))",
				"(%REPORTING-UNCAUGHT #'RONTOLISP::%JAVA-UNCAUGHT-LINE (%HOST-EXIT 1) (SETQ |demo.app.T.NIL| 16) "
						+ "(SETQ |demo.app.T.TEXT| \"n=16y\") (|demo.app.Main.main(String[])| (VECTOR)))");
	}

	@Test
	void aMonitorIsEnteredAndLeftAndAThreadIsTheRunTimes() {
		// synchronized enters the monitor of its object -- the class's for a static
		// method and a class literal -- and leaves it however the body ends; a volatile
		// field is read and written one access at a time; wait and notify are
		// Thread.java's
		// over the run time's; a started thread runs its run(), reports what it leaves
		// uncaught and ends; a program that starts one initializes a class under a lock
		// and ends when its threads have.
		assertThat(lower("""
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
				class T1 {
				    public static void main(String[] args) throws InterruptedException {
				        Cell cell = new Cell();
				        Thread worker = new Thread(() -> cell.add(cell));
				        worker.start();
				        worker.join();
				        cell.twice(cell);
				        System.out.println(Cell.bump() + cell.held + Lazy.made);
				    }
				}
				""", "T1.java")).contains(
				"(DEFUN |Cell.add(Cell)| (|this| |other|) (LET ((|%t1| (RONTOLISP::%JAVA-MONITOR-ENTER |this|))) "
						+ "(UNWIND-PROTECT (PROGN (SETF (|Cell.held| |this|) (- (LOGAND (+ (+ (|Cell.held| |this|) 1) "
						+ "2147483648) 4294967295) 2147483648)) (LET* ((|%t0| (RONTOLISP:WITH-MUTEX "
						+ "((RONTOLISP::%JAVA-VOLATILE)) (|Cell.next| |other|)))) (RONTOLISP:WITH-MUTEX "
						+ "((RONTOLISP::%JAVA-VOLATILE)) (SETF (|Cell.next| |this|) |%t0|)))) "
						+ "(RONTOLISP::%JAVA-MONITOR-EXIT |%t1|))))",
				"(DEFUN |Cell.bump()| NIL (LET ((|%t3| (RONTOLISP::%JAVA-MONITOR-ENTER '|Cell|))) (UNWIND-PROTECT "
						+ "(PROG1 (RONTOLISP:WITH-MUTEX ((RONTOLISP::%JAVA-VOLATILE)) |Cell.hits|) (LET* ((|%t2| (- "
						+ "(LOGAND (+ (+ (RONTOLISP:WITH-MUTEX ((RONTOLISP::%JAVA-VOLATILE)) |Cell.hits|) 1) "
						+ "2147483648) 4294967295) 2147483648))) (RONTOLISP:WITH-MUTEX ((RONTOLISP::%JAVA-VOLATILE)) "
						+ "(SETQ |Cell.hits| |%t2|)))) (RONTOLISP::%JAVA-MONITOR-EXIT |%t3|))))",
				"(DEFUN |Cell.twice(Object)| (|this| |lock|) (LET ((|%t5| (RONTOLISP::%JAVA-MONITOR-ENTER |lock|))) "
						+ "(UNWIND-PROTECT (PROGN (LET ((|%t4| (RONTOLISP::%JAVA-MONITOR-ENTER '|Cell|))) "
						+ "(UNWIND-PROTECT (SETF (|Cell.held| |this|) (- (LOGAND (+ (+ (|Cell.held| |this|) 2) "
						+ "2147483648) 4294967295) 2147483648)) (RONTOLISP::%JAVA-MONITOR-EXIT |%t4|))) "
						+ "(|Thread.await(Object,long)| (OR |lock| (RONTOLISP::%JAVA-NPE)) 0) "
						+ "(|Thread.signal(Object,boolean)| (OR |lock| (RONTOLISP::%JAVA-NPE)) T)) "
						+ "(RONTOLISP::%JAVA-MONITOR-EXIT |%t5|))))",
				"(DEFUN |Thread.start()| (|this|) (PROGN (LET ((|%t6| (RONTOLISP::%JAVA-MONITOR-ENTER |this|))) "
						+ "(UNWIND-PROTECT (PROGN (IF (|Thread.started| |this|) (RONTOLISP::%JAVA-THROW "
						+ "(|IllegalThreadStateException.new()|) #'|Throwable.toString()|)) (SETF (|Thread.started| "
						+ "|this|) T) (RONTOLISP:WITH-MUTEX ((RONTOLISP::%JAVA-VOLATILE)) (SETF (|Thread.alive| |this|) "
						+ "T))) (RONTOLISP::%JAVA-MONITOR-EXIT |%t6|))) (LET ((|%t7| |this|)) "
						+ "(RONTOLISP::%JAVA-THREAD-START (|Thread.carrier| |this|) (|Thread.daemon| |this|) (LAMBDA "
						+ "NIL (UNWIND-PROTECT (HANDLER-CASE (|Thread.body(Thread)| |%t7|) (ERROR (|%t8|) "
						+ "(|Thread.report(Thread,String)| |%t7| (RONTOLISP::%JAVA-THROWN-TEXT |%t8|)))) "
						+ "(|Thread.finish(Thread)| |%t7|)))))))",
				"(DEFUN |Lazy.<clinit>| NIL (IF (EQ |Lazy.<clinit>%done| :ENDED) NIL (RONTOLISP:WITH-MUTEX "
						+ "((RONTOLISP::%JAVA-INITIALIZING)) (IF |Lazy.<clinit>%done| NIL (PROGN (SETQ "
						+ "|Lazy.<clinit>%done| T) (WRITE-LINE \"made\") (SETQ |Lazy.<clinit>%done| :ENDED))))))",
				"(RONTOLISP::%JAVA-THREAD-MAIN (LAMBDA NIL (PROGN (SETQ |Cell.hits| 0) (SETQ |Lazy.made| 0) (SETQ "
						+ "|Thread.MIN_PRIORITY| 1) (SETQ |Thread.NORM_PRIORITY| 5) (SETQ |Thread.MAX_PRIORITY| 10) "
						+ "(SETQ |Thread.unnamed| 0) (SETQ |Thread.identifiers| 1) (SETQ |Lazy.<clinit>%done| NIL) "
						+ "(|T1.main(String[])| (VECTOR)))) (LAMBDA (|%t|) (|Thread.finish(Thread)| |%t|)))");
		// a program that starts no thread and neither waits nor asks cannot tell a
		// monitor
		// held from one that is not: synchronized leaves the exception a null has, the
		// program ends as any other and its classes are initialized under no lock
		assertThat(lower("""
				class Lazy { static int made; static { System.out.println("made"); } }
				class T2 {
				    int n;
				    synchronized void add(Object lock, T2 other) {
				        synchronized (lock) { n++; }
				        synchronized (T2.class) { n++; }
				        synchronized (other.self()) { n++; }
				    }
				    T2 self() { return this; }
				    static synchronized void main() { System.out.println(Lazy.made); new T2().add("x", new T2()); }
				}
				""", "T2.java")).contains(
				"(DEFUN |T2.add(Object,T2)| (|this| |lock| |other|) (PROGN (PROGN (OR |lock| (RONTOLISP::%JAVA-NPE)) "
						+ "(SETF (|T2.n| |this|) (- (LOGAND (+ (+ (|T2.n| |this|) 1) 2147483648) 4294967295) "
						+ "2147483648))) (SETF (|T2.n| |this|) (- (LOGAND (+ (+ (|T2.n| |this|) 1) 2147483648) "
						+ "4294967295) 2147483648)) (PROGN (OR (|T2.self()| (OR |other| (RONTOLISP::%JAVA-NPE))) "
						+ "(RONTOLISP::%JAVA-NPE)) (SETF (|T2.n| |this|) (- (LOGAND (+ (+ (|T2.n| |this|) 1) "
						+ "2147483648) 4294967295) 2147483648)))))",
				"(DEFUN |Lazy.<clinit>| NIL (IF |Lazy.<clinit>%done| NIL (PROGN (SETQ |Lazy.<clinit>%done| T) "
						+ "(WRITE-LINE \"made\"))))",
				"(%REPORTING-UNCAUGHT #'RONTOLISP::%JAVA-UNCAUGHT-LINE (%HOST-EXIT 1) (SETQ |Lazy.made| 0) "
						+ "(SETQ |Lazy.<clinit>%done| NIL) (|T2.main()|))");
	}

	@Test
	void aConstructTheSubsetDoesNotHaveIsRefusedByNameAtItsPosition() {
		String[][] refusals = { { "record P(int... xs) {}", "1:17: a variable arity record component" },
				{ "import module java.sql; void main() { }", "1:1: the module java.sql" },
				{ "void main() { assert true; }", "1:15: an assert statement" },
				{ "void main() { switch (1L) { default -> { } } }", "1:15: a switch over long" },
				{ "interface M { } void main() { Runnable r = (Runnable & M) () -> { }; }",
						"1:44: a lambda or a method reference cast to an intersection type" },
				{ "void main() { Object o = int.class; }", "1:26: a class literal" },
				{ "class A { transient int x; } void main() { }", "1:11: the modifier transient" },
				{ "import java.util.concurrent.locks.ReentrantLock; void main() { ReentrantLock l = null; }",
						"1:64: the class java.util.concurrent.locks.ReentrantLock" },
				{ "interface I { default void m() { } } class A implements I { Runnable r = I.super::m; } void main() { }",
						"1:81: a method reference through a qualified super" },
				{ "import java.util.Scanner; void main() { Scanner s = null; }", "1:41: the class java.util.Scanner" },
				{ "void main() { var xs = new ArrayList<String>(); IO.println(xs.stream()); }",
						"1:63: ArrayList.stream()" },
				{ "void main() { IO.println(\"a\".chars()); }", "1:30: String.chars()" },
				{ "void main() { IO.println(Collections.EMPTY_LIST); }", "1:38: Collections.EMPTY_LIST" },
				{ "void main() { var s = new StringBuilder(); s.appendCodePoint(65); }",
						"1:46: StringBuilder.appendCodePoint(int)" },
				{ "void main() { IO.println(getClass()); }",
						"1:26: getClass() but for its getName() and getSimpleName()" },
				{ "class A { int x; } class B extends A { int x; } void main() { }", "1:44: a field hiding A.x" },
				{ "class String { } class Refused { static void main() { } }",
						"1:7: a class named as java.lang.String, which the program also uses," } };
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
				{ "void main() { Integer i = 1; String[] a = (String[]) i; }",
						"1:43: incompatible types: Integer cannot be converted to String[]" },
				{ "void main() { char c = 70000; }", "1:20: incompatible types: int cannot be converted to char" },
				{ "void main() { foo(); }", "1:15: cannot find symbol: method foo()" },
				{ "void main() { IO.println(y); }", "1:26: cannot find symbol: variable y" },
				{ "void main() { Foo f = null; }", "1:15: cannot find symbol: class Foo" },
				{ "void main() { int x = 1; int x = 2; }", "1:30: variable x is already defined" },
				{ "class A { synchronized int x; } void main() { }", "1:28: modifier synchronized not allowed here" },
				{ "class A { volatile void m() { } } void main() { }", "1:25: modifier volatile not allowed here" },
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
				}
				""", "p/B.java", "package p; class B { public static int v() { return 1; } }", "p/Q.java",
				"package other; public class Q { }", "app/Helper.java", """
						package app;
						class Helper { static int twice(int x) { return 2 * x; } }
						""");
		JavaFiles files = (from, path) -> {
			String resolved = Path.of(from).resolveSibling(path).normalize().toString();
			String text = tree.get(resolved);
			return text == null ? null : new JavaFiles.Source(resolved, text);
		};
		// A class beside the file, a class of another package and a static import are
		// lowered with it, each from its own file.
		assertThat(Java.read("""
				package app;
				import static p.U.*;
				public class Main { public static void main(String[] a) { IO.println(Helper.twice(yes() + F)); } }
				""", "app/Main.java", files).stream().map(LispVal::print).toList()).contains(
				"(DEFUN |app.Helper.twice(int)| (|x|) (- (LOGAND (+ (* 2 |x|) 2147483648) 4294967295) 2147483648))",
				"(DEFUN |p.U.yes()| NIL 1)");
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
				{ "app/Main.java", "package app; import static p.U.nope; class Main { void main() { } }",
						"app/Main.java:1:14: cannot find symbol: static nope" },
				{ "app/Main.java", "package app; import static p.Nope.*; class Main { void main() { } }",
						"app/Main.java:1:28: cannot find symbol: class Nope" },
				{ "app/Main.java", "package app; import static p.U.*; class Main { void main() { inst(); } }",
						"app/Main.java:1:62: cannot find symbol: method inst()" },
				{ "app/Main.java", "package app; class Main { void main() { new p.Q(); } }",
						"p/Q.java:1:1: bad source file: p/Q.java: file does not contain class p.Q" } };
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
