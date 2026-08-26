package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Upstream CFFI, running here: the real library the ecosystem's C bindings are written
 * against, not a look-alike of its API. Its portable source is vendored UNMODIFIED under
 * {@code src/test/resources/cffi} (the 2026-01-01 release, MIT); what rontolisp supplies
 * is the {@code cffi-sys} backend ({@code cffi-rontolisp.lisp}, over the {@code ffi:}
 * verbs), a replacement {@code cffi.asd} ({@link AsdOverrides}) and a substitute for the
 * one file that cannot load, {@code src/strings.lisp} ({@link ShimLibraries}).
 *
 * <p>
 * That upstream's files load unmodified is the property this whole item exists to keep,
 * so {@link #upstreamsPortableSourceLoadsUnmodified()} is the test that matters -- it is
 * what breaks the day upstream changes one of them, and the fix is then to update the
 * vendored copy and see what the backend owes it. It deliberately needs NO native access:
 * loading cffi is reading and CLOS, and only a call is foreign.
 *
 * <p>
 * The exercising tests below need a machine that grants native access (the test JVM does,
 * {@code --enable-native-access=ALL-UNNAMED} in the surefire argLine) and are skipped
 * where it does not. See {@code .kb/cffi.md}.
 */
class CffiSystemTest {

	private static final List<String> SYSTEM_PATH = List.of(
			Path.of("src", "test", "resources", "cffi").toAbsolutePath().toString(),
			Path.of("src", "test", "resources", "alexandria").toAbsolutePath().toString());

	private static boolean mac() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
	}

	private static String libm() {
		return mac() ? "libm.dylib" : "libm.so.6";
	}

	/** A fresh evaluator with cffi already loaded, and stdout discarded. */
	private static Session cffi() {
		Session session = new Session();
		session.eval("(asdf:load-system :cffi)");
		return session;
	}

	private static final class Session {

		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		private final LispEvaluator evaluator = new LispEvaluator(new PrintStream(this.out, true));

		private Session() {
			this.evaluator.setSystemPath(SYSTEM_PATH);
		}

		private String eval(String source) {
			LispVal result = LispNil.INSTANCE;
			for (LispVal expr : LispReader.readAllFromString(source)) {
				result = this.evaluator.eval(expr);
			}
			return result.print();
		}

	}

	@Test
	void upstreamsPortableSourceLoadsUnmodified() {
		Session session = cffi();
		// The whole tower is there: the type system and its parse-method protocol, the
		// enum layer, the struct layer, defcfun's argument walker, the translate/expand
		// protocol -- and the :string type, which comes from the substituted
		// strings.lisp and is what proves the substitution kept the surface.
		assertThat(session.eval("(list (cffi:foreign-type-size :int) (cffi:foreign-type-size :pointer)"
				+ " (cffi:foreign-type-size :size) (cffi:foreign-type-size :string))"))
			.isEqualTo("(4 8 8 8)");
		assertThat(session.eval("(progn (cffi:defctype my-size :uint64) (cffi:foreign-type-size 'my-size))"))
			.isEqualTo("8");
		assertThat(session.eval(
				"(progn (cffi:defcstruct tv (sec :long) (usec :long))" + " (list (cffi:foreign-type-size '(:struct tv))"
						+ " (cffi:foreign-slot-offset '(:struct tv) 'usec)))"))
			.isEqualTo("(16 8)");
		assertThat(session.eval("(progn (cffi:defcenum color :red :green :blue)"
				+ " (list (cffi:foreign-enum-value 'color :blue) (cffi:foreign-enum-keyword 'color 1)))"))
			.isEqualTo("(2 :GREEN)");
		// Structures by value are the ordinary call path here, so upstream's
		// "load cffi-libffi" default must have been replaced -- by the backend file,
		// which sets the variable BEFORE functions.lisp declares it.
		assertThat(session.eval("(functionp cffi::*foreign-structures-by-value*)")).isEqualTo("T");
		assertThat(session.eval("(handler-case (progn (cffi:defcstruct pair (a :int) (b :int))"
				+ " (cffi::ensure-parsed-base-type '(:struct pair)) :parsed) (error (e) (princ-to-string e)))"))
			.isEqualTo(":PARSED");
	}

	@Test
	void theProcessesOwnSymbolsAnswerADefcfunAndAForeignFuncall() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		Session session = cffi();
		assertThat(session.eval("(progn (cffi:defcfun \"strlen\" :long (s :string)) (strlen \"hello, world\"))"))
			.isEqualTo("12");
		assertThat(session.eval("(integerp (cffi:foreign-funcall \"getpid\" :int))")).isEqualTo("T");
		// An out parameter through with-foreign-object + mem-ref, and a null pointer.
		assertThat(session.eval("(cffi:with-foreign-object (tv :long 2)"
				+ " (cffi:foreign-funcall \"gettimeofday\" :pointer tv :pointer (cffi:null-pointer) :int)"
				+ " (> (cffi:mem-ref tv :long) 0))"))
			.isEqualTo("T");
		// The string round trip, over the substituted strings.lisp.
		assertThat(session.eval("(cffi:with-foreign-string (s \"hello\") (cffi:foreign-string-to-lisp s))"))
			.isEqualTo("\"hello\"");
	}

	@Test
	void aLibraryOpenedAtRunTimeJoinsTheFlatNamespace() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		Session session = cffi();
		// FFM's lookups are per library and the default one never sees a library opened
		// later, so this is the backend's own search order answering -- without it,
		// use-foreign-library followed by a plain defcfun would not resolve, which is
		// what every binding in the ecosystem assumes.
		assertThat(session.eval("(progn (cffi:define-foreign-library libm (t \"" + libm() + "\"))"
				+ " (cffi:use-foreign-library libm) (cffi:defcfun (\"cos\" c-cos) :double (x :double))"
				+ " (c-cos 0.0))"))
			.isEqualTo("1.0");
		assertThat(session.eval("(cffi:pointerp (cffi:foreign-symbol-pointer \"cos\"))")).isEqualTo("T");
		assertThat(session.eval("(cffi:foreign-symbol-pointer \"no_such_symbol_rontolisp\")")).isEqualTo("NIL");
	}

	@Test
	void aStructurePassesByValueWithNoLibffi() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		Session session = cffi();
		// div(3) returns a div_t { int quot; int rem; } BY VALUE. Upstream signals here
		// and offers a restart that loads cffi-libffi; the foreign function API lays the
		// structure out itself, so the call is the ordinary one.
		assertThat(session.eval("(progn (cffi:defcstruct div-t (quot :int) (rem :int))"
				+ " (cffi:defcfun (\"div\" c-div) (:struct div-t) (numer :int) (denom :int))"
				+ " (let ((d (c-div 17 5))) (list (getf d 'quot) (getf d 'rem))))"))
			.isEqualTo("(3 2)");
	}

	@Test
	void aCallbackAndAVariadicCallReachTheForeignSide() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		Session session = cffi();
		assertThat(session.eval("(progn (cffi:defcallback cmp :int ((a :pointer) (b :pointer))"
				+ " (- (cffi:mem-ref a :int) (cffi:mem-ref b :int)))" + " (cffi:with-foreign-object (arr :int 4)"
				+ "  (loop for i from 0 for v in '(4 2 9 1) do (setf (cffi:mem-aref arr :int i) v))"
				+ "  (cffi:foreign-funcall \"qsort\" :pointer arr :long 4 :long 4"
				+ "                        :pointer (cffi:callback cmp) :void)"
				+ "  (loop for i below 4 collect (cffi:mem-aref arr :int i))))"))
			.isEqualTo("(1 2 4 9)");
		// The variadic tail is marked where it starts; without the marker the call is
		// silently wrong on AArch64 and Apple silicon.
		assertThat(session.eval("(cffi:with-foreign-pointer (buf 64)"
				+ " (cffi:foreign-funcall \"snprintf\" :pointer buf :long 64 :string \"%s-%d\""
				+ "                       :string \"x\" :int 7 :int)" + " (cffi:foreign-string-to-lisp buf))"))
			.isEqualTo("\"x-7\"");
	}

	@Test
	void aShareableVectorIsCopiedInAndOut() {
		assumeTrue(FfiInterop.available(), FfiInterop.description());
		Session session = cffi();
		assertThat(session.eval("(let ((v (cffi:make-shareable-byte-vector 4)))"
				+ " (cffi:with-pointer-to-vector-data (p v)"
				+ "  (cffi:foreign-funcall \"memset\" :pointer p :int 65 :long 4 :pointer))" + " (coerce v 'list))"))
			.isEqualTo("(65 65 65 65)");
	}

	@Test
	void grovelAndLibffiRefuseWithTheReason() {
		// Neither can work here, and a half-load or a silent download that ends in an
		// unparseable system definition is worse than a sentence saying why.
		Session session = new Session();
		assertThatThrownBy(() -> session.eval("(asdf:load-system :cffi-grovel)"))
			.hasMessageContaining("Cannot load system 'cffi-grovel'")
			.hasMessageContaining("needs a C toolchain");
		assertThatThrownBy(() -> session.eval("(asdf:load-system :cffi-libffi)"))
			.hasMessageContaining("Cannot load system 'cffi-libffi'")
			.hasMessageContaining("structures by value need no libffi here");
	}

}
