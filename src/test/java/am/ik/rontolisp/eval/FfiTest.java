package am.ik.rontolisp.eval;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code ffi:} verbs on the interpreter: the foreign primitives CFFI's backend is
 * written over. The machine-independent half pins what every platform must do -- the
 * verbs defined, the pure type arithmetic, the pointer value model, the compile-path
 * refusal -- and the native half runs wherever the JVM grants native access (the test JVM
 * does, {@code --enable-native-access=ALL-UNNAMED} in the surefire argLine), opening
 * {@code libm}, {@code libsqlite3} and the process itself.
 */
class FfiTest {

	private String eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result.print();
	}

	private static boolean mac() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
	}

	private static String libm() {
		return mac() ? "libm.dylib" : "libm.so.6";
	}

	private static String libsqlite3() {
		return mac() ? "libsqlite3.dylib" : "libsqlite3.so.0";
	}

	@Test
	void everyVerbIsDefinedOnEveryMachineAndAMachineWithoutNativeAccessSignalsAtTheCall() {
		assertThat(eval("(list (fboundp 'ffi:open) (fboundp 'ffi:symbol) (fboundp 'ffi:call)"
				+ " (fboundp 'ffi:callback) (fboundp 'ffi:alloc) (fboundp 'ffi:free) (fboundp 'ffi:peek)"
				+ " (fboundp 'ffi:poke) (fboundp 'ffi:size) (fboundp 'ffi:align) (fboundp 'ffi:pointerp)"
				+ " (fboundp 'ffi:address) (fboundp 'ffi:errno))"))
			.isEqualTo("(T T T T T T T T T T T T T)");
		assertThat(FfiInterop.description()).isNotBlank();
		if (!FfiInterop.available()) {
			// The condition is an ordinary error naming the verb and the reason, so a
			// program fails at the call that needed the foreign function rather than
			// somewhere downstream with an undefined function.
			assertThat(eval("(handler-case (ffi:open \"libm.so.6\") (error (e) (princ-to-string e)))"))
				.startsWith("\"ffi:open: foreign functions are not available");
			assertThat(eval("(handler-case (ffi:alloc 8) (error (e) (princ-to-string e)))"))
				.startsWith("\"ffi:alloc: foreign functions are not available");
		}
	}

	@Test
	void theVerbsThatNeedNoRuntimeAnswerEverywhere() {
		// A pointer is its own value: pointerp answers nil for an integer, address is
		// its own inverse, two pointers to one address are equal, and NULL is a legal
		// pointer.
		assertThat(eval("(ffi:pointerp 42)")).isEqualTo("NIL");
		assertThat(eval("(ffi:pointerp (ffi:address 42))")).isEqualTo("T");
		assertThat(eval("(ffi:address (ffi:address 42))")).isEqualTo("42");
		assertThat(eval("(equal (ffi:address 7) (ffi:address 7))")).isEqualTo("T");
		assertThat(eval("(princ-to-string (ffi:address 255))")).isEqualTo("\"#<pointer #xff>\"");
		assertThat(eval("(ffi:address (ffi:address 0))")).isEqualTo("0");
		// The pure type arithmetic: sizes and alignments, the C struct layout rule
		// (padding between members and at the tail), nesting.
		assertThat(eval("(list (ffi:size :char) (ffi:size :short) (ffi:size :int) (ffi:size :long)"
				+ " (ffi:size :float) (ffi:size :double) (ffi:size :pointer) (ffi:size :string))"))
			.isEqualTo("(1 2 4 8 4 8 8 8)");
		assertThat(eval("(list (ffi:align :char) (ffi:align :int) (ffi:align :double))")).isEqualTo("(1 4 8)");
		assertThat(eval("(list (ffi:size '(:struct :char :int)) (ffi:align '(:struct :char :int))"
				+ " (ffi:size '(:struct :char :char)) (ffi:size '(:struct :char (:struct :int :int)))"
				+ " (ffi:size '(:struct :int :double)))"))
			.isEqualTo("(8 4 2 12 16)");
		assertThat(eval("(handler-case (ffi:size :flonum) (error (e) (princ-to-string e)))"))
			.isEqualTo("\"ffi:size: no such foreign type: :flonum\"");
		assertThat(eval("(handler-case (ffi:size :void) (error (e) (princ-to-string e)))"))
			.isEqualTo("\"ffi:size: :void has no size\"");
	}

	@Test
	void applyCallIsFfiCallWithTheArgumentsAsOneList() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		// The internal fixed-arity spelling the cffi backend calls instead of
		// (apply #'ffi:call ...), so the compiled backends need no first-class
		// #'ffi:call; same answer as the spread form.
		assertThat(eval("""
				(let ((strlen (ffi:symbol (ffi:open) "strlen")))
				  (list (ffi:call strlen :long '(:string) "hello, world")
				        (ffi:%apply-call strlen :long '(:string) (list "hello, world"))))
				""")).isEqualTo("(12 12)");
	}

	@Test
	void theProcessesOwnSymbolsAnswerACallAndAMissingSymbolIsNil() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		// (ffi:open) with no argument is the process itself; a :string argument
		// marshals through a call-scoped arena.
		assertThat(eval("(ffi:call (ffi:symbol (ffi:open) \"strlen\") :long '(:string) \"hello, world\")"))
			.isEqualTo("12");
		assertThat(eval("(ffi:symbol (ffi:open) \"no_such_symbol_rontolisp\")")).isEqualTo("NIL");
	}

	@Test
	void aNamedLibraryOpensAndCallsAtTheFloatingTypes() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		assertThat(eval("(ffi:call (ffi:symbol (ffi:open \"" + libm() + "\") \"cos\") :double '(:double) 0.0)"))
			.isEqualTo("1.0");
		// :float narrows on the way down and widens on the way back.
		assertThat(eval("(ffi:call (ffi:symbol (ffi:open \"" + libm() + "\") \"fabsf\") :float '(:float) -2.5)"))
			.isEqualTo("2.5");
		// An integer operand fits a floating parameter.
		assertThat(eval("(ffi:call (ffi:symbol (ffi:open \"" + libm() + "\") \"cos\") :double '(:double) 0)"))
			.isEqualTo("1.0");
	}

	@Test
	void aThirdPartyLibraryAnswersAStringReturn() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		assumeTrue(eval("(handler-case (progn (ffi:open \"" + libsqlite3() + "\") t) (error (e) nil))").equals("T"),
				"libsqlite3 is not installed here");
		assertThat(eval(
				"(ffi:call (ffi:symbol (ffi:open \"" + libsqlite3() + "\") \"sqlite3_libversion\")" + " :string '())"))
			.matches("\"\\d+\\.\\d+\\.\\d+\"");
	}

	@Test
	void aStringRoundTripsThroughForeignMemory() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		// strdup mallocs a copy; ffi:peek :string reads the NUL-terminated UTF-8 at the
		// address, and ffi:free returns the memory -- C's own ownership, end to end.
		assertThat(eval("(let ((p (ffi:call (ffi:symbol (ffi:open) \"strdup\") :pointer '(:string) \"hello\")))"
				+ " (prog1 (ffi:peek p :string) (ffi:free p)))"))
			.isEqualTo("\"hello\"");
	}

	@Test
	void everyScalarTypeRoundTripsThroughPeekAndPoke() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		assertThat(eval("""
				(let ((p (ffi:alloc 16)))
				  (prog1 (list (progn (ffi:poke p :char -1) (ffi:peek p :char))
				               (progn (ffi:poke p :uchar 255) (ffi:peek p :uchar))
				               (progn (ffi:poke p :short -32768) (ffi:peek p :short))
				               (progn (ffi:poke p :ushort 65535) (ffi:peek p :ushort))
				               (progn (ffi:poke p :int -123456) (ffi:peek p :int))
				               (progn (ffi:poke p :uint 4294967295) (ffi:peek p :uint))
				               (progn (ffi:poke p :long -1) (ffi:peek p :long))
				               (progn (ffi:poke p :ulong 18446744073709551615) (ffi:peek p :ulong))
				               (progn (ffi:poke p :float 1.5) (ffi:peek p :float))
				               (progn (ffi:poke p :double -2.25) (ffi:peek p :double))
				               (progn (ffi:poke p :pointer (ffi:address 4096))
				                      (ffi:address (ffi:peek p :pointer))))
				    (ffi:free p)))
				""")).isEqualTo("(-1 255 -32768 65535 -123456 4294967295 -1 18446744073709551615 1.5 -2.25 4096)");
		// An offset addresses a slot, aligned or not.
		assertThat(eval("(let ((p (ffi:alloc 16))) (ffi:poke p :int 7 4) (ffi:poke p :int 9 9)"
				+ " (prog1 (list (ffi:peek p :int 4) (ffi:peek p :int 9)) (ffi:free p)))"))
			.isEqualTo("(7 9)");
	}

	@Test
	void aStructReturnedByValueArrivesInForeignMemoryAndReadsBySlot() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		// div answers a div_t {int quot; int rem;} BY VALUE; the primitive copies it to
		// malloc'd memory and the slots read back through ffi:peek at their offsets.
		assertThat(eval("(let ((p (ffi:call (ffi:symbol (ffi:open) \"div\") '(:struct :int :int) '(:int :int) 7 2)))"
				+ " (prog1 (list (ffi:peek p :int) (ffi:peek p :int 4)) (ffi:free p)))"))
			.isEqualTo("(3 1)");
	}

	@Test
	void qsortSortsThroughALispCallback() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		assertThat(eval("""
				(let ((buf (ffi:alloc 16))
				      (cmp (ffi:callback (lambda (a b) (- (ffi:peek a :int) (ffi:peek b :int)))
				                         :int '(:pointer :pointer))))
				  (ffi:poke buf :int 5)
				  (ffi:poke buf :int 3 4)
				  (ffi:poke buf :int 9 8)
				  (ffi:poke buf :int 1 12)
				  (ffi:call (ffi:symbol (ffi:open) "qsort") :void '(:pointer :long :long :pointer) buf 4 4 cmp)
				  (prog1 (list (ffi:peek buf :int) (ffi:peek buf :int 4) (ffi:peek buf :int 8)
				               (ffi:peek buf :int 12))
				    (ffi:free buf)))
				""")).isEqualTo("(1 3 5 9)");
	}

	@Test
	void aCallbackIsAnOrdinaryCodeAddressAndAnEscapedErrorAnswersZero() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		// The callback's address is callable like any C function -- a downcall into our
		// own upcall stub.
		assertThat(eval("(let ((cb (ffi:callback (lambda (a b) (+ (ffi:address a) (ffi:address b)))"
				+ " :int '(:pointer :pointer)))) (ffi:call cb :int '(:pointer :pointer) 20 22))"))
			.isEqualTo("42");
		// An error the Lisp handler does not catch is printed and swallowed (unwinding
		// into the native frame above an upcall would end the process); the callback
		// answers zero.
		assertThat(eval("(let ((cb (ffi:callback (lambda (a b) (error \"boom\")) :int '(:pointer :pointer))))"
				+ " (ffi:call cb :int '(:pointer :pointer) 1 2))"))
			.isEqualTo("0");
	}

	@Test
	void aVarargsCallPlacesTheTailByTheMarker() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		// snprintf(char*, size_t, const char*, ...): the :varargs marker is where the
		// variadic tail starts -- without it the call is silently wrong on
		// AArch64/Apple.
		assertThat(eval("""
				(let ((buf (ffi:alloc 64)))
				  (ffi:call (ffi:symbol (ffi:open) "snprintf") :int
				            '(:pointer :long :string :varargs :string :int) buf 64 "%s=%d" "n" 42)
				  (prog1 (ffi:peek buf :string) (ffi:free buf)))
				""")).isEqualTo("\"n=42\"");
	}

	@Test
	void errnoIsCapturedFromAFailedCall() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		// open(2) on a path that does not exist: -1, and ffi:errno answers the ENOENT
		// that call left -- captured per thread, never fetched later.
		assertThat(eval("(list (ffi:call (ffi:symbol (ffi:open) \"open\") :int '(:string :int)"
				+ " \"/no/such/rontolisp-file\" 0) (ffi:errno))"))
			.isEqualTo("(-1 2)");
	}

	@Test
	void theFailuresSignalWithAMessageNamingTheVerb() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		assertThat(eval(
				"(handler-case (ffi:open \"libdoesnotexist-rontolisp.so.99\")" + " (error (e) (princ-to-string e)))"))
			.startsWith("\"ffi:open: the library");
		assertThat(eval("(handler-case (ffi:call (ffi:address 0) :void '()) (error (e) (princ-to-string e)))"))
			.isEqualTo("\"ffi:call: a null function pointer cannot be called\"");
		assertThat(eval("(handler-case (ffi:call (ffi:symbol (ffi:open \"" + libm() + "\") \"cos\")"
				+ " :double '(:double) \"x\") (error (e) (princ-to-string e)))"))
			.isEqualTo("\"ffi:call: \\\"x\\\" does not fit :double\"");
		assertThat(eval("(handler-case (ffi:peek (ffi:address 0) :int) (error (e) (princ-to-string e)))"))
			.isEqualTo("\"ffi:peek: a null pointer cannot be read\"");
		assertThat(eval("(handler-case (ffi:call (ffi:symbol (ffi:open) \"strlen\") :long '(:string) \"x\" \"y\")"
				+ " (error (e) (princ-to-string e)))"))
			.isEqualTo("\"ffi:call declares 1 argument but got 2\"");
	}

	@Test
	void theFirstFfiReferenceIsFoundQualifiedOrInPackage() {
		assertThat(FfiInterop.firstFfiReference(read("(print 1) (defun f () (ffi:open \"libm.so.6\"))")))
			.isEqualTo("FFI:OPEN");
		assertThat(FfiInterop.firstFfiReference(read("(in-package ffi) (open \"libm.so.6\")"))).isEqualTo("FFI:OPEN");
		assertThat(FfiInterop.firstFfiReference(read("(in-package cl-user) (defun size (x) x) (print 'call)")))
			.isNull();
		assertThat(FfiInterop.firstFfiReference(read("(objc:send x \"y\")"))).isNull();
	}

	@Test
	void theCompilePathRefusesTheWasmBackendsByName(@TempDir Path dir) throws IOException {
		Path source = dir.resolve("native.lisp");
		Files.writeString(source, "(print (ffi:call (ffi:symbol (ffi:open) \"getpid\") :int '()))\n");
		Path loader = dir.resolve("loader.lisp");
		Files.writeString(loader, "(load \"native.lisp\")\n");
		for (String[] output : new String[][] { { "-o", dir.resolve("prog.wasm").toString() },
				{ "-o", dir.resolve("comp.wasm").toString(), "--component" } }) {
			assertThatThrownBy(() -> compile(source, output)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Cannot compile: FFI:CALL")
				.hasMessageContaining("not in a .wasm");
			// A (load ...)-ed file is caught too: the refusal runs after load inlining.
			assertThatThrownBy(() -> compile(loader, output)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Cannot compile: FFI:CALL");
		}
	}

	private static void compile(Path source, String... options) {
		List<String> args = new ArrayList<>();
		args.add(source.toString());
		args.addAll(List.of(options));
		new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()))
			.run(args.toArray(String[]::new));
	}

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

}
