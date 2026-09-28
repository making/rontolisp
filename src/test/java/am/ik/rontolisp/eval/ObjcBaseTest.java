package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The new {@code objc} base -- LispWorks 8.1's {@code OBJC} call half and {@code COCOA}
 * ({@code objc.lisp} over {@link ObjcPrimitives}) -- on the interpreter. Headless:
 * Foundation objects and an unshown view, never a window. The corpus
 * ({@code objc-base-corpus.lisp}) is what the JVM class and the {@code --native}
 * executable must print byte for byte; here it is pinned against the committed expected
 * output, and the recorded LispWorks answers ({@code objc-lispworks-answers.lisp}) are
 * asserted one by one.
 */
class ObjcBaseTest {

	private static String resource(String name) {
		try (InputStream in = ObjcBaseTest.class.getResourceAsStream("/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Runs a program on a fresh interpreter and answers what it printed. */
	static String interpret(String source) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		for (LispVal form : LispReader.readAllFromString(source)) {
			evaluator.eval(form);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String eval(String source) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal form : LispReader.readAllFromString(source)) {
			result = evaluator.eval(form);
		}
		return result.print();
	}

	@Test
	void everyNameIsDefinedOnEveryMachineAndAMachineWithoutTheRuntimeSignalsAtTheCall() {
		for (String member : PackageRegistry.objcBaseNames()) {
			String name = PackageRegistry.qualify(LispNames.OBJC_PKG, member);
			// fboundp loads no library: the first resolution of one of its names does.
			boolean function = eval("(progn (objc:selector-name \"x\") (fboundp '" + name + "))").equals("T");
			boolean type = List
				.of("OBJC-OBJECT-POINTER", "OBJC-CLASS", "SEL", "OBJC-BOOL", "OBJC-C++-BOOL", "OBJC-C-STRING",
						"OBJC-AT-QUESTION-MARK", "OBJC-UNKNOWN", "STANDARD-OBJC-OBJECT", "OBJC-BLOCK",
						LispNames.OBJC_WITH_AUTORELEASE_POOL)
				.contains(member);
			assertThat(function || type).as(name).isTrue();
		}
		for (String member : List.of("SET-NS-POINT*", "SET-NS-SIZE*", "SET-NS-RECT*", "SET-NS-RANGE*")) {
			assertThat(eval("(progn (objc:selector-name \"x\") (fboundp 'cocoa:"
					+ member.toLowerCase(java.util.Locale.ROOT) + "))"))
				.isEqualTo("T");
		}
		assertThat(eval("cocoa:ns-not-found")).isEqualTo("9223372036854775807");
		if (!ObjcInterop.available()) {
			assertThat(eval("(handler-case (objc:invoke \"NSObject\" \"new\") (error (e) (princ-to-string e)))"))
				.startsWith("\"objc: Objective-C is not available");
		}
	}

	@Test
	void theFoundationStructuresNeedNoRuntime() {
		assertThat(eval("(list (cocoa:set-ns-rect* (make-array 4) 1 2 3 4) (cocoa:set-ns-range* (cons 0 0) 6 5))"))
			.isEqualTo("(#(1 2 3 4) (6 . 5))");
		assertThat(eval("(objc:invoke nil \"length\")")).isEqualTo("NIL");
		assertThat(eval("(objc:selector-name \"frame:\")")).isEqualTo("\"frame:\"");
		assertThat(eval("(handler-case (cocoa:set-ns-point* (make-array 1) 1 2) (error (e) (princ-to-string e)))"))
			.isEqualTo("\"cocoa:set-ns-point*: #(NIL) is not a vector of 2 elements\"");
	}

	@Test
	void theEncodingParserNamesWhatLispWorksNames() {
		// The FLI descriptors objc-class-method-signature answers, from the encoding
		// alone -- no runtime.
		assertThat(eval("(mapcar #'objc::%fli-type (objc::%parse-encoding \"{CGRect={CGPoint=dd}{CGSize=dd}}"
				+ "40@0:8^{_NSRange=QQ}16B24@?28r*32:36#40c44I48q52f56\"))"))
			.isEqualTo("((:STRUCT COCOA:NS-RECT) OBJC:OBJC-OBJECT-POINTER OBJC:SEL (:POINTER (:STRUCT COCOA:NS-RANGE))"
					+ " OBJC:OBJC-C++-BOOL OBJC:OBJC-AT-QUESTION-MARK OBJC:OBJC-C-STRING OBJC:SEL OBJC:OBJC-CLASS"
					+ " (:SIGNED :CHAR) (:UNSIGNED :INT) :LONG-LONG :FLOAT)");
		// A block or a function pointer travels as the pointer it is.
		assertThat(eval("(objc::%callable-types \"v32@0:8@?16^?24\")")).isEqualTo("\"v32@0:8^v16^v24\"");
		// The list form's types become an encoding, variadic ones promoted.
		assertThat(eval("(multiple-value-list (objc::%list-types '(\"f:\" (objc:objc-object-pointer :float :char)"
				+ " :result-type (:unsigned :long) :variadic-num-of-fixed 1)))"))
			.isEqualTo("(\"Q@:@dq\" 1)");
	}

	@Test
	void theMethodFamiliesFollowArc() {
		assertThat(eval("(mapcar #'objc::%owned-result-p '(\"alloc\" \"allocWithZone:\" \"new\" \"newObject\""
				+ " \"copy\" \"mutableCopy\" \"init\" \"initWithFrame:\" \"_init\" \"newline\" \"copying\""
				+ " \"initialize\" \"description\"))"))
			.isEqualTo("(T T T T T T T T T NIL NIL NIL NIL)");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theCorpusPrintsWhatIsCommitted() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(interpret(resource("objc-base-corpus.lisp"))).isEqualTo(resource("objc-base-corpus.expected"));
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theRecordedLispWorksAnswersHold() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		List<LispVal> entries = ((LispCons) LispReader.readAllFromString(resource("objc-lispworks-answers.lisp"))
			.getFirst()).toList();
		assertThat(entries).hasSize(19);
		StringBuilder probes = new StringBuilder("""
				(defvar s (objc:invoke "NSString" "stringWithUTF8String:" "hello world"))
				(defvar c (objc:coerce-to-objc-class "NSString"))
				""");
		for (LispVal entry : entries) {
			List<LispVal> parts = ((LispCons) entry).toList();
			String probe = parts.get(1).print();
			// No foreign memory here: a structure's size is its leaves', every one of
			// them eight bytes wide in these four.
			probe = probe.replaceAll("\\(FLI:SIZE-OF (?:'|\\(QUOTE )(COCOA:NS-[A-Z]+)\\)?\\)",
					"(* 8 (length (objc::%leaves (car (objc::%parse-type (objc::%type-encoding '$1) 0)))))");
			probes.append("(prin1 ").append(probe).append(") (terpri)\n");
		}
		String[] answers = interpret(probes.toString()).split("\n");
		assertThat(answers).hasSize(entries.size());
		for (int i = 0; i < entries.size(); i++) {
			List<LispVal> parts = ((LispCons) entries.get(i)).toList();
			String expected = parts.get(2).print();
			String actual = answers[i];
			if (parts.getFirst().print().equals(":MISSING-METHOD-REPORT")) {
				expected = withoutAddress(expected);
				actual = withoutAddress(actual);
			}
			assertThat(actual).as(parts.getFirst().print()).isEqualTo(expected);
		}
	}

	private static String withoutAddress(String report) {
		return report.replaceAll("#x[0-9A-F]{16}", "#x");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aPointerPrintsAsLispWorksPrintsOneEvenOnTheFirstPrint() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// The library loads lazily INSIDE the first print's argument: the routing to its
		// print-object methods must still see them.
		assertThat(interpret("(print (objc:coerce-to-objc-class \"NSObject\"))"))
			.matches("\\s*#<Pointer: OBJC:OBJC-CLASS = #x[0-9A-F]{16}>\\s*");
		assertThat(interpret("(print (objc:invoke \"NSObject\" \"new\"))"))
			.matches("\\s*#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x[0-9A-F]{16}>\\s*");
		assertThat(interpret("(print (objc:coerce-to-selector \"frame\"))"))
			.matches("\\s*#<Pointer: OBJC:SEL = #x[0-9A-F]{16}>\\s*");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void traceInvokePrintsTheCallAndTheValue() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		String traced = interpret("""
				(objc:trace-invoke "length")
				(objc:invoke (objc:invoke "NSString" "stringWithUTF8String:" "abc") "length")
				(objc:untrace-invoke "length")
				(objc:invoke (objc:invoke "NSString" "stringWithUTF8String:" "abc") "length")
				""");
		assertThat(traced)
			.matches("\\(objc:invoke #<Pointer: OBJC:OBJC-OBJECT-POINTER = #x[0-9A-F]{16}> \"length\"\\)\n  => 3\n");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aModuleThatCannotBeLoadedSignals() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(eval("(handler-case (objc:ensure-objc-initialized :modules '(\"/no/such/Framework\"))"
				+ " (error (e) (princ-to-string e)))"))
			.startsWith("\"objc: the module /no/such/Framework cannot be loaded");
	}

}
