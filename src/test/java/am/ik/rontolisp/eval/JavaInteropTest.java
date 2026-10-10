package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.JavaImplementationPrograms;
import am.ik.rontolisp.testsupport.JavaInteropPrograms;
import am.ik.rontolisp.testsupport.JavaLibraryJar;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

	// A character argument marshals to an int parameter (the code point) when there is
	// no char/Character overload -- matching the JVM-compiled bridge's rule
	// (JavaBridgeTemplate.marshal).
	@Test
	void characterMarshalsToIntParameter() {
		assertThat(eval("(java:static \"java.lang.Character\" \"charCount\" #\\a)")).isEqualTo(new LispInteger(1));
	}

	// A supplementary code point cannot fit a single Java char, so it must be refused
	// the char/Character overload (which would otherwise silently truncate it) and fall
	// back to the int overload instead: Character.toString(int) over
	// Character.toString(char).
	@Test
	void supplementaryCodePointDoesNotNarrowToChar() {
		assertThat(eval("(java:static \"java.lang.Character\" \"toString\" (code-char 128512))"))
			.isEqualTo(new LispString(new String(Character.toChars(128512))));
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

	// The overload chosen for a call is remembered per argument kind. A float first
	// selects max(double,double); the integers after it must still select max(int,int)
	// (a shared choice would return 3.0), and the floats after those the double one.
	@Test
	void rememberedOverloadFollowsTheArgumentKind() {
		assertThat(eval("""
				(mapcar (lambda (x) (java:static "java.lang.Math" "max" x 0)) (list 2.5 3 4.5 5))
				""").print()).isEqualTo("(2.5 3 4.5 5)");
	}

	// The same method name on alternating receiver classes resolves on each class.
	@Test
	void rememberedOverloadFollowsTheReceiverClass() {
		assertThat(eval("""
				(mapcar (lambda (c) (java:call c "add" "x") (java:call c "size"))
				        (list (java:new "java.util.ArrayList") (java:new "java.util.LinkedList")
				              (java:new "java.util.ArrayList")))
				""").print()).isEqualTo("(1 1 1)");
	}

	// A one-character string may narrow to char (Writer.append(char)); a longer one
	// cannot and takes append(CharSequence). Alternating the two at one site.
	@Test
	void rememberedOverloadSeparatesOneCharacterStrings() {
		assertThat(eval("""
				(setq w (java:new "java.io.StringWriter"))
				(dolist (s (list "a" "bc" "d" "ef")) (java:call w "append" s))
				(java:call w "toString")
				""")).isEqualTo(new LispString("abcdef"));
	}

	// A list has no remembered kind: after a scalar, String.valueOf still takes the
	// char[] overload for a list of characters, and the scalar the int one again.
	@Test
	void listArgumentAfterAScalarIsResolvedAgain() {
		assertThat(eval("""
				(mapcar (lambda (x) (java:static "java.lang.String" "valueOf" x)) (list 5 (list #\\a #\\b) 7))
				""").print()).isEqualTo("(\"5\" \"ab\" \"7\")");
	}

	// A remembered varargs choice packs a tail of its own length on every call.
	@Test
	void rememberedVarargsChoicePacksEachTail() {
		assertThat(eval("""
				(list (java:static "java.lang.String" "format" "%s" 1)
				      (java:static "java.lang.String" "format" "%s/%s" 1 2)
				      (java:static "java.lang.String" "format" "%s" 3))
				""").print()).isEqualTo("(\"1\" \"1/2\" \"3\")");
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

	// An interface method a non-public class inherits from a non-public superclass
	// (HashMap's entry iterator gets hasNext from HashMap$HashIterator) is found through
	// the receiver class's own interfaces, at a site left to run time (the receiver
	// is a parameter). Mirrors
	// JvmJavaInteropCompilerTest#anInterfaceMethodOfANonPublicReceiverIsCalled.
	@Test
	void anInterfaceMethodOfANonPublicReceiverIsCalled() {
		assertThat(eval("""
				(defun probe (m)
				  (let ((it (java:call (java:call m "entrySet") "iterator")))
				    (list (java:call it "hasNext")
				          (java:call (java:call it "next") "getKey")
				          (java:call it "hasNext")
				          (java:call (java:call (java:call m "keySet") "iterator") "hasNext"))))
				(let ((m (java:new "java.util.HashMap")))
				  (java:call m "put" "a" 1)
				  (probe m))
				""").print()).isEqualTo("(T \"a\" NIL T)");
	}

	// A parameter tag names the overload the cost would not pick: valueOf(int) makes the
	// code point of #\a, where valueOf(char) would make "a". A constructor takes one
	// too. Mirrors JvmJavaInteropCompilerTest#aParameterTagSelectsTheOverload.
	@Test
	void aParameterTagSelectsTheOverload() {
		assertThat(eval("(java:static \"java.lang.String\" \"valueOf(int)\" #\\a)")).isEqualTo(new LispString("97"));
		assertThat(eval("(java:static \"java.lang.String\" \"valueOf\" #\\a)")).isEqualTo(new LispString("a"));
		assertThat(eval("(java:static \"java.lang.Math\" \"max(long,_)\" 3 7)")).isEqualTo(new LispInteger(7));
		assertThat(eval("(java:call (java:new \"java.lang.StringBuilder(int)\" 16) \"capacity\")"))
			.isEqualTo(new LispInteger(16));
		// On a receiver whose class is known only at run time the tag still narrows.
		assertThat(eval("""
				(setq sb (java:new "java.lang.StringBuilder"))
				(java:call sb "append(Object)" "x")
				(java:call sb "toString")
				""")).isEqualTo(new LispString("x"));
	}

	@Test
	void aMalformedParameterTagSignals() {
		assertThatThrownBy(() -> eval("(java:static \"java.lang.Math\" \"max(long\" 3 7)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("malformed parameter tag");
	}

	// The documented difference between a site resolved before it runs and run-time
	// resolution: a receiver typed by an upper bound resolves among the bound's methods,
	// so Collection.remove(Object) removes the ELEMENT 1, where the untyped call on the
	// ArrayList picks ArrayList.remove(int) and removes the element AT index 1. Mirrors
	// JvmJavaInteropCompilerTest#anUpperBoundReceiverResolvesAmongTheBoundsMethods.
	@Test
	void anUpperBoundReceiverResolvesAmongTheBoundsMethods() {
		assertThat(eval("""
				(defun drop-one (c)
				  (declare (type (java:object "java.util.Collection") c))
				  (java:call c "remove" 1))
				(defun fill-list ()
				  (let ((lst (java:new "java.util.ArrayList")))
				    (java:call lst "add" 10)
				    (java:call lst "add" 20)
				    (java:call lst "add" 1)
				    lst))
				(let ((a (fill-list)) (b (fill-list)) (c (fill-list)))
				  (list (drop-one a) (java:call a "toString")
				        (java:call b "remove" 1) (java:call b "toString")
				        (java:call (the (java:object "java.util.Collection") c) "remove" 10) (java:call c "toString")))
				""").print()).isEqualTo("(T \"[10, 20]\" 20 \"[10, 1]\" T \"[20, 1]\")");
	}

	// A let binding nothing assigns takes its initializer's static type, so a receiver
	// held in it resolves as the initializer would; an assigned one is left to run time.
	// Mirrors JvmJavaInteropCompilerTest#aLetBoundReceiverTakesItsInitializersType.
	@Test
	void aLetBoundReceiverTakesItsInitializersType() {
		assertThat(eval("""
				(defun fill-list ()
				  (let ((lst (java:new "java.util.ArrayList")))
				    (java:call lst "add" 10)
				    (java:call lst "add" 20)
				    (java:call lst "add" 1)
				    lst))
				(let ((c (the (java:object "java.util.Collection") (fill-list)))
				      (d (the (java:object "java.util.Collection") (fill-list))))
				  (setq d (fill-list))
				  (list (java:call c "remove" 1) (java:call c "toString")
				        (java:call d "remove" 1) (java:call d "toString")))
				""").print()).isEqualTo("(T \"[10, 20]\" 20 \"[10, 1]\")");
	}

	// A proclaimed type types the global in every form after it. Mirrors
	// JvmJavaInteropCompilerTest#aProclaimedGlobalTypesTheFormsAfterIt.
	@Test
	void aProclaimedGlobalTypesTheFormsAfterIt() {
		assertThat(eval("""
				(defun fill-list ()
				  (let ((lst (java:new "java.util.ArrayList")))
				    (java:call lst "add" 10)
				    (java:call lst "add" 20)
				    (java:call lst "add" 1)
				    lst))
				(defvar *before* (fill-list))
				(defun drop-before () (java:call *before* "remove" 1))
				(declaim (type (java:object "java.util.Collection") *c* *before*))
				(defvar *c* (fill-list))
				(list (java:call *c* "remove" 1) (java:call *c* "toString")
				      (drop-before) (java:call *before* "toString"))
				""").print()).isEqualTo("(T \"[10, 20]\" 20 \"[10, 1]\")");
	}

	// A declared type is trusted, so a false one is a deterministic error where the
	// value meets the member -- the same message the compiled bridge raises.
	@Test
	void aFalseDeclarationSignals() {
		assertThatThrownBy(() -> eval("""
				(defun size-of (c)
				  (declare (type (java:object "java.util.Collection") c))
				  (java:call c "size"))
				(size-of (java:new "java.lang.StringBuilder"))
				""")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining(
					"java:call: the receiver is not a java.util.Collection, got #<java java.lang.StringBuilder>");
	}

	// An argument a declaration lied about is an error where it meets the member, never
	// converted for a member it was not chosen for -- the text a compiled direct call
	// raises (JvmJavaInteropCompilerTest#aFalseArgumentDeclarationSignals).
	@Test
	void aFalseArgumentDeclarationSignals() {
		String parse = """
				(defun parse (s)
				  (declare (type (java:object "java.lang.String") s))
				  (java:static "java.lang.Integer" "parseInt" s))
				""";
		assertThat(eval(parse + "(parse \"42\")")).isEqualTo(new LispInteger(42));
		assertThatThrownBy(() -> eval(parse + "(parse 42)")).isInstanceOf(LispEvalException.class)
			.hasMessage("java:static: argument 1 is not a java.lang.String, got 42");
	}

	// A site whose class is known but whose argument kinds are not chooses among that
	// class's overloads when it runs, from the kinds the arguments have: numbers,
	// characters, strings, t and nil, a list and a vector (an array parameter), a varargs
	// tail, a constructor. The compiled program dispatches the same way without
	// reflection (JvmJavaInteropCompilerTest#aDispatchedSiteChoosesByTheKindsItMeets).
	@Test
	void aDispatchedSiteChoosesByTheKindsItMeets() {
		assertThat(output(JavaInteropPrograms.DISPATCH_PROGRAM)).isEqualTo(JavaInteropPrograms.DISPATCH_OUTPUT);
	}

	// Sequences and functions reach a dispatched site as the run-time resolution passes
	// them: a list or vector becomes an array or a list of the parameter's type, a
	// function a proxy of an interface -- the one arm that needs the bridge.
	@Test
	void aDispatchedSiteConvertsSequencesAndFunctions() {
		assertThat(output(JavaInteropPrograms.SEQUENCE_DISPATCH_PROGRAM))
			.isEqualTo(JavaInteropPrograms.SEQUENCE_DISPATCH_OUTPUT);
	}

	// A dispatched call on a receiver of declared class C chooses among C's overloads,
	// never the run-time class's: Collection.remove(Object) removes the ELEMENT 1 even
	// when the argument's kind is known only when it runs.
	@Test
	void aDispatchedSiteChoosesAmongTheDeclaredClasssOverloads() {
		assertThat(output(JavaInteropPrograms.UPPER_BOUND_DISPATCH)).isEqualTo("(T \"[10, 20]\")");
	}

	// Mirrors JvmJavaInteropCompilerTest#aLispValueIsNeverAHostObject.
	@Test
	void aLispValueIsNeverAHostObject() {
		assertThat(output(JavaInteropPrograms.HOST_OBJECT_PROGRAM)).isEqualTo(JavaInteropPrograms.HOST_OBJECT_OUTPUT);
	}

	// Mirrors JvmJavaInteropCompilerTest#specializedVectorsAndBignumsAreMarshalled.
	@Test
	void specializedVectorsAndBignumsAreMarshalled() {
		assertThat(output(JavaInteropPrograms.SPECIALIZED_AND_BIGNUM_PROGRAM))
			.isEqualTo(JavaInteropPrograms.SPECIALIZED_AND_BIGNUM_OUTPUT);
	}

	// Mirrors JvmJavaInteropCompilerTest#aHostCollectionIsNoLispArrayOrTable.
	@Test
	void aHostCollectionIsNoLispArrayOrTable() {
		assertThat(output(JavaInteropPrograms.HOST_COLLECTION_PROGRAM))
			.isEqualTo(JavaInteropPrograms.HOST_COLLECTION_OUTPUT);
	}

	// Mirrors JvmJavaInteropCompilerTest#eqAndEqlOnHostObjectsAreIdentity.
	@Test
	void eqAndEqlOnHostObjectsAreIdentity() {
		assertThat(output(JavaInteropPrograms.HOST_IDENTITY_PROGRAM))
			.isEqualTo(JavaInteropPrograms.HOST_IDENTITY_OUTPUT);
	}

	// Mirrors
	// JvmJavaInteropCompilerTest#equalOfAHostObjectAndALispValueAsksEqualsWithTheValueAsJavaSeesIt.
	@Test
	void equalOfAHostObjectAndALispValueAsksEqualsWithTheValueAsJavaSeesIt() {
		assertThat(output(JavaInteropPrograms.HOST_EQUAL_LISP_VALUE_PROGRAM))
			.isEqualTo(JavaInteropPrograms.HOST_EQUAL_LISP_VALUE_OUTPUT);
	}

	// Mirrors JvmJavaInteropCompilerTest#anAccessorRefusesAHostCollection.
	@Test
	void anAccessorRefusesAHostCollection() {
		assertThat(output(JavaInteropPrograms.HOST_ACCESSOR_PROGRAM))
			.isEqualTo(JavaInteropPrograms.HOST_ACCESSOR_OUTPUT);
	}

	// What a dispatched site counted on is checked before it chooses: a declared argument
	// that is not an instance of its class is an error, and arguments no overload takes
	// are reported as the run-time resolution reports them.
	@Test
	void aDispatchedSiteChecksWhatItCountedOn() {
		String addAll = """
				(defun add-all (c)
				  (declare (type (java:object "java.util.Collection") c))
				  (java:call (java:new "java.util.ArrayList") "addAll" c))
				""";
		assertThat(eval(addAll + "(add-all (java:static \"java.util.List\" \"of\" 1))")).isEqualTo(LispTrue.INSTANCE);
		assertThatThrownBy(() -> eval(addAll + "(add-all 5)")).isInstanceOf(LispEvalException.class)
			.hasMessage("java:call: argument 1 is not a java.util.Collection, got 5");
		assertThatThrownBy(() -> eval("(defun mx (x y) (java:static \"java.lang.Math\" \"max\" x y)) (mx \"a\" 1)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("No matching method java.lang.Math.max with 2 argument(s)");
	}

	// java:static calls a static method: an instance method of the name, which could only
	// fail without a receiver, is never chosen.
	@Test
	void aStaticCallNeverChoosesAnInstanceMethod() {
		assertThatThrownBy(() -> eval("(java:static \"java.lang.String\" \"length\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("No matching method java.lang.String.length with 0 argument(s)");
	}

	@Test
	void aClassNameReadsOnlyAStaticField() {
		assertThatThrownBy(() -> eval("(java:field \"java.awt.Point\" \"x\")")).isInstanceOf(LispEvalException.class)
			.hasMessage("java:field: field java.awt.Point.x is not static");
	}

	// What the member throws is wrapped, with the compiled program's text.
	@Test
	void anExceptionFromTheMemberIsWrapped() {
		assertThatThrownBy(() -> eval("(java:static \"java.lang.Integer\" \"parseInt\" \"x\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("error calling java.lang.Integer.parseInt: java.lang.NumberFormatException:"
					+ " For input string: \"x\"");
		assertThatThrownBy(() -> eval("(java:new \"java.lang.StringBuilder\" -1)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("error constructing java.lang.StringBuilder: java.lang.NegativeArraySizeException: -1");
	}

	// What the member throws is caught as a java:java-exception carrying it, and a
	// caught one passed to a member is that throwable again.
	@Test
	void aFailedCallSignalsAJavaExceptionCarryingWhatTheMemberThrew() {
		assertThat(output(JavaInteropPrograms.HOST_EXCEPTION_PROGRAM))
			.isEqualTo(JavaInteropPrograms.HOST_EXCEPTION_OUTPUT);
	}

	// A function value passed to a resolved site where an interface is expected becomes a
	// proxy, as on a compiled direct call.
	@Test
	void aFunctionArgumentOfAResolvedSiteBecomesAProxy() {
		assertThat(output("""
				(java:call (java:static "java.util.List" "of" 1 2 3) "forEach" (lambda (m x) (print x)))
				""")).isEqualTo("1\n2\n3");
	}

	// The values a resolved site answers, as a compiled direct call answers them
	// (JvmJavaInteropCompilerTest#aDirectCallConvertsTheValueBackAsTheBridgeDoes).
	@Test
	void aResolvedSiteConvertsTheValueBack() {
		assertThat(output("""
				(print (java:call (java:new "java.lang.StringBuilder" "ab") "charAt" 1))
				(print (java:call (java:new "java.util.ArrayList") "isEmpty"))
				(print (java:static "java.lang.Character" "valueOf" #\\a))
				(print (java:static "java.lang.Boolean" "valueOf" t))
				(print (java:static "java.lang.Float" "valueOf" 1.5))
				(print (java:static "java.lang.Long" "valueOf" 7))
				(print (java:call (java:static "java.util.regex.Pattern" "compile" ",") "split" "a,b"))
				(print (java:call (java:new "java.util.ArrayList") "add" nil))
				""")).isEqualTo("#\\b\nT\n#\\a\nT\n1.5\n7\n(\"a\" \"b\")\nT");
	}

	// An interface has Object's public methods as members, which Class.getMethods() of
	// it does not list: a call of one resolves before it runs, so nothing is reported.
	// Mirrors JvmJavaInteropCompilerTest#objectsMethodsOnAnInterfaceAreDirectCalls.
	@Test
	void objectsMethodsOnAnInterfaceResolveBeforeTheyRun() {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (var ignored = ThreadStdio.err(err)) {
			assertThat(
					output("(setq java:*warn-on-reflection* t)\n" + JavaInteropPrograms.OBJECT_METHODS_ON_AN_INTERFACE))
				.isEqualTo(JavaInteropPrograms.OBJECT_METHODS_ON_AN_INTERFACE_OUTPUT);
		}
		assertThat(err.toString()).isEmpty();
	}

	// Evaluates the forms and answers what they printed.
	private String output(String input) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out));
		for (LispVal expr : LispReader.readAllFromString(input)) {
			evaluator.eval(expr);
		}
		return out.toString().trim();
	}

	// A chain resolves link by link: each declared return type types the next receiver.
	@Test
	void aChainResolvesThroughDeclaredReturnTypes() {
		assertThat(eval("""
				(java:call (java:call (java:call (java:new "java.lang.StringBuilder" "ab") "append" "c") "reverse")
				           "toString")
				""")).isEqualTo(new LispString("cba"));
	}

	// java:*warn-on-reflection* reports each site a top-level form shows that cannot be
	// resolved before it runs, when the form is loaded; a resolved site is not reported.
	@Test
	void warnOnReflectionReportsTheSitesLeftToRunTime() {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (var ignored = ThreadStdio.err(err)) {
			eval("""
					(defun before (x) (java:call x "size"))
					(setq java:*warn-on-reflection* t)
					(defun len (x) (java:call x "length"))
					(java:static "java.lang.Math" "max" 1 2)
					(let ((sb (java:new "java.lang.StringBuilder"))) (java:call sb "capacity"))
					""");
		}
		assertThat(err.toString()).contains(
				"warning: java:call \"length\" is resolved by reflection at run time: the receiver's class is not known")
			.doesNotContain("\"size\"")
			.doesNotContain("\"max\"")
			.doesNotContain("\"capacity\"");
	}

	// Mirrors JvmJavaInteropCompilerTest#aHostArrayListPrintsOpaquely.
	@Test
	void aHostArrayListPrintsOpaquely() {
		assertThat(output("""
				(print (java:new "java.util.ArrayList"))
				(let ((l (java:new "java.util.ArrayList")))
				  (java:call l "add" 1)
				  (print l)
				  (princ l))
				(print (make-array 2 :initial-element 7))
				"""))
			.isEqualTo("#<java java.util.ArrayList>\n#<java java.util.ArrayList>\n#<java java.util.ArrayList>#(7 7)");
		assertThatThrownBy(() -> eval("""
				(defun size-of (sb)
				  (declare (type (java:object "java.lang.StringBuilder") sb))
				  (java:call sb "length"))
				(size-of (java:new "java.util.ArrayList"))
				""")).isInstanceOf(LispEvalException.class)
			.hasMessage("java:call: the receiver is not a java.lang.StringBuilder, got #<java java.util.ArrayList>");
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
		assertThatThrownBy(() -> eval("(java:call 'foo \"toString\")")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a java object");
	}

	// Mirrors JvmJavaInteropCompilerTest#aLispValueIsCalledAsTheObjectItConvertsTo.
	@Test
	void aLispValueIsCalledAsTheObjectItConvertsTo() {
		assertThat(output(JavaInteropPrograms.LISP_RECEIVER_PROGRAM))
			.isEqualTo(JavaInteropPrograms.LISP_RECEIVER_OUTPUT);
	}

	// Mirrors JvmJavaInteropCompilerTest#falseAndAHashTableCrossAsJavasFalseAndAMap.
	// Before, measured 2026-10-08: every |false| and hash-table row was "No matching
	// method ..." and every callback row "java:reify: cannot return |false| as ...".
	@Test
	void falseAndAHashTableCrossAsJavasFalseAndAMap() {
		assertThat(output(JavaInteropPrograms.FALSE_AND_TABLE_PROGRAM))
			.isEqualTo(JavaInteropPrograms.FALSE_AND_TABLE_OUTPUT);
	}

	// Mirrors JvmJavaInteropCompilerTest#aCallEndingInJavaFalseAnswersJavasFalseAsFalse.
	// Before, measured 2026-10-08: every value Java answers false was nil.
	@Test
	void aCallEndingInJavaFalseAnswersJavasFalseAsFalse() {
		assertThat(output(JavaInteropPrograms.JAVA_FALSE_PROGRAM)).isEqualTo(JavaInteropPrograms.JAVA_FALSE_OUTPUT);
	}

	// Mirrors
	// JvmJavaInteropCompilerTest#anImplementationMadeAtJavaFalseIsHandedFalseAndReadsAComparison.
	// Before, measured 2026-10-08: each function was handed nil, and a Comparator's
	// boolean, float or ratio answer was "cannot return ... as int".
	@Test
	void anImplementationMadeAtJavaFalseIsHandedFalseAndReadsAComparison() {
		assertThat(output(JavaImplementationPrograms.JAVA_FALSE))
			.isEqualTo(JavaImplementationPrograms.JAVA_FALSE_OUTPUT);
	}

	// Mirrors
	// JvmJavaInteropCompilerTest#anImplementationMadeAtOctetsIsHandedAByteArrayAsAnOctetVector.
	// Here the vector is Java's array itself. Before, measured 2026-10-10: every function
	// was handed a list of signed bytes, which (setf aref) refused.
	@Test
	void anImplementationMadeAtOctetsIsHandedAByteArrayAsAnOctetVector() {
		assertThat(output(JavaImplementationPrograms.OCTETS)).isEqualTo(JavaImplementationPrograms.OCTETS_OUTPUT);
	}

	// Mirrors JvmJavaInteropCompilerTest#aHandleIsItsTextToJavaAndItsValueBack.
	@Test
	void aHandleIsItsTextToJavaAndItsValueBack() {
		assertThat(output(JavaInteropPrograms.JAVA_HANDLE_PROGRAM)).isEqualTo(JavaInteropPrograms.JAVA_HANDLE_OUTPUT);
	}

	// Mirrors
	// JvmJavaInteropCompilerTest#aViewIsAReadOnlyCollectionAndAHandleItsOwnObject.
	@Test
	void aViewIsAReadOnlyCollectionAndAHandleItsOwnObject() {
		assertThat(output(JavaInteropPrograms.JAVA_VIEW_PROGRAM)).isEqualTo(JavaInteropPrograms.JAVA_VIEW_OUTPUT);
	}

	// Mirrors
	// JvmJavaInteropCompilerTest#aCallEndingInOctetsAnswersAByteArrayAsAnOctetVector.
	// Before: every byte[] Java answered was a list of signed bytes.
	@Test
	void aCallEndingInOctetsAnswersAByteArrayAsAnOctetVector() {
		assertThat(output(JavaInteropPrograms.OCTETS_PROGRAM)).isEqualTo(JavaInteropPrograms.OCTETS_OUTPUT);
	}

	// Mirrors JvmJavaInteropCompilerTest#aBytesViewIsTheByteArrayJavaStoresInto. Here the
	// array Java is handed is the vector's own storage.
	@Test
	void aBytesViewIsTheByteArrayJavaStoresInto() {
		assertThat(output(JavaInteropPrograms.BYTES_VIEW_PROGRAM)).isEqualTo(JavaInteropPrograms.BYTES_VIEW_OUTPUT);
	}

	@Test
	void proxyOnNonInterfaceSignals() {
		assertThatThrownBy(() -> eval("(java:proxy \"java.lang.String\" (lambda (m) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects an interface");
	}

	// java:reify: each function implements the one method its name designates; a
	// default method keeps its body; Object's three are identity unless implemented.
	// Mirrors JvmJavaInteropCompilerTest#aReifyImplementsEachMethodWithItsFunction.
	@Test
	void aReifyImplementsEachMethodWithItsFunction() {
		assertThat(output(JavaImplementationPrograms.REIFY)).isEqualTo(JavaImplementationPrograms.REIFY_OUTPUT);
	}

	// An abstract method no function implements throws; a function's value that does
	// not convert to the method's return type is an error. Mirrors
	// JvmJavaInteropCompilerTest#whatAReifyCannotDoIsAnError.
	@Test
	void whatAReifyCannotDoIsAnError() {
		assertThatThrownBy(
				() -> eval("(java:call (java:reify \"java.util.Iterator\" \"hasNext\" (lambda () t)) \"next\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("error calling java.util.Iterator.next: java.lang.UnsupportedOperationException:"
					+ " java:reify: no implementation of java.util.Iterator.next()");
		assertThatThrownBy(
				() -> eval("(java:call (java:reify \"java.util.function.IntSupplier\" \"getAsInt\" (lambda () \"x\"))"
						+ " \"getAsInt\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining(
					"java:reify: cannot return \"x\" as int from java.util.function.IntSupplier.getAsInt");
		assertThatThrownBy(() -> eval("(java:reify \"java.util.Comparator\" \"nope\" #'car)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:reify: interface java.util.Comparator has no method nope");
		assertThatThrownBy(() -> eval("(java:reify \"java.lang.Appendable\" \"append\" #'car)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:reify: append names more than one method of java.lang.Appendable: append(char),"
					+ " append(java.lang.CharSequence), append(java.lang.CharSequence,int,int)");
		assertThatThrownBy(() -> eval("(java:reify \"java.util.Iterator\" \"next\" #'car \"next()\" #'cdr)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:reify: java.util.Iterator.next() is implemented twice");
		assertThatThrownBy(() -> eval("(java:reify \"java.lang.String\" \"length\" #'car)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:reify expects an interface, got java.lang.String");
		assertThatThrownBy(() -> eval("(java:reify \"java.lang.Runnable\" \"run\")"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:reify expects (java:reify \"interface\"-or-list [:value v] [:class \"class\"]"
					+ " \"method\" function ...)");
		// The interface and the names may be computed: they are resolved when it runs.
		assertThat(eval("""
				(let ((iface "java.util.function.Supplier") (name "get"))
				  (java:call (java:reify iface name (lambda () 7)) "get"))
				""")).isEqualTo(new LispInteger(7));
	}

	// java:reify of a list of interfaces is one object implementing each; given :value
	// it stands for the value, which Java hands back. Mirrors
	// JvmJavaInteropCompilerTest#aReifyOfSeveralInterfacesMayStandForAValue.
	@Test
	void aReifyOfSeveralInterfacesMayStandForAValue() {
		assertThat(output(JavaImplementationPrograms.REIFY_SEVERAL_STANDING))
			.isEqualTo(JavaImplementationPrograms.REIFY_SEVERAL_STANDING_OUTPUT);
	}

	// What a function called back from Java raises passes through the Java frames to
	// the Lisp code that made the call -- before, the java: site wrapped it as "error
	// calling C.m: ...", so an exit never arrived and a handler on the condition's type
	// never matched. Mirrors
	// JvmJavaInteropCompilerTest#whatACallbackRaisesPassesThroughTheJavaCall.
	@Test
	void whatACallbackRaisesPassesThroughTheJavaCall() {
		assertThat(output(JavaImplementationPrograms.CALLBACK_SIGNALS))
			.isEqualTo(JavaImplementationPrograms.CALLBACK_SIGNALS_OUTPUT);
	}

	// java:proxy routes every method -- a default one too -- to its callable, with the
	// method's name first; Object's three keep their identity behavior. Mirrors
	// JvmJavaInteropCompilerTest#aProxyRoutesEveryMethodToItsCallable.
	@Test
	void aProxyRoutesEveryMethodToItsCallable() {
		assertThat(output(JavaImplementationPrograms.PROXY)).isEqualTo(JavaImplementationPrograms.PROXY_OUTPUT);

	}

	// A java:proxy of several interfaces is one object Java calls through each, every
	// method reaching the one callable by its name. Mirrors
	// JvmJavaInteropCompilerTest#aProxyOfSeveralInterfacesRoutesEachToItsCallable.
	@Test
	void aProxyOfSeveralInterfacesRoutesEachToItsCallable() {
		assertThat(output(JavaImplementationPrograms.PROXY_SEVERAL))
			.isEqualTo(JavaImplementationPrograms.PROXY_SEVERAL_OUTPUT);
	}

	// Every name before the callable must be an interface, each once. Mirrors
	// JvmJavaInteropCompilerTest#aProxyOfSeveralInterfacesRefusesAClassOrARepeat.
	@Test
	void aProxyOfSeveralInterfacesRefusesAClassOrARepeat() {
		assertThatThrownBy(() -> eval("(java:proxy \"java.lang.Runnable\" \"java.lang.String\" (lambda (m) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:proxy expects an interface, got java.lang.String");
		assertThatThrownBy(() -> eval("(java:proxy \"java.lang.Runnable\" \"java.lang.Runnable\" (lambda (m) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:proxy names interface java.lang.Runnable twice");
		assertThatThrownBy(() -> eval("(java:proxy \"java.lang.Runnable\" 1 (lambda (m) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:proxy expects (java:proxy \"interface\"... callable)");
	}

	// A reify held in a let keeps its kind, so the calls passing it resolve; a
	// declaration that a value is one, which lies, is an error. Mirrors
	// JvmJavaInteropCompilerTest#aLetBoundReifyKeepsItsKind.
	@Test
	void aLetBoundReifyKeepsItsKind() {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (var ignored = ThreadStdio.err(err)) {
			assertThat(output("(setq java:*warn-on-reflection* t)\n" + JavaImplementationPrograms.LISTENER))
				.isEqualTo(JavaImplementationPrograms.LISTENER_OUTPUT);
		}
		assertThat(err.toString()).isEmpty();
		assertThatThrownBy(() -> eval(JavaImplementationPrograms.FALSE_IMPLEMENTATION))
			.isInstanceOf(LispEvalException.class)
			.hasMessage(JavaImplementationPrograms.FALSE_IMPLEMENTATION_ERROR);
	}

	// Mirrors
	// JvmJavaInteropCompilerTest#aFunctionAtAFunctionalSiteImplementsItsInterfaceByItsArguments.
	@Test
	void aFunctionAtAFunctionalSiteImplementsItsInterfaceByItsArguments() {
		assertThat(output(JavaImplementationPrograms.FUNCTIONAL))
			.isEqualTo(JavaImplementationPrograms.FUNCTIONAL_OUTPUT);
		// through apply, the marker is the last evaluated argument
		assertThat(output("(let ((l (java:new \"java.util.ArrayList\"))) (java:call l \"add\" 4)"
				+ " (apply #'java:call l \"forEach\" (list #'print :functional)))"))
			.isEqualTo("4");
	}

	// Mirrors
	// JvmJavaInteropCompilerTest#aProxyOfSeveralInterfacesResolvesTheCallsItIsPassedTo.
	@Test
	void aProxyOfSeveralInterfacesResolvesTheCallsItIsPassedTo() {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (var ignored = ThreadStdio.err(err)) {
			assertThat(output("(setq java:*warn-on-reflection* t)\n" + JavaImplementationPrograms.PROXY_SEVERAL_PASSED))
				.isEqualTo(JavaImplementationPrograms.PROXY_SEVERAL_PASSED_OUTPUT);
		}
		assertThat(err.toString()).isEmpty();
		assertThatThrownBy(() -> eval(JavaImplementationPrograms.FALSE_SINGLE_IMPLEMENTATION))
			.isInstanceOf(LispEvalException.class)
			.hasMessageStartingWith(JavaImplementationPrograms.FALSE_SINGLE_IMPLEMENTATION_ERROR);
	}

	// java:*warn-on-reflection* reports a java:reify / java:proxy the compiler would
	// implement by reflection, as the compile path does.
	@Test
	void warnOnReflectionReportsAnInterfaceImplementedByReflection() {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (var ignored = ThreadStdio.err(err)) {
			eval("""
					(setq java:*warn-on-reflection* t)
					(defun proxy-of (iface f) (java:proxy iface f))
					(defun runnable (f) (java:reify "java.lang.Runnable" "run" f))
					""");
		}
		assertThat(err.toString()).contains(
				"warning: java:proxy is implemented by reflection at run time: the interface name is not a literal string")
			.doesNotContain("java:reify");
	}

	// java:subclass extends the superclass: the constructor arguments choose the
	// superclass constructor, a named method runs its body (which sees the object as
	// this), an unnamed one is inherited, and a proxy-super reaches the superclass
	// implementation through the generated accessor. Mirrors
	// JvmJavaInteropCompilerTest#aSubclassExtendsItsSuperclass.
	@Test
	void aSubclassExtendsItsSuperclass() {
		assertThat(output(JavaImplementationPrograms.SUBCLASS)).isEqualTo(JavaImplementationPrograms.SUBCLASS_OUTPUT);
	}

	// A java:subclass of ArrayList / LinkedHashMap is a host object whatever its
	// overrides answer. Mirrors
	// JvmJavaInteropCompilerTest#aCollectionSubclassIsAHostObject.
	@Test
	void aCollectionSubclassIsAHostObject() {
		assertThat(output(JavaImplementationPrograms.HOST_COLLECTION_SUBCLASS))
			.isEqualTo(JavaImplementationPrograms.HOST_COLLECTION_SUBCLASS_OUTPUT);
	}

	// What a java:subclass cannot do is an error: a superclass that is no class, a
	// final one, an interface where an extra interface goes, a repeated interface, a
	// method that names nothing, constructor arguments no constructor takes, and a
	// function's value that does not convert to the method's return type. Mirrors
	// JvmJavaInteropCompilerTest#whatASubclassCannotDoIsAnError.
	@Test
	void whatASubclassCannotDoIsAnError() {
		assertThatThrownBy(() -> eval(
				"(java:subclass \"java.util.function.Supplier\" '() '() (lambda (this name &rest args) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:subclass expects a class, got java.util.function.Supplier");
		assertThatThrownBy(
				() -> eval("(java:subclass \"java.lang.String\" '() '() \"x\" (lambda (this name &rest args) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:subclass: class java.lang.String is final and cannot be extended");
		assertThatThrownBy(() -> eval(
				"(java:subclass \"java.io.File\" '(\"java.lang.String\") '() \"x\" (lambda (this name &rest args) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:subclass expects an interface, got java.lang.String");
		assertThatThrownBy(() -> eval(
				"(java:subclass \"java.io.File\" '(\"java.io.Serializable\" \"java.io.Serializable\") '() \"x\" (lambda (this name &rest args) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:subclass names interface java.io.Serializable twice");
		assertThatThrownBy(() -> eval(
				"(java:subclass \"java.io.File\" '() '(\"nope\") \"x\" (lambda (this name &rest args) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:subclass: java.io.File has no method nope");
		assertThatThrownBy(() -> eval(
				"(java:subclass \"java.io.File\" '() '(\"getClass\") \"x\" (lambda (this name &rest args) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:subclass: java.io.File.getClass cannot be overridden");
		assertThatThrownBy(
				() -> eval("(java:subclass \"java.io.File\" '() '() 1 2 3 4 5 (lambda (this name &rest args) nil))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("No matching constructor for java.io.File with 5 argument(s)");
		assertThatThrownBy(() -> eval(
				"""
						(java:call (java:subclass "java.io.File" '() '("lastModified") "x" (lambda (this name &rest args) "s")) "lastModified")
						"""))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("java:subclass: cannot return \"s\" as long from java.io.File");
		assertThatThrownBy(() -> eval("(java:subclass \"java.io.File\" '() '(\"lastModified\"))"))
			.isInstanceOf(LispEvalException.class)
			.hasMessage("java:subclass expects (java:subclass \"superclass\""
					+ " '(\"interface\"...) '(\"method\"...) constructor-args... callable)");
		// An abstract method no body implements throws with the method's name.
		assertThatThrownBy(() -> eval(
				"""
						(java:call (java:subclass "java.util.AbstractList" '() '("size") (lambda (this name &rest args) 0)) "get" 0)
						"""))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("java.lang.UnsupportedOperationException: get");
	}

	// A class of the program's Java class path -- the source loader's Java class loader,
	// which the CLI builds from --java-classpath / --java-dep -- is reached by every
	// java: operator, a resolved site and one left to run time alike, and only there.
	// The compiled program reaches it the same way
	// (JvmJavaInteropCompilerTest#aClassOnTheJavaClassPathIsCalledDirectly).
	@Test
	void aClassOnTheProgramsJavaClassPathIsReachable(@TempDir Path dir) throws Exception {
		Path jar = JavaLibraryJar.build(dir);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { jar.toUri().toURL() },
				LispEvaluator.class.getClassLoader())) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out));
			evaluator.setSourceLoader(SourceLoader.fileSystem(loader));
			for (LispVal expr : LispReader.readAllFromString(JavaLibraryJar.LIBRARY_PROGRAM)) {
				evaluator.eval(expr);
			}
			assertThat(String.join("\n", out.toString().strip().lines().map(String::strip).toList()))
				.isEqualTo(JavaLibraryJar.LIBRARY_OUTPUT);
		}
		assertThatThrownBy(() -> eval("(java:new \"fixture.lib.Greeter\" \"x\")"))
			.hasMessageContaining("No such class: fixture.lib.Greeter");
	}

}
