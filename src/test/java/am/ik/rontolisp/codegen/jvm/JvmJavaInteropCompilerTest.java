package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.JavaInteropPrograms;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@code java:} interop functions compiled to a JVM {@code .class} (the
 * {@link JavaBridgeTemplate} bridge that travels beside it). Mirrors the interpreter's
 * {@code JavaInteropTest} cases so the two backends stay behaviorally identical; every
 * case uses headless, deterministic JDK classes.
 */
class JvmJavaInteropCompilerTest {

	@TempDir
	Path tempDir;

	// Compiles the program, runs its main, and returns the captured stdout. A runtime
	// exception thrown by the program (e.g. a bridge validation error) is unwrapped and
	// rethrown so tests can assert on the original message.
	private String compileAndRun(String lispCode) throws Exception {
		List<LispVal> program = LispReader.readAllFromString(lispCode);
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Files.write(this.tempDir.resolve("Test.class"), classBytes);
		writeBeside(compiler);

		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			catch (InvocationTargetException ex) {
				if (ex.getCause() instanceof RuntimeException re) {
					throw re;
				}
				throw ex;
			}
			finally {
				System.setOut(oldOut);
			}
			return baos.toString().trim();
		}
	}

	// Writes the class files that travel beside the program (the java: bridge among
	// them) into the class path root, as the CLI does for -o X.class.
	private void writeBeside(JvmLispCompiler compiler) throws Exception {
		for (var file : compiler.runtimeClassFiles().entrySet()) {
			Path target = this.tempDir.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
	}

	@Test
	void newAndInstanceCall() throws Exception {
		assertThat(compileAndRun("""
				(setq sb (java:new "java.lang.StringBuilder" "hi"))
				(java:call sb "append" "!")
				(print (java:call sb "toString"))
				""")).isEqualTo("\"hi!\"");
	}

	@Test
	void staticCall() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.Integer\" \"parseInt\" \"100\"))")).isEqualTo("100");
		// A class name, a method name and a String argument built by a string producer
		// are mutable character vectors; the bridge's lispString renders them through
		// the reflectively bound _strv (.kb/string-write-runtime.md).
		assertThat(compileAndRun("""
				(print (java:static (concatenate 'string "java.lang." "Integer")
				                    (format nil "parse~a" "Int")
				                    (concatenate 'string "1" "01")))
				""")).isEqualTo("101");
	}

	@Test
	void staticReadsField() throws Exception {
		assertThat(compileAndRun("(print (java:field \"java.lang.Integer\" \"MAX_VALUE\"))")).isEqualTo("2147483647");
	}

	@Test
	void instanceReadsField() throws Exception {
		// java.awt.Point has public int fields x/y, and works headless.
		assertThat(compileAndRun("""
				(setq p (java:new "java.awt.Point" 3 4))
				(print (java:field p "y"))
				""")).isEqualTo("4");
	}

	// An integer argument prefers the int overload (returning an integer), not the
	// long/float/double ones -- the same deterministic overload resolution as the
	// interpreter.
	@Test
	void overloadResolutionPrefersIntForAnInteger() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.Math\" \"max\" 3 7))")).isEqualTo("7");
	}

	@Test
	void overloadResolutionPrefersDoubleForAFloat() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.Math\" \"abs\" -5.5))")).isEqualTo("5.5");
	}

	@Test
	void integerConvertsToDoubleWhenNoIntegerOverload() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.Math\" \"sqrt\" 16))")).isEqualTo("4.0");
	}

	// A character argument marshals to an int parameter (the code point) when there is
	// no char/Character overload -- mirrors
	// JavaInteropTest#characterMarshalsToIntParameter
	// to pin the interpreter and the compiled bridge to the same rule.
	@Test
	void characterMarshalsToIntParameter() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.Character\" \"charCount\" #\\a))")).isEqualTo("1");
	}

	// A supplementary code point cannot fit a single Java char, so it falls back to the
	// int overload (Character.toString(int)) rather than the char one -- mirrors
	// JavaInteropTest#supplementaryCodePointDoesNotNarrowToChar.
	@Test
	void supplementaryCodePointDoesNotNarrowToChar() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.Character\" \"toString\" (code-char 128512)))"))
			.isEqualTo("\"" + new String(Character.toChars(128512)) + "\"");
	}

	// The symbol t marshals to a boolean parameter (the compiled true is the bare
	// symbol "T", which is also what a boolean answer comes back as).
	@Test
	void trueMarshalsToABooleanParameter() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.String\" \"valueOf\" t))")).isEqualTo("\"true\"");
		assertThat(compileAndRun("(print (java:static \"java.lang.Boolean\" \"logicalAnd\" t t))")).isEqualTo("T");
	}

	// A boolean Java return (ArrayList.add) surfaces as t.
	@Test
	void booleanReturnSurfacesAsTrue() throws Exception {
		assertThat(compileAndRun("""
				(setq lst (java:new "java.util.ArrayList"))
				(print (java:call lst "add" 42))
				""")).isEqualTo("T");
	}

	@Test
	void collectionRoundTrip() throws Exception {
		assertThat(compileAndRun("""
				(setq lst (java:new "java.util.ArrayList"))
				(java:call lst "add" 10)
				(java:call lst "add" 20)
				(print (list (java:call lst "size") (java:call lst "get" 1)))
				""")).isEqualTo("(2 20)");
	}

	// A rontolisp lambda becomes a Runnable through the embedded bridge's _apply
	// callback; calling run() runs the lambda (void return).
	@Test
	void proxyVoidReturnRunsTheLambda() throws Exception {
		assertThat(compileAndRun("""
				(setq fired nil)
				(setq r (java:proxy "java.lang.Runnable" (lambda (method) (setq fired t))))
				(java:call r "run")
				(print fired)
				""")).isEqualTo("T");
	}

	@Test
	void proxyValueReturn() throws Exception {
		assertThat(compileAndRun("""
				(setq s (java:proxy "java.util.function.Supplier" (lambda (method) 42)))
				(print (java:call s "get"))
				""")).isEqualTo("42");
	}

	// A proxy that receives arguments: a Comparator drives Collections.sort.
	@Test
	void proxyReceivesArguments() throws Exception {
		assertThat(compileAndRun("""
				(setq lst (java:new "java.util.ArrayList"))
				(java:call lst "add" 30)
				(java:call lst "add" 10)
				(java:call lst "add" 20)
				(java:static "java.util.Collections" "sort" lst
				  (java:proxy "java.util.Comparator" (lambda (method a b) (- a b))))
				(print (list (java:call lst "get" 0) (java:call lst "get" 2)))
				""")).isEqualTo("(10 30)");
	}

	// A defun'd function value is auto-proxied when passed where an interface is
	// expected.
	@Test
	void functionValueAutoProxies() throws Exception {
		assertThat(compileAndRun("""
				(defun desc (method a b) (- b a))
				(setq lst (java:new "java.util.ArrayList"))
				(java:call lst "add" 10)
				(java:call lst "add" 30)
				(java:call lst "add" 20)
				(java:static "java.util.Collections" "sort" lst #'desc)
				(print (java:call lst "get" 0))
				""")).isEqualTo("30");
	}

	// A proper list marshals to a primitive array parameter; the int elements prefer
	// the int[] overload of binarySearch.
	@Test
	void listMarshalsToPrimitiveArrayParameter() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.util.Arrays\" \"binarySearch\" (list 10 20 30 40) 30))"))
			.isEqualTo("2");
	}

	@Test
	void listMarshalsToCollectionParameter() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.util.Collections\" \"max\" (list 3 9 4)))"))
			.isEqualTo("9");
	}

	@Test
	void nestedListMarshalsRecursively() throws Exception {
		assertThat(
				compileAndRun("(print (java:static \"java.util.Arrays\" \"deepToString\" (list (list 1 2) (list 3))))"))
			.isEqualTo("\"[[1, 2], [3]]\"");
	}

	// A rank-1 vector (the runtime ArrayList representation) marshals like a list;
	// string elements select the CharSequence[] varargs parameter of String.join.
	@Test
	void vectorMarshalsToArrayParameter() throws Exception {
		assertThat(compileAndRun("""
				(setq v (make-array 2))
				(setf (aref v 0) "a")
				(setf (aref v 1) "b")
				(print (java:static "java.lang.String" "join" "-" v))
				""")).isEqualTo("\"a-b\"");
	}

	@Test
	void fillPointerVectorMarshalsUpToFillPointer() throws Exception {
		// The fill pointer bounds the marshaled sequence, matching length/printing.
		assertThat(compileAndRun("""
				(setq v (make-array 3 :fill-pointer 0))
				(vector-push "a" v)
				(vector-push "b" v)
				(print (java:static "java.lang.String" "join" "-" v))
				""")).isEqualTo("\"a-b\"");
	}

	// A dotted (improper) list is not a sequence, so no overload matches.
	@Test
	void dottedListDoesNotMarshal() {
		assertThatThrownBy(() -> compileAndRun("(java:static \"java.util.Collections\" \"max\" (cons 1 2))"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("No matching method");
	}

	@Test
	void varargsPacksTrailingArguments() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.lang.String\" \"format\" \"%s-%s\" 1 \"x\"))"))
			.isEqualTo("\"1-x\"");
	}

	// Eleven arguments exceed every fixed-arity List.of overload, forcing the varargs
	// one; and a varargs method also accepts an empty tail.
	@Test
	void varargsAcceptsAnyArity() throws Exception {
		assertThat(compileAndRun(
				"(print (java:call (java:static \"java.util.List\" \"of\" 1 2 3 4 5 6 7 8 9 10 11) \"size\"))"))
			.isEqualTo("11");
		assertThat(compileAndRun("(print (java:call (java:static \"java.util.List\" \"of\") \"size\"))"))
			.isEqualTo("0");
	}

	// The overload chosen for a call is remembered per argument kind. A float first
	// selects max(double,double); the integers after it must still select max(int,int)
	// (a shared choice would print 3.0), and the floats after those the double one.
	@Test
	void rememberedOverloadFollowsTheArgumentKind() throws Exception {
		assertThat(compileAndRun("""
				(print (mapcar (lambda (x) (java:static "java.lang.Math" "max" x 0)) (list 2.5 3 4.5 5)))
				""")).isEqualTo("(2.5 3 4.5 5)");
	}

	// The same method name on alternating receiver classes resolves on each class.
	@Test
	void rememberedOverloadFollowsTheReceiverClass() throws Exception {
		assertThat(compileAndRun("""
				(print (mapcar (lambda (c) (java:call c "add" "x") (java:call c "size"))
				               (list (java:new "java.util.ArrayList") (java:new "java.util.LinkedList")
				                     (java:new "java.util.ArrayList"))))
				""")).isEqualTo("(1 1 1)");
	}

	// A one-character string may narrow to char (Writer.append(char)); a longer one
	// cannot and takes append(CharSequence). Alternating the two at one site.
	@Test
	void rememberedOverloadSeparatesOneCharacterStrings() throws Exception {
		assertThat(compileAndRun("""
				(setq w (java:new "java.io.StringWriter"))
				(dolist (s (list "a" "bc" "d" "ef")) (java:call w "append" s))
				(print (java:call w "toString"))
				""")).isEqualTo("\"abcdef\"");
	}

	// A list has no remembered kind: after a scalar, String.valueOf still takes the
	// char[] overload for a list of characters, and the scalar the int one again.
	@Test
	void listArgumentAfterAScalarIsResolvedAgain() throws Exception {
		assertThat(compileAndRun("""
				(print (mapcar (lambda (x) (java:static "java.lang.String" "valueOf" x)) (list 5 (list #\\a #\\b) 7)))
				""")).isEqualTo("(\"5\" \"ab\" \"7\")");
	}

	// A remembered varargs choice packs a tail of its own length on every call.
	@Test
	void rememberedVarargsChoicePacksEachTail() throws Exception {
		assertThat(compileAndRun("""
				(print (list (java:static "java.lang.String" "format" "%s" 1)
				             (java:static "java.lang.String" "format" "%s/%s" 1 2)
				             (java:static "java.lang.String" "format" "%s" 3)))
				""")).isEqualTo("(\"1\" \"1/2\" \"3\")");
	}

	// A returned Java array surfaces as a Lisp list, and a list marshals back into an
	// array parameter -- Arrays.copyOf(int[], int) round-trips both directions.
	@Test
	void arrayRoundTripsThroughCopyOf() throws Exception {
		assertThat(compileAndRun("(print (java:static \"java.util.Arrays\" \"copyOf\" (list 1 2 3) 2))"))
			.isEqualTo("(1 2)");
	}

	// A returned primitive array unmarshals element-wise; the IntStream implementation
	// class is JDK-internal, so this also exercises the accessible-method resolution.
	@Test
	void returnedPrimitiveArrayUnmarshalsToList() throws Exception {
		assertThat(compileAndRun(
				"(print (java:call (java:call (java:new \"java.lang.StringBuilder\" \"ab\") \"chars\") \"toArray\"))"))
			.isEqualTo("(97 98)");
	}

	// A parameter tag names the overload the cost would not pick -- mirrors
	// JavaInteropTest#aParameterTagSelectsTheOverload.
	@Test
	void aParameterTagSelectsTheOverload() throws Exception {
		assertThat(compileAndRun("""
				(print (java:static "java.lang.String" "valueOf(int)" #\\a))
				(print (java:static "java.lang.String" "valueOf" #\\a))
				(print (java:static "java.lang.Math" "max(long,_)" 3 7))
				(print (java:call (java:new "java.lang.StringBuilder(int)" 16) "capacity"))
				(setq sb (java:new "java.lang.StringBuilder"))
				(java:call sb "append(Object)" "x")
				(print (java:call sb "toString"))
				""")).isEqualTo("\"97\"\n\"a\"\n7\n16\n\"x\"");
	}

	@Test
	void aMalformedParameterTagSignals() {
		assertThatThrownBy(() -> compileAndRun("(java:static \"java.lang.Math\" \"max(long\" 3 7)"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("malformed parameter tag");
	}

	// The receiver-subclass difference, identical to the interpreter's -- mirrors
	// JavaInteropTest#anUpperBoundReceiverResolvesAmongTheBoundsMethods.
	@Test
	void anUpperBoundReceiverResolvesAmongTheBoundsMethods() throws Exception {
		assertThat(compileAndRun("""
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
				  (print (list (drop-one a) (java:call a "toString")
				               (java:call b "remove" 1) (java:call b "toString")
				               (java:call (the (java:object "java.util.Collection") c) "remove" 10)
				               (java:call c "toString"))))
				""")).isEqualTo("(T \"[10, 20]\" 20 \"[10, 1]\" T \"[20, 1]\")");
	}

	@Test
	void aLetBoundReceiverTakesItsInitializersType() throws Exception {
		assertThat(compileAndRun("""
				(defun fill-list ()
				  (let ((lst (java:new "java.util.ArrayList")))
				    (java:call lst "add" 10)
				    (java:call lst "add" 20)
				    (java:call lst "add" 1)
				    lst))
				(let ((c (the (java:object "java.util.Collection") (fill-list)))
				      (d (the (java:object "java.util.Collection") (fill-list))))
				  (setq d (fill-list))
				  (print (list (java:call c "remove" 1) (java:call c "toString")
				               (java:call d "remove" 1) (java:call d "toString"))))
				""")).isEqualTo("(T \"[10, 20]\" 20 \"[10, 1]\")");
	}

	@Test
	void aProclaimedGlobalTypesTheFormsAfterIt() throws Exception {
		assertThat(compileAndRun("""
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
				(print (list (java:call *c* "remove" 1) (java:call *c* "toString")
				             (drop-before) (java:call *before* "toString")))
				""")).isEqualTo("(T \"[10, 20]\" 20 \"[10, 1]\")");
	}

	@Test
	void aFalseDeclarationSignals() {
		assertThatThrownBy(() -> compileAndRun("""
				(defun size-of (c)
				  (declare (type (java:object "java.util.Collection") c))
				  (java:call c "size"))
				(size-of (java:new "java.lang.StringBuilder"))
				""")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining(
					"java:call: the receiver is not a java.util.Collection, got #<java java.lang.StringBuilder>");
	}

	@Test
	void aChainResolvesThroughDeclaredReturnTypes() throws Exception {
		assertThat(compileAndRun("""
				(print (java:call (java:call (java:call (java:new "java.lang.StringBuilder" "ab") "append" "c")
				                             "reverse")
				                  "toString"))
				""")).isEqualTo("\"cba\"");
	}

	// A resolved site is a direct call of the member it resolved to -- invokestatic,
	// new + invokespecial, invokevirtual, getstatic, invokeinterface -- and a class whose
	// sites all resolved carries no bridge, no reflection and no eval runtime.
	@Test
	void aResolvedSiteIsADirectCall() throws Exception {
		String program = """
				(print (java:static "java.lang.Math" "max" 3 7))
				(print (java:call (java:new "java.lang.StringBuilder" "ab") "length"))
				(print (java:field "java.lang.Integer" "MAX_VALUE"))
				(print (java:call (java:static "java.util.List" "of" 1 2) "size"))
				""";
		assertThat(compileAndRun(program)).isEqualTo("7\n2\n2147483647\n2");
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		compiler.compile(LispReader.readAllFromString(program));
		assertThat(compiler.runtimeClassFiles()).isEmpty();
		assertThat(javap(program)).contains("Method java/lang/Math.max:(II)I")
			.contains("Method java/lang/StringBuilder.\"<init>\":(Ljava/lang/String;)V")
			.contains("Method java/lang/StringBuilder.length:()I")
			.contains("Field java/lang/Integer.MAX_VALUE:I")
			.contains("InterfaceMethod java/util/List.of:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;")
			.contains("InterfaceMethod java/util/List.size:()I")
			.doesNotContain(JvmJavaRuntimeBuilder.BRIDGE_SUFFIX)
			.doesNotContain(JvmJavaRuntimeBuilder.INIT_METHOD)
			.doesNotContain("java/lang/reflect")
			.doesNotContain("_eval");
	}

	// A site left to run time keeps the bridge, and only then is it embedded.
	@Test
	void aSiteLeftToRunTimeKeepsTheBridge() throws Exception {
		String program = """
				(defun len (x) (java:call x "length"))
				(print (len (java:new "java.lang.StringBuilder" "abc")))
				""";
		assertThat(compileAndRun(program)).isEqualTo("3");
		assertThat(javap(program)).contains(JvmJavaRuntimeBuilder.BRIDGE_SUFFIX)
			.contains("Method java/lang/StringBuilder.\"<init>\":(Ljava/lang/String;)V");
	}

	// The class is stamped for the Java release its sites resolved against, so an older
	// JRE refuses it at load instead of failing at the first call of a newer member; a
	// program without java: keeps the Java 17 baseline.
	@Test
	void theClassIsStampedForTheReleaseItsSitesResolvedAgainst() {
		List<LispVal> program = LispReader.readAllFromString("(print (java:static \"java.lang.Math\" \"max\" 3 7))");
		int newest;
		try (JvmClassFileLookup lookup = JvmClassFileLookup.forJdk(null, List.of())) {
			newest = lookup.release();
		}
		assertThat(majorVersion(JvmLispCompiler.builder().className("Test").build().compile(program)))
			.isEqualTo(Math.max(61, 44 + newest));
		assertThat(majorVersion(JvmLispCompiler.builder().className("Test").javaRelease(17).build().compile(program)))
			.isEqualTo(61);
		assertThat(majorVersion(JvmLispCompiler.builder()
			.className("Test")
			.build()
			.compile(LispReader.readAllFromString("(print (+ 1 2))")))).isEqualTo(61);
	}

	private static int majorVersion(byte[] classFile) {
		return ((classFile[6] & 0xFF) << 8) | (classFile[7] & 0xFF);
	}

	// A declared type is trusted: an argument a declaration lied about is an error where
	// it meets the member, with the interpreter's text, never converted for a member it
	// was not chosen for.
	@Test
	void aFalseArgumentDeclarationSignals() throws Exception {
		String parse = """
				(defun parse (s)
				  (declare (type (java:object "java.lang.String") s))
				  (java:static "java.lang.Integer" "parseInt" s))
				""";
		assertThat(compileAndRun(parse + "(print (parse \"42\"))")).isEqualTo("42");
		assertThatThrownBy(() -> compileAndRun(parse + "(parse 42)")).isInstanceOf(RuntimeException.class)
			.hasMessage("java:static: argument 1 is not a java.lang.String, got 42");
	}

	// A site whose class is known but whose argument kinds are not is a dispatch among
	// the
	// class's overloads compiled without reflection: the costs of the value each argument
	// has, the cheapest overload, a direct call of it. It chooses what the interpreter
	// chooses (JavaInteropTest#aDispatchedSiteChoosesByTheKindsItMeets).
	@Test
	void aDispatchedSiteChoosesByTheKindsItMeets() throws Exception {
		assertThat(compileAndRun(JavaInteropPrograms.DISPATCH_PROGRAM)).isEqualTo(JavaInteropPrograms.DISPATCH_OUTPUT);
		String program = """
				(defun mx (x y) (java:static "java.lang.Math" "max" x y))
				(defun val (x) (java:static "java.lang.String" "valueOf" x))
				(print (list (mx 1 2) (val (list #\\a #\\b))))
				""";
		assertThat(compileAndRun(program)).isEqualTo("(2 \"ab\")");
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		compiler.compile(LispReader.readAllFromString(program));
		assertThat(compiler.runtimeClassFiles()).isEmpty();
		assertThat(javap(program)).contains("Method java/lang/Math.max:(II)I")
			.contains("Method java/lang/Math.max:(DD)D")
			.contains("Method java/lang/String.valueOf:([C)Ljava/lang/String;")
			.contains(JvmJavaDirectSites.COST_PREFIX)
			.doesNotContain(JvmJavaRuntimeBuilder.BRIDGE_SUFFIX)
			.doesNotContain(JvmJavaRuntimeBuilder.INIT_METHOD)
			.doesNotContain("java/lang/reflect");
	}

	// Sequences and functions reach a dispatched site as the run-time resolution passes
	// them: a list or vector becomes an array or a list of the parameter's type, a
	// function a proxy of an interface -- the one arm that needs the bridge.
	@Test
	void aDispatchedSiteConvertsSequencesAndFunctions() throws Exception {
		assertThat(compileAndRun(JavaInteropPrograms.SEQUENCE_DISPATCH_PROGRAM))
			.isEqualTo(JavaInteropPrograms.SEQUENCE_DISPATCH_OUTPUT);
	}

	// A dispatched call on a receiver of declared class C chooses among C's overloads
	// (JavaInteropTest#aDispatchedSiteChoosesAmongTheDeclaredClasssOverloads).
	@Test
	void aDispatchedSiteChoosesAmongTheDeclaredClasssOverloads() throws Exception {
		assertThat(compileAndRun(JavaInteropPrograms.UPPER_BOUND_DISPATCH)).isEqualTo("(T \"[10, 20]\")");
	}

	// JavaInteropTest#aDispatchedSiteChecksWhatItCountedOn, with the same texts.
	@Test
	void aDispatchedSiteChecksWhatItCountedOn() throws Exception {
		String addAll = """
				(defun add-all (c)
				  (declare (type (java:object "java.util.Collection") c))
				  (java:call (java:new "java.util.ArrayList") "addAll" c))
				""";
		assertThat(compileAndRun(addAll + "(print (add-all (java:static \"java.util.List\" \"of\" 1)))"))
			.isEqualTo("T");
		assertThatThrownBy(() -> compileAndRun(addAll + "(add-all 5)")).isInstanceOf(RuntimeException.class)
			.hasMessage("java:call: argument 1 is not a java.util.Collection, got 5");
		assertThatThrownBy(
				() -> compileAndRun("(defun mx (x y) (java:static \"java.lang.Math\" \"max\" x y)) (mx \"a\" 1)"))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("No matching method java.lang.Math.max with 2 argument(s)");
	}

	// java:static calls a static method: an instance method of the name, which could only
	// fail without a receiver, is never chosen.
	@Test
	void aStaticCallNeverChoosesAnInstanceMethod() {
		assertThatThrownBy(() -> compileAndRun("(java:static \"java.lang.String\" \"length\")"))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("No matching method java.lang.String.length with 0 argument(s)");
	}

	@Test
	void aClassNameReadsOnlyAStaticField() {
		assertThatThrownBy(() -> compileAndRun("(java:field \"java.awt.Point\" \"x\")"))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("java:field: field java.awt.Point.x is not static");
	}

	// What the member throws is wrapped as the bridge and the interpreter wrap it.
	@Test
	void anExceptionFromTheMemberIsWrapped() {
		assertThatThrownBy(() -> compileAndRun("(java:static \"java.lang.Integer\" \"parseInt\" \"x\")"))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("error calling java.lang.Integer.parseInt: java.lang.NumberFormatException:"
					+ " For input string: \"x\"");
		assertThatThrownBy(() -> compileAndRun("(java:new \"java.lang.StringBuilder\" -1)"))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("error constructing java.lang.StringBuilder: java.lang.NegativeArraySizeException: -1");
	}

	// A function value passed where an interface is expected becomes the bridge's proxy
	// even on a direct call: the one conversion that reflects.
	@Test
	void aFunctionArgumentOfADirectCallBecomesAProxy() throws Exception {
		String program = """
				(java:call (java:static "java.util.List" "of" 1 2 3) "forEach" (lambda (m x) (print x)))
				""";
		assertThat(compileAndRun(program)).isEqualTo("1\n2\n3");
		assertThat(javap(program)).contains("InterfaceMethod java/util/List.forEach:(Ljava/util/function/Consumer;)V")
			.contains("javaProxy");
	}

	// The value comes back as the bridge's unmarshal makes it, specialized to the
	// declared
	// type: a char, a boolean, the boxes, an array.
	@Test
	void aDirectCallConvertsTheValueBackAsTheBridgeDoes() throws Exception {
		assertThat(compileAndRun("""
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

	// A string a program builds is a mutable character vector: it is rendered to the
	// string it spells before a direct call reads it, as the bridge renders it.
	@Test
	void aBuiltStringReachesADirectCallAsTheStringItSpells() throws Exception {
		assertThat(compileAndRun("""
				(print (java:static "java.lang.Integer" "parseInt"
				                    (the (java:object "java.lang.String") (concatenate 'string "4" "2"))))
				""")).isEqualTo("42");
	}

	// Past the JVM's parameter-count comfort a site's method takes its values in one
	// array.
	@Test
	void aSiteWithManyArgumentsPassesThemInOneArray() throws Exception {
		StringBuilder args = new StringBuilder();
		for (int i = 1; i <= 250; i++) {
			args.append(' ').append(i);
		}
		assertThat(compileAndRun("(print (java:call (java:static \"java.util.List\" \"of\"" + args + ") \"size\"))"))
			.isEqualTo("250");
	}

	// --java-static: a program whose sites are all direct compiles to a class with no
	// reflection; any site that would need the bridge is a compile error naming it.
	@Test
	void javaStaticRefusesEverySiteThatNeedsReflection() throws Exception {
		byte[] direct = JvmLispCompiler.builder()
			.className("Test")
			.javaStatic(true)
			.build()
			.compile(LispReader.readAllFromString("(print (java:static \"java.lang.Math\" \"max\" 3 7))"));
		assertThat(new String(direct, java.nio.charset.StandardCharsets.ISO_8859_1))
			.doesNotContain(JvmJavaRuntimeBuilder.BRIDGE_SUFFIX);
		// A dispatch among overloads that take no interface needs no reflection either.
		byte[] dispatched = JvmLispCompiler.builder()
			.className("Test")
			.javaStatic(true)
			.build()
			.compile(LispReader.readAllFromString("""
					(defun mx (x y) (java:static "java.lang.Math" "max" x y))
					(defun val (x) (java:static "java.lang.String" "valueOf" x))
					(print (list (mx 1 2.5) (val (list #\\a))))
					"""));
		assertThat(new String(dispatched, java.nio.charset.StandardCharsets.ISO_8859_1))
			.doesNotContain(JvmJavaRuntimeBuilder.BRIDGE_SUFFIX);
		assertThatThrownBy(() -> JvmLispCompiler.builder()
			.className("Test")
			.javaStatic(true)
			.build()
			.compile(LispReader.readAllFromString("""
					(defun len (x) (java:call x "length"))
					(java:proxy "java.lang.Runnable" (lambda (m) nil))
					(java:call (java:static "java.util.List" "of" 1) "forEach" (lambda (m x) x))
					(defun join (xs) (java:static "java.lang.String" "join" "-" xs))
					"""))).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--java-static: 4 java: calls cannot be compiled without reflection:")
			.hasMessageContaining("java:call \"length\": it is resolved by reflection at run time:"
					+ " the receiver's class is not known")
			.hasMessageContaining("java:proxy \"java.lang.Runnable\": java:proxy implements its interface with"
					+ " java.lang.reflect.Proxy")
			.hasMessageContaining("java:call \"forEach\": argument 1 may be a function, which becomes a"
					+ " java.lang.reflect.Proxy of java.util.function.Consumer")
			.hasMessageContaining("java:static \"java.lang.String\" \"join\": argument 2 may be a function, which"
					+ " becomes a java.lang.reflect.Proxy of java.lang.CharSequence");
	}

	// The class's disassembly, as javap prints it.
	private static String javap(String program) throws Exception {
		return javap(new JvmLispCompiler("Test").compile(LispReader.readAllFromString(program)));
	}

	private static String javap(byte[] classBytes) throws Exception {
		Path dir = Files.createTempDirectory("javap");
		try {
			Path classFile = dir.resolve("Test.class");
			Files.write(classFile, classBytes);
			java.util.spi.ToolProvider javap = java.util.spi.ToolProvider.findFirst("javap").orElseThrow();
			java.io.StringWriter out = new java.io.StringWriter();
			int code = javap.run(new java.io.PrintWriter(out), new java.io.PrintWriter(out), "-c", "-p", "-v",
					classFile.toString());
			assertThat(code).as(out.toString()).isZero();
			return out.toString();
		}
		finally {
			try (var files = Files.list(dir)) {
				for (Path file : files.toList()) {
					Files.delete(file);
				}
			}
			Files.delete(dir);
		}
	}

	// --warn-java-reflection reports at compile time, with the site's position, each
	// site left to run time; so does a top-level (setq java:*warn-on-reflection* t), for
	// the forms after it -- which is when the interpreter reports the same sites.
	@Test
	void warnJavaReflectionReportsTheSitesLeftToRunTime() {
		String program = """
				(defun before (x) (java:call x "size"))
				(defun len (x) (java:call x "length"))
				(print (java:static "java.lang.Math" "max" 1 2))
				(let ((sb (java:new "java.lang.StringBuilder"))) (java:call sb "capacity"))
				""";
		assertThat(compileWarnings(program, true))
			.contains("warning: java:call \"size\" is resolved by reflection at run time")
			.contains("warning: java:call \"length\" is resolved by reflection at run time:"
					+ " the receiver's class is not known")
			.doesNotContain("\"max\"")
			.doesNotContain("\"capacity\"");
		assertThat(compileWarnings("""
				(defun before (x) (java:call x "size"))
				(setq java:*warn-on-reflection* t)
				(defun len (x) (java:call x "length"))
				""", false)).contains("\"length\"").doesNotContain("\"size\"");
		assertThat(compileWarnings(program, false)).isEmpty();
	}

	// A site rewritten for a typed argument, around another rewritten site, keeps its
	// source position in the report.
	@Test
	void aRewrittenSiteKeepsItsPosition() {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		am.ik.rontolisp.SourceProvenance.startRecording();
		try (var ignored = ThreadStdio.err(err)) {
			JvmLispCompiler.builder()
				.className("Test")
				.warnJavaReflection(true)
				.build()
				.compile(LispReader.readAllFromString("""
						(let ((sb (java:new "java.lang.StringBuilder")))
						  (java:call (other) "accept" sb (lambda () (java:call sb "length"))))
						""", am.ik.rontolisp.reader.Features.JVM, "t.lisp"));
		}
		finally {
			am.ik.rontolisp.SourceProvenance.stopRecording();
		}
		assertThat(err.toString()).contains("t.lisp:2:3: warning: java:call \"accept\"");
	}

	private static String compileWarnings(String program, boolean warn) {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (var ignored = ThreadStdio.err(err)) {
			JvmLispCompiler.builder()
				.className("Test")
				.warnJavaReflection(warn)
				.build()
				.compile(LispReader.readAllFromString(program));
		}
		return err.toString().trim();
	}

	// A wrapped host object prints opaquely as #<java class>, matching the interpreter.
	@Test
	void printsOpaquely() throws Exception {
		assertThat(compileAndRun("(print (java:new \"java.lang.StringBuilder\"))"))
			.isEqualTo("#<java java.lang.StringBuilder>");
	}

	// An ArrayList a Java call answers is a host object, not a Lisp array (whose slot 0
	// is its Object[] header): it prints as the interpreter prints it, and a message that
	// shows it (a false declaration's) does too.
	@Test
	void aHostArrayListPrintsOpaquely() throws Exception {
		assertThat(compileAndRun("""
				(print (java:new "java.util.ArrayList"))
				(let ((l (java:new "java.util.ArrayList")))
				  (java:call l "add" 1)
				  (print l)
				  (princ l))
				(print (make-array 2 :initial-element 7))
				"""))
			.isEqualTo("#<java java.util.ArrayList>\n#<java java.util.ArrayList>\n#<java java.util.ArrayList>#(7 7)");
		assertThatThrownBy(() -> compileAndRun("""
				(defun size-of (sb)
				  (declare (type (java:object "java.lang.StringBuilder") sb))
				  (java:call sb "length"))
				(size-of (java:new "java.util.ArrayList"))
				""")).isInstanceOf(RuntimeException.class)
			.hasMessage("java:call: the receiver is not a java.lang.StringBuilder, got #<java java.util.ArrayList>");
	}

	// java: interop composes with hash tables: the HashMap-based Lisp hash table keeps
	// working (and hash-table-p stays t) while host objects print opaquely.
	@Test
	void composesWithHashTables() throws Exception {
		assertThat(compileAndRun("""
				(setq h (make-hash-table))
				(setf (gethash 'k h) (java:static "java.lang.Math" "max" 1 2))
				(print (gethash 'k h))
				(print (hash-table-p h))
				""")).isEqualTo("2\nT");
	}

	@Test
	void unknownClassSignals() {
		assertThatThrownBy(() -> compileAndRun("(java:new \"no.such.Class\")")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("No such class");
	}

	@Test
	void noMatchingMethodSignals() {
		assertThatThrownBy(() -> compileAndRun("""
				(setq sb (java:new "java.lang.StringBuilder"))
				(java:call sb "noSuchMethod" 1 2 3)
				""")).isInstanceOf(RuntimeException.class).hasMessageContaining("No matching method");
	}

	@Test
	void callOnNonObjectSignals() {
		assertThatThrownBy(() -> compileAndRun("(java:call 42 \"toString\")")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("expects a java object");
	}

	@Test
	void proxyOnNonInterfaceSignals() {
		assertThatThrownBy(() -> compileAndRun("(java:proxy \"java.lang.String\" (lambda (m) nil))"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("expects an interface");
	}

	// Arity errors are caught at compile time.
	@Test
	void missingArgumentsFailAtCompileTime() {
		assertThatThrownBy(() -> compileAndRun("(java:new)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("java:new expects");
		assertThatThrownBy(() -> compileAndRun("(java:call 1)")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("java:call expects");
		assertThatThrownBy(() -> compileAndRun("(java:field \"java.lang.Integer\")"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("java:field expects");
	}

	// The bridge travels as an ordinary class file beside the program, never as bytes the
	// program defines at run time: a GraalVM native image cannot define a class at run
	// time, so a Lookup.defineClass in _javaInit made every java: program fail under
	// native-image. Named after the program, because it holds that program's _apply.
	@Test
	void theBridgeTravelsAsAClassFileBesideTheProgram() throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("com/example/Test");
		byte[] classBytes = compiler.compile(LispReader.readAllFromString("""
				(defun biggest (cls) (java:static cls "max" 3 7))
				(print (biggest "java.lang.Math"))
				"""));
		assertThat(compiler.runtimeClassFiles()).containsKey("com/example/Test$JavaBridge.class");
		String classText = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
		assertThat(classText).doesNotContain("defineClass").doesNotContain("java/util/Base64");
		byte[] bridge = compiler.runtimeClassFiles().get("com/example/Test$JavaBridge.class");
		assertThat(new String(bridge, java.nio.charset.StandardCharsets.ISO_8859_1))
			.doesNotContain("am/ik/rontolisp/codegen/jvm/JavaBridgeTemplate");
	}

	// The renamed bridge class stays structurally valid: this is implicitly covered by
	// every test above, but the rename itself must also leave non-matching classes
	// untouched.
	@Test
	void renameClassLeavesOtherUtf8EntriesIntact() throws Exception {
		byte[] original;
		try (var in = JavaBridgeTemplate.class.getResourceAsStream("JavaBridgeTemplate.class")) {
			original = java.util.Objects.requireNonNull(in).readAllBytes();
		}
		byte[] renamed = JvmJavaRuntimeBuilder.renameClass(original, "am/ik/rontolisp/codegen/jvm/JavaBridgeTemplate",
				"RontoLispJavaBridge");
		String renamedText = new String(renamed, java.nio.charset.StandardCharsets.ISO_8859_1);
		assertThat(renamedText).doesNotContain("am/ik/rontolisp/codegen/jvm/JavaBridgeTemplate");
		assertThat(renamedText).contains("RontoLispJavaBridge");
		// Renaming back round-trips to the original bytes.
		byte[] roundTripped = JvmJavaRuntimeBuilder.renameClass(renamed, "RontoLispJavaBridge",
				"am/ik/rontolisp/codegen/jvm/JavaBridgeTemplate");
		assertThat(roundTripped).isEqualTo(original);
	}

	// The bridge's entry points are package-private, so a generated class that HAS a
	// package must rename the bridge into that package too -- not into the default
	// package, which is what every other test above compiles into.
	@Test
	void theBridgeIsRenamedIntoTheGeneratedClassOwnPackage() throws Exception {
		// A site left to run time: the bridge travels.
		List<LispVal> program = LispReader.readAllFromString("""
				(defun biggest (cls) (java:static cls "max" 3 7))
				(print (biggest "java.lang.Math"))
				""");
		JvmLispCompiler compiler = new JvmLispCompiler("com/example/Test");
		byte[] classBytes = compiler.compile(program);
		Path packageDir = this.tempDir.resolve("com").resolve("example");
		Files.createDirectories(packageDir);
		Files.write(packageDir.resolve("Test.class"), classBytes);
		writeBeside(compiler);

		String bridgeName = JvmJavaRuntimeBuilder.bridgeName("com/example/Test");
		assertThat(bridgeName).isEqualTo("com/example/Test$JavaBridge");
		assertThat(new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1)).contains(bridgeName);

		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("com.example.Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			assertThat(baos.toString().trim()).isEqualTo("7");
		}
	}

}
