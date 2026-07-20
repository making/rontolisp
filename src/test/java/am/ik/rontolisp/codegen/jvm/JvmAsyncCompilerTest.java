package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JVM-backend arm of the cross-backend async/await contract (the compiled twin of
 * {@code AsyncEvalTest}): async-defun/async-lambda over virtual threads, the generic
 * await, first-class streams, and the placement rules as compile errors.
 */
class JvmAsyncCompilerTest {

	@TempDir
	Path tempDir;

	private String compileAndRun(String lispCode) throws Exception {
		// mirror the CLI pipeline's prelude splice so rontolisp:read-all resolves
		List<LispVal> program = LispPreludeLibrary.process(LispReader.readAllFromString(lispCode));
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(program);
		Path classFile = this.tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
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
			finally {
				System.setOut(oldOut);
			}
			return baos.toString().trim();
		}
	}

	@Test
	void asyncDefunEagerStartAndMemoizedAwait() throws Exception {
		assertThat(compileAndRun("""
				(rontolisp:async-defun add (a b) (print "in") (+ a b))
				(print "before")
				(let ((f (add 1 2)))
				  (print "after")
				  (print (rontolisp:futurep f))
				  (print (rontolisp:await f))
				  (print (rontolisp:await f)))
				""")).isEqualTo("\"before\"\n\"in\"\n\"after\"\nt\n3\n3");
	}

	@Test
	void awaitFlattensNestedAsyncCallsAndPassesNonFuturesThrough() throws Exception {
		assertThat(compileAndRun("""
				(rontolisp:async-defun inner () 10)
				(rontolisp:async-defun outer () (+ (rontolisp:await (inner)) 1))
				(print (rontolisp:await (outer)))
				(print (rontolisp:await 42))
				(print (rontolisp:await nil))
				""")).isEqualTo("11\n42\nnil");
	}

	@Test
	void errorInAsyncBodySignalsAtAwaitAcrossThreads() throws Exception {
		assertThat(compileAndRun("""
				(rontolisp:async-defun failing () (error "boom"))
				(print (handler-case (rontolisp:await (failing)) (error (e) "caught")))
				""")).isEqualTo("\"caught\"");
	}

	@Test
	void typedConditionCrossesTheAwait() throws Exception {
		assertThat(compileAndRun("""
				(define-condition my-err (error) ((v :initarg :v :reader my-err-v)))
				(rontolisp:async-defun failing () (error 'my-err :v 7))
				(print (handler-case (rontolisp:await (failing))
				         (my-err (e) (list :caught (my-err-v e)))))
				""")).isEqualTo("(:CAUGHT 7)");
	}

	@Test
	void streamsCarryChunksDrainAndSignalEof() throws Exception {
		assertThat(compileAndRun("""
				(let ((s (rontolisp:make-stream)))
				  (print (rontolisp:streamp s))
				  (print (rontolisp:futurep s))
				  (rontolisp:stream-write s "hello ")
				  (rontolisp:stream-write s "world")
				  (rontolisp:stream-close s)
				  (print (rontolisp:await (rontolisp:read-all s)))
				  (print (rontolisp:await (rontolisp:stream-read s))))
				""")).isEqualTo("t\nnil\n\"hello world\"\nnil");
	}

	@Test
	void twoAsyncBodiesProgressConcurrently() throws Exception {
		assertThat(compileAndRun("""
				(defvar *s1* (rontolisp:make-stream))
				(defvar *s2* (rontolisp:make-stream))
				(rontolisp:async-defun ping ()
				  (rontolisp:stream-write *s1* "ping")
				  (rontolisp:await (rontolisp:stream-read *s2*)))
				(rontolisp:async-defun pong ()
				  (let ((got (rontolisp:await (rontolisp:stream-read *s1*))))
				    (rontolisp:stream-write *s2* (concatenate 'string got "-pong"))
				    got))
				(let ((f1 (ping)) (f2 (pong)))
				  (print (list (rontolisp:await f1) (rontolisp:await f2))))
				""")).isEqualTo("(\"ping-pong\" \"ping\")");
	}

	@Test
	void asyncLambdaWorksAndOpaqueValuesPrint() throws Exception {
		assertThat(compileAndRun("""
				(print (rontolisp:await (funcall (rontolisp:async-lambda (x) (* x 2)) 21)))
				(print (rontolisp:make-stream))
				(print (rontolisp:stream-read (rontolisp:make-stream)))
				""")).isEqualTo("42\n#<STREAM>\n#<FUTURE>");
	}

	@Test
	void unwindProtectCleanupRunsAcrossSuspension() throws Exception {
		assertThat(compileAndRun("""
				(defvar *s* (rontolisp:make-stream))
				(defvar *log* "")
				(rontolisp:async-defun guarded ()
				  (unwind-protect
				      (progn
				        (setq *log* (concatenate 'string *log* "body"))
				        (rontolisp:await (rontolisp:stream-read *s*))
				        (setq *log* (concatenate 'string *log* "+resumed")))
				    (setq *log* (concatenate 'string *log* "+cleanup"))))
				(defvar *f* (guarded))
				(rontolisp:stream-write *s* "x")
				(rontolisp:await *f*)
				(print *log*)
				""")).isEqualTo("\"body+resumed+cleanup\"");
	}

	@Test
	void asyncWrapperExpandsToAsyncDefunAndAsyncLambda() throws Exception {
		assertThat(compileAndRun("""
				(rontolisp:async (defun add (a b) (+ a b)))
				(print (rontolisp:futurep (add 1 2)))
				(print (rontolisp:await (add 20 22)))
				(print (rontolisp:await (funcall (rontolisp:async (lambda (x) (* x 2))) 21)))
				""")).isEqualTo("t\n42\n42");
	}

	@Test
	void asyncWrapperRejectsNonDefiningForms() {
		assertThatThrownBy(() -> compileAndRun("(rontolisp:async (+ 1 2))"))
			.hasMessageContaining("expects a single (defun ...) or (lambda ...) form");
	}

	@Test
	void awaitInPlainDefunIsACompileError() {
		assertThatThrownBy(() -> compileAndRun("(defun bad () (rontolisp:await 1))"))
			.hasMessageContaining("only allowed inside");
		assertThatThrownBy(() -> compileAndRun("""
				(rontolisp:async-defun outer ()
				  (mapcar (lambda (x) (rontolisp:await x)) (list 1 2)))
				""")).hasMessageContaining("only allowed inside");
	}

	@Test
	void streamMisuseSignals() throws Exception {
		assertThat(compileAndRun("""
				(let ((s (rontolisp:make-stream)))
				  (rontolisp:stream-close s)
				  (print (handler-case (rontolisp:stream-write s "late") (error (e) "closed-caught"))))
				""")).isEqualTo("\"closed-caught\"");
	}

	@Test
	void waitForReturnsAFutureSettlingToNil() throws Exception {
		assertThat(compileAndRun("""
				(let ((f (rontolisp:wait-for 10)))
				  (print (rontolisp:futurep f))
				  (print (rontolisp:await f)))
				""")).isEqualTo("t\nnil");
	}

	@Test
	void waitForTimersCompleteInDelayOrderNotStartOrder() throws Exception {
		assertThat(compileAndRun("""
				(defvar *log* nil)
				(rontolisp:async-defun slow () (rontolisp:await (rontolisp:wait-for 300)) (push "slow" *log*))
				(rontolisp:async-defun fast () (rontolisp:await (rontolisp:wait-for 30)) (push "fast" *log*))
				(let ((s (slow)) (f (fast)))
				  (rontolisp:await s)
				  (rontolisp:await f))
				(print (reverse *log*))
				""")).isEqualTo("(\"fast\" \"slow\")");
	}

	@Test
	void waitForRejectsNegativeMilliseconds() throws Exception {
		assertThat(compileAndRun("""
				(print (handler-case (rontolisp:wait-for -1) (error (e) "bad-arg")))
				""")).isEqualTo("\"bad-arg\"");
	}

}
