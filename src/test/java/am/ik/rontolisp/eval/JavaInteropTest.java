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
import am.ik.rontolisp.testsupport.JavaInteropPrograms;
import am.ik.rontolisp.testsupport.ThreadStdio;
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
