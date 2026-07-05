package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the interpreter-only {@code java} interop package. Every case uses headless,
 * deterministic JDK classes (no Swing/AWT) so it runs anywhere.
 */
class JavaInteropTest {

	// Evaluates a sequence of top-level forms and returns the last result.
	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	@Test
	void newAndInstanceCall() {
		assertThat(eval("""
				(setq sb (java:new "java.lang.StringBuilder" "hi"))
				(java:call sb "append" "!")
				(java:call sb "toString")
				""")).isEqualTo(new LispString("hi!"));
	}

	@Test
	void staticCall() {
		assertThat(eval("(java:static \"java.lang.Integer\" \"parseInt\" \"100\")")).isEqualTo(new LispInteger(100));
	}

	@Test
	void staticReadsField() {
		assertThat(eval("(java:field \"java.lang.Integer\" \"MAX_VALUE\")")).isEqualTo(new LispInteger(2147483647));
	}

	@Test
	void instanceReadsField() {
		// java.awt.Point has public int fields x/y, and works headless.
		assertThat(eval("""
				(setq p (java:new "java.awt.Point" 3 4))
				(java:field p "y")
				""")).isEqualTo(new LispInteger(4));
	}

	// An integer argument prefers the int overload (returning an integer), not the
	// long/float/double ones -- this is the deterministic overload resolution.
	@Test
	void overloadResolutionPrefersIntForAnInteger() {
		LispVal result = eval("(java:static \"java.lang.Math\" \"max\" 3 7)");
		assertThat(result).isEqualTo(new LispInteger(7));
		assertThat(result).isInstanceOf(LispInteger.class);
	}

	// A float argument selects the double overload of the same method.
	@Test
	void overloadResolutionPrefersDoubleForAFloat() {
		assertThat(eval("(java:static \"java.lang.Math\" \"abs\" -5.5)")).isEqualTo(new LispDouble(5.5));
	}

	// When only a double overload exists, an integer is converted to it.
	@Test
	void integerConvertsToDoubleWhenNoIntegerOverload() {
		assertThat(eval("(java:static \"java.lang.Math\" \"sqrt\" 16)")).isEqualTo(new LispDouble(4.0));
	}

	// A boolean Java return (ArrayList.add) surfaces as t.
	@Test
	void booleanReturnSurfacesAsTrue() {
		assertThat(eval("""
				(setq lst (java:new "java.util.ArrayList"))
				(java:call lst "add" 42)
				""")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void collectionRoundTrip() {
		assertThat(eval("""
				(setq lst (java:new "java.util.ArrayList"))
				(java:call lst "add" 10)
				(java:call lst "add" 20)
				(list (java:call lst "size") (java:call lst "get" 1))
				""").print()).isEqualTo("(2 20)");
	}

	// A rontolisp lambda becomes a Runnable; calling run() runs the lambda (void return).
	@Test
	void proxyVoidReturnRunsTheLambda() {
		assertThat(eval("""
				(setq fired nil)
				(setq r (java:proxy "java.lang.Runnable" (lambda (method) (setq fired t))))
				(java:call r "run")
				fired
				""")).isEqualTo(LispTrue.INSTANCE);
	}

	// A proxy whose lambda returns a value: Supplier.get() returns the marshalled value.
	@Test
	void proxyValueReturn() {
		assertThat(eval("""
				(setq s (java:proxy "java.util.function.Supplier" (lambda (method) 42)))
				(java:call s "get")
				""")).isEqualTo(new LispInteger(42));
	}

	// A proxy that receives arguments: a Comparator drives Collections.sort.
	@Test
	void proxyReceivesArguments() {
		assertThat(eval("""
				(setq lst (java:new "java.util.ArrayList"))
				(java:call lst "add" 30)
				(java:call lst "add" 10)
				(java:call lst "add" 20)
				(java:static "java.util.Collections" "sort" lst
				  (java:proxy "java.util.Comparator" (lambda (method a b) (- a b))))
				(list (java:call lst "get" 0) (java:call lst "get" 2))
				""").print()).isEqualTo("(10 30)");
	}

	// A proper list marshals to a primitive array parameter; the int elements prefer
	// the int[] overload of binarySearch over long[]/double[]/Object[].
	@Test
	void listMarshalsToPrimitiveArrayParameter() {
		assertThat(eval("(java:static \"java.util.Arrays\" \"binarySearch\" (list 10 20 30 40) 30)"))
			.isEqualTo(new LispInteger(2));
	}

	// A proper list marshals to a Collection parameter as a java.util.List.
	@Test
	void listMarshalsToCollectionParameter() {
		assertThat(eval("(java:static \"java.util.Collections\" \"max\" (list 3 9 4))")).isEqualTo(new LispInteger(9));
	}

	// Nested lists marshal recursively (the inner lists become Lists boxed as Object).
	@Test
	void nestedListMarshalsRecursively() {
		assertThat(eval("(java:static \"java.util.Arrays\" \"deepToString\" (list (list 1 2) (list 3)))"))
			.isEqualTo(new LispString("[[1, 2], [3]]"));
	}

	// A rank-1 vector marshals like a list; string elements select the CharSequence[]
	// varargs parameter of String.join taken as-is (not element-packed).
	@Test
	void vectorMarshalsToArrayParameter() {
		assertThat(eval("""
				(setq v (make-array 2))
				(setf (aref v 0) "a")
				(setf (aref v 1) "b")
				(java:static "java.lang.String" "join" "-" v)
				""")).isEqualTo(new LispString("a-b"));
	}

	@Test
	void fillPointerVectorMarshalsUpToFillPointer() {
		// The fill pointer bounds the marshaled sequence, matching length/printing.
		assertThat(eval("""
				(setq v (make-array 3 :fill-pointer 0))
				(vector-push "a" v)
				(vector-push "b" v)
				(java:static "java.lang.String" "join" "-" v)
				""")).isEqualTo(new LispString("a-b"));
	}

	// A dotted (improper) list is not a sequence, so no overload matches.
	@Test
	void dottedListDoesNotMarshal() {
		assertThatThrownBy(() -> eval("(java:static \"java.util.Collections\" \"max\" (cons 1 2))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("No matching method");
	}

	// Extra trailing arguments are packed into the varargs array.
	@Test
	void varargsPacksTrailingArguments() {
		assertThat(eval("(java:static \"java.lang.String\" \"format\" \"%s-%s\" 1 \"x\")"))
			.isEqualTo(new LispString("1-x"));
	}

	// Eleven arguments exceed every fixed-arity List.of overload, forcing the varargs
	// one; and a varargs method also accepts an empty tail.
	@Test
	void varargsAcceptsAnyArity() {
		assertThat(eval("(java:call (java:static \"java.util.List\" \"of\" 1 2 3 4 5 6 7 8 9 10 11) \"size\")"))
			.isEqualTo(new LispInteger(11));
		assertThat(eval("(java:call (java:static \"java.util.List\" \"of\") \"size\")")).isEqualTo(new LispInteger(0));
	}

	// A returned Java array surfaces as a Lisp list, and a list marshals back into an
	// array parameter -- Arrays.copyOf(int[], int) round-trips both directions.
	@Test
	void arrayRoundTripsThroughCopyOf() {
		assertThat(eval("(java:static \"java.util.Arrays\" \"copyOf\" (list 1 2 3) 2)").print()).isEqualTo("(1 2)");
	}

	// A returned primitive array unmarshals element-wise; the IntStream implementation
	// class is JDK-internal, so this also exercises the accessible-method resolution.
	@Test
	void returnedPrimitiveArrayUnmarshalsToList() {
		assertThat(eval("(java:call (java:call (java:new \"java.lang.StringBuilder\" \"ab\") \"chars\") \"toArray\")")
			.print()).isEqualTo("(97 98)");
	}

	@Test
	void printsOpaquely() {
		assertThat(eval("(java:new \"java.lang.StringBuilder\")").print()).isEqualTo("#<java java.lang.StringBuilder>");
	}

	@Test
	void unknownClassSignals() {
		assertThatThrownBy(() -> eval("(java:new \"no.such.Class\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("No such class");
	}

	@Test
	void noMatchingMethodSignals() {
		assertThatThrownBy(() -> eval("""
				(setq sb (java:new "java.lang.StringBuilder"))
				(java:call sb "noSuchMethod" 1 2 3)
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("No matching method");
	}

	@Test
	void callOnNonObjectSignals() {
		assertThatThrownBy(() -> eval("(java:call 42 \"toString\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a java object");
	}

	@Test
	void proxyOnNonInterfaceSignals() {
		assertThatThrownBy(() -> eval("(java:proxy \"java.lang.String\" (lambda (m) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects an interface");
	}

}
