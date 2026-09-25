package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code objc:} verbs on the interpreter. Everything here is headless -- Foundation
 * objects and a class defined at run time, never a window -- so the Mac half runs under a
 * plain {@code ./mvnw test} on a Mac, and the platform-independent half pins what a
 * machine without the runtime must do: define every verb and SIGNAL at the call.
 */
class ObjcInteropTest {

	private String eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result.print();
	}

	@Test
	void everyVerbIsDefinedOnEveryMachineAndAMachineWithoutTheRuntimeSignalsAtTheCall() {
		assertThat(eval("(list (fboundp 'objc:class) (fboundp 'objc:send) (fboundp 'objc:define-class)"
				+ " (fboundp 'objc:on-main) (fboundp 'objc:string) (fboundp 'objc:data) (fboundp 'objc:bytes)"
				+ " (fboundp 'objc:address) (fboundp 'objc:objectp))"))
			.isEqualTo("(T T T T T T T T T)");
		assertThat(ObjcInterop.description()).isNotBlank();
		if (!ObjcInterop.available()) {
			// The condition is an ordinary error naming the verb and the reason, so a
			// program fails at the call that needed the runtime rather than somewhere
			// downstream with an undefined function.
			assertThat(eval("(handler-case (objc:class \"NSObject\") (error (e) (princ-to-string e)))"))
				.startsWith("\"objc:class: Objective-C is not available");
			assertThat(eval("(handler-case (objc:string \"x\") (error (e) (princ-to-string e)))"))
				.startsWith("\"objc:string: Objective-C is not available");
			assertThat(ObjcInterop.mainThreadHandOverRequired()).isFalse();
		}
	}

	@Test
	void theVerbsThatNeedNoRuntimeAnswerEverywhere() {
		assertThat(eval("(objc:objectp 42)")).isEqualTo("NIL");
		assertThat(eval("(objc:send nil \"length\")")).isEqualTo("NIL");
		assertThat(eval("(handler-case (objc:address 42) (error (e) (princ-to-string e)))"))
			.startsWith("\"objc:address expects an Objective-C object");
		// Both new verbs validate their argument before the runtime is opened, so the
		// message a Linux user sees names what they passed, not the absent library.
		assertThat(eval("(handler-case (objc:data '(1 2 3)) (error (e) (princ-to-string e)))"))
			.startsWith("\"objc:data expects a packed float array");
		assertThat(eval("(handler-case (objc:bytes 42) (error (e) (princ-to-string e)))"))
			.startsWith("\"objc:bytes expects an Objective-C object");
	}

	@Test
	void aValueThatIsNoWrapperIsNoObjcObject() {
		assertThat(eval("(list (typep 42 'objc:object) (typep nil 'objc:object) (type-of 42))"))
			.isEqualTo("(NIL NIL INTEGER)");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aWrapperIsOfTheNamedTypeObjcObjectAndNoStructure() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// The type every backend answers (.kb/objc.md): the --native executable's
		// wrapper is a defstruct, and still reads as this type and no structure-object.
		assertThat(eval(WRAPPER_TYPE_PROBE)).isEqualTo(WRAPPER_TYPE_ANSWER);
	}

	static final String WRAPPER_TYPE_PROBE = """
			(let ((w (objc:send (objc:send "NSObject" "alloc") "init")))
			  (list (type-of w) (typep w 'objc:object) (typep w 'structure-object)
			        (typep (objc:class "NSObject") 'objc:object)
			        (typecase w (structure-object :struct) (objc:object :objc) (t :other))
			        (class-name (class-of w)) (eq (class-of w) (find-class 'objc:object))
			        (typep w (type-of w))))
			""";

	static final String WRAPPER_TYPE_ANSWER = "(OBJC:OBJECT T NIL T :OBJC OBJC:OBJECT T T)";

	@Test
	@EnabledOnOs(OS.MAC)
	void aPackedBufferCrossesAsAnNsdataAndComesBackTheSameBytes() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// The bytes are write-sequence's: little-endian, row-major, the dimension header
		// of a packed float array left behind (eval/PackedBuffer, one definition for
		// both).
		assertThat(eval("(objc:bytes (objc:data (make-array 3 :element-type 'single-float"
				+ " :initial-contents '(1.0 2.0 3.0))))"))
			.isEqualTo("#(0 0 128 63 0 0 0 64 0 0 64 64)");
		assertThat(eval("(objc:bytes (objc:data (make-array '(2 2) :element-type 'double-float"
				+ " :initial-contents '((1.0d0 2.0d0) (3.0d0 4.0d0)))))"))
			.isEqualTo("#(0 0 0 0 0 0 240 63 0 0 0 0 0 0 0 64 0 0 0 0 0 0 8 64 0 0 0 0 0 0 16 64)");
		assertThat(eval("(objc:bytes (objc:data (make-array 3 :element-type '(unsigned-byte 16)"
				+ " :initial-contents '(1 258 65535))))"))
			.isEqualTo("#(1 0 2 1 255 255)");
		assertThat(eval("(objc:bytes (objc:data \"hi\"))")).isEqualTo("#(104 105)");
		assertThat(eval("(objc:bytes (objc:data (make-array 0 :element-type '(unsigned-byte 8))))")).isEqualTo("#()");
		// It is an NSData that arrives, so every NSData selector answers for it -- and a
		// mutable one, so mutableBytes is writable scratch a callee can be handed.
		assertThat(eval("(objc:send (objc:data \"hello\") \"length\")")).isEqualTo("5");
		assertThat(eval("(objc:send (objc:data \"hello\") \"isKindOfClass:\" (objc:class \"NSData\"))")).isEqualTo("T");
		assertThat(eval("(integerp (objc:send (objc:data \"hello\") \"mutableBytes\"))")).isEqualTo("T");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theErrorSlotRaisesWhatTheNserrorSaysAndIsSilentWhenTheCallSucceeded() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// A failed call fills the NSError ** the binding allocated for :error, and the
		// verb signals with what it says -- never the bare nil the selector answers.
		assertThat(eval("(handler-case (objc:send \"NSJSONSerialization\" \"JSONObjectWithData:options:error:\""
				+ " (objc:data \"not json\") 0 :error) (error (e) (princ-to-string e)))"))
			.contains("objc:send: JSONObjectWithData:options:error:")
			.contains("NSCocoaErrorDomain");
		// A call that succeeded answers its value: the slot stays NULL and nothing is
		// raised.
		assertThat(eval("(objc:send (objc:send \"NSJSONSerialization\" \"JSONObjectWithData:options:error:\""
				+ " (objc:data \"[1,2,3]\") 0 :error) \"count\")"))
			.isEqualTo("3");
		// :error is only for a pointer parameter; anywhere else it is the operand
		// mismatch it would be without the marker.
		assertThat(eval("(handler-case (objc:send (objc:string \"x\") \"isEqual:\" :error)"
				+ " (error (e) (princ-to-string e)))"))
			.contains("is declared object, not a pointer");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aFoundationObjectRoundTripsThroughTheSelectorsOwnEncoding() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// Each answer is marshalled by the kind the runtime declared: an integer, a C
		// string, a struct as a list, a double, an object that prints its class.
		assertThat(eval("(objc:send (objc:string \"hello\") \"length\")")).isEqualTo("5");
		assertThat(eval("(objc:send (objc:string \"hello\") \"UTF8String\")")).isEqualTo("\"hello\"");
		assertThat(eval("(objc:send (objc:string \"hello world\") \"rangeOfString:\" \"world\")")).isEqualTo("(6 5)");
		assertThat(eval("(objc:send (objc:send \"NSNumber\" \"numberWithDouble:\" 2.5) \"doubleValue\")"))
			.isEqualTo("2.5");
		assertThat(eval("(objc:class \"NSString\")")).isEqualTo("#<objc NSString>");
		assertThat(eval("(objc:send \"NSString\" \"stringWithUTF8String:\" \"a longer string than a tagged one\")"))
			.startsWith("#<objc ");
		assertThat(eval("(objc:send (objc:send (objc:string \"abc\") \"uppercaseString\") \"UTF8String\")"))
			.isEqualTo("\"ABC\"");
		assertThat(eval("(list (objc:objectp (objc:string \"x\")) (integerp (objc:address (objc:string \"x\"))))"))
			.isEqualTo("(T T)");
		assertThat(eval("(objc:send (objc:string \"x\") \"isKindOfClass:\" (objc:class \"NSString\"))")).isEqualTo("T");
		assertThat(eval("(objc:send (objc:string \"x\") \"respondsToSelector:\" \"length\")")).isEqualTo("T");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aClassDefinedAtRunTimeRunsItsLispMethodOnTheMainThread() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// The method is applied from an upcall, with the receiver and the argument
		// wrapped; the answer is the closure's side effect, since performSelector:'s own
		// answer is that of a void method.
		String program = """
				(defvar *seen* nil)
				(defvar *cls* (objc:define-class "RontoLispObjcInteropTest" "NSObject"
				                 (list (list "invoke:" (lambda (self sender)
				                                         (setq *seen* (list (objc:objectp self)
				                                                            (objc:send sender "UTF8String"))))))))
				(defvar *obj* (objc:send (objc:send *cls* "alloc") "init"))
				(objc:send *obj* "performSelector:withObject:" "invoke:" (objc:string "from lisp"))
				*seen*
				""";
		assertThat(eval(program)).isEqualTo("(T \"from lisp\")");
		// Re-defining the class (a REPL re-evaluates its definitions) rebinds the method
		// rather than failing on a name the runtime cannot unregister.
		assertThat(eval("""
				(objc:define-class "RontoLispObjcInteropTest" "NSObject" (list (list "invoke:" (lambda (self x) nil))))
				(objc:define-class "RontoLispObjcInteropTest" "NSObject" (list (list "invoke:" (lambda (self x) nil))))
				""")).isEqualTo("#<objc RontoLispObjcInteropTest>");
		// A callback that errors is reported, never thrown into the native frame.
		assertThat(eval("""
				(objc:define-class "RontoLispObjcInteropTest" "NSObject"
				  (list (list "invoke:" (lambda (self x) (error "boom")))))
				(objc:send (objc:send (objc:send (objc:class "RontoLispObjcInteropTest") "alloc") "init")
				           "performSelector:withObject:" "invoke:" nil)
				:survived
				""")).isEqualTo(":SURVIVED");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aVariadicSelectorTakesItsWholeArgumentListAndTerminatesItself() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		// arrayWithObjects: is declared @@:@ -- byte for byte what arrayWithObject: is --
		// so sending it through the encoding leaves the callee reading its va_list off a
		// stack slot nobody wrote, and the process dies in objc_retain. The table of
		// known variadic selectors is what makes the difference (am.ik.objc's
		// VariadicSelectors): the list is passed as variadic arguments and the nil
		// terminator is the binding's, never the caller's.
		assertThat(eval("(objc:send (objc:send \"NSArray\" \"arrayWithObjects:\" (objc:string \"a\")"
				+ " (objc:string \"b\") (objc:string \"c\")) \"count\")"))
			.isEqualTo("3");
		assertThat(eval("(objc:send (objc:send (objc:send \"NSArray\" \"arrayWithObjects:\" (objc:string \"a\")"
				+ " (objc:string \"b\")) \"componentsJoinedByString:\" (objc:string \"-\")) \"UTF8String\")"))
			.isEqualTo("\"a-b\"");
		// The declared arity alone is a one-element list, not a crash: the terminator is
		// still appended.
		assertThat(
				eval("(objc:send (objc:send \"NSArray\" \"arrayWithObjects:\" (objc:string \"solo\"))" + " \"count\")"))
			.isEqualTo("1");
		// The pairs of dictionaryWithObjectsAndKeys:, value first.
		assertThat(eval("(objc:send (objc:send (objc:send \"NSDictionary\" \"dictionaryWithObjectsAndKeys:\""
				+ " (objc:string \"v1\") (objc:string \"k1\") (objc:string \"v2\") (objc:string \"k2\"))"
				+ " \"objectForKey:\" (objc:string \"k2\")) \"UTF8String\")"))
			.isEqualTo("\"v2\"");
		// The format family takes the carrier its VALUE picks, which is what %@, %ld and
		// %f read back out of the va_list.
		assertThat(eval("(objc:send (objc:send \"NSString\" \"stringWithFormat:\" (objc:string \"%@ %ld %.2f\")"
				+ " (objc:string \"x\") 42 3.5) \"UTF8String\")"))
			.isEqualTo("\"x 42 3.50\"");
		// Below the declared arity it is still the arity error, worded for a list with no
		// end.
		assertThat(eval("(handler-case (objc:send \"NSArray\" \"arrayWithObjects:\") (error (e) (princ-to-string e)))"))
			.contains("arrayWithObjects: takes at least 1 argument(s), got 0");
		// A variadic argument has no declared type to check against, so the refusal is
		// by carrier: an object, a string, an integer or a float.
		assertThat(eval("(handler-case (objc:send \"NSArray\" \"arrayWithObjects:\" (objc:string \"a\") '(1 2))"
				+ " (error (e) (princ-to-string e)))"))
			.contains("is past the declared arity");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aWrongSelectorArityOrOperandIsAConditionNotACrash() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(eval("(handler-case (objc:send (objc:string \"x\") \"nope:\" 1) (error (e) (princ-to-string e)))"))
			.contains("does not respond to nope:");
		assertThat(eval("(handler-case (objc:send (objc:string \"x\") \"length\" 1) (error (e) (princ-to-string e)))"))
			.contains("length takes 0 argument(s), got 1");
		assertThat(eval("(handler-case (objc:send (objc:string \"x\") \"rangeOfString:\" 42)"
				+ " (error (e) (princ-to-string e)))"))
			.contains("argument 1 must be an object");
		assertThat(eval("(handler-case (objc:class \"NoSuchClassAnywhere\") (error (e) (princ-to-string e)))"))
			.contains("no Objective-C class named NoSuchClassAnywhere");
		assertThat(
				eval("(handler-case (objc:define-class \"X\" \"NSObject\" (list (list \"a:b:c:d:e:\" (lambda () nil))))"
						+ " (error (e) (princ-to-string e)))"))
			.contains("outside the supported set");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void onMainAnswersTheBodysValueAndPropagatesItsError() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(eval("(objc:on-main (lambda () (+ 1 2)))")).isEqualTo("3");
		assertThat(eval("(handler-case (objc:on-main (lambda () (error \"inside\"))) (error (e) (princ-to-string e)))"))
			.isEqualTo("\"inside\"");
		// Nested: a body already on thread 0 runs the inner one inline (the re-entrancy
		// rule), so this returns instead of deadlocking on the queue it is draining.
		assertThat(eval("(objc:on-main (lambda () (objc:on-main (lambda () :nested))))")).isEqualTo(":NESTED");
	}

}
