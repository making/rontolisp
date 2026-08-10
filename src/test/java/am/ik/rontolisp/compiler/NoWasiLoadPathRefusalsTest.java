package am.ik.rontolisp.compiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pin for the {@code --no-wasi} build lines: a refusal the LOAD path reaches is named
 * at build time, and one only an export can reach is not. Finding the blockers of a real
 * library chain used to take one node run per blocker plus reading wasm function indices
 * out of a backtrace; every one of them was a fact the build already had.
 */
class NoWasiLoadPathRefusalsTest {

	private static final String LINE = "is reachable from a top-level form of this --no-wasi module";

	/**
	 * The warnings one {@code --no-wasi} compile writes to stderr through
	 * {@link CompileWarnings}.
	 */
	private static String warnings(String source, boolean hostRandom, boolean component) {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		try {
			new WasmLispCompiler(false, component, true, OptimizeLevel.NONE, false, false, hostRandom)
				.compile(LispReader.readAllFromString(source));
		}
		finally {
			System.setErr(oldErr);
		}
		return err.toString();
	}

	private static String warnings(String source) {
		return warnings(source, false, false);
	}

	@Test
	void theClockIsNamedAsAHostObligationRatherThanARefusal() {
		// The clock is the interesting row: since the host can hand a time in, a program
		// that reads it from a top-level form IS loadable -- on a host that sets it
		// first.
		// That is a build-time obligation, and nothing but the build can state it.
		assertThat(warnings("(defvar *started* (get-universal-time))\n")).contains("GET-UNIVERSAL-TIME " + LINE)
			.contains("the top-level (DEFVAR *STARTED*)")
			.contains("__ronto_set_time")
			.contains("BEFORE _initialize");
	}

	@Test
	void aPrimitiveOnlyAnExportCanReachStaysQuiet() {
		// A reactor's handler runs when the HOST calls it, so a refusal in it is an
		// ordinary call-time condition the caller can catch -- not a load-time death. The
		// #'now that reaches the export is a function VALUE, which is the rule that keeps
		// this quiet: following it would flag every handler in every program.
		assertThat(warnings("""
				(defun now () (get-universal-time))
				(defvar *handler* #'now)
				(rontolisp:wasm-export 'now :params '() :returns :s64)
				""")).doesNotContain(LINE);
	}

	@Test
	void aTransitivelyCalledRefusalNamesTheChainThatReachedIt() {
		// The chain is what the wasm backtrace could not give: which library got there.
		assertThat(warnings("""
				(defun outer () (inner))
				(defun inner () (sleep 1))
				(outer)
				""")).contains("SLEEP " + LINE)
			.contains("a top-level form -> OUTER -> INNER")
			.contains("no interval could elapse");
	}

	@Test
	void aSlotDefaultCountsAndNamesItsDefinition() {
		// Upstream lack reaches (get-universal-time) exactly here -- a cookie-state slot
		// default, constructed at load time through find-middleware, i.e. intern +
		// symbol-value, which no name-based call graph can follow. The line names the
		// definition so a reader can tell a slot default from a plain top-level call.
		assertThat(warnings("(defstruct (cookie-state) (expires (get-universal-time) :type integer))\n"))
			.contains("GET-UNIVERSAL-TIME " + LINE)
			.contains("the top-level (DEFSTRUCT COOKIE-STATE)");
	}

	@Test
	void aCaughtRefusalIsNotReportedAndTheOneThatTrapsStillIs() {
		// Every refusal but standard input signals a CATCHABLE condition, so a program
		// that wraps it already handles it -- upstream local-time opens /etc/localtime
		// from a top-level form and falls back to UTC. fd_read traps instead, so no
		// handler covers it and the line stands wherever it is.
		assertThat(warnings("(handler-case (open \"x.txt\") (error () nil))\n")).doesNotContain(LINE);
		assertThat(warnings("(ignore-errors (get-universal-time))\n")).doesNotContain(LINE);
		assertThat(warnings("(handler-case (read-line) (error () nil))\n")).contains("READ-LINE " + LINE)
			.contains("TRAPS")
			.contains("no handler can cover");
		// Given a stream the call reads THAT stream, so it is not the standard-input
		// shape.
		assertThat(warnings("(defvar *in* nil)\n(read-line *in* nil nil)\n")).doesNotContain(LINE);
	}

	@Test
	void entropyNamesHostRandomAndHostRandomSilencesIt() {
		String source = "(defvar *key* (rontolisp:random-bytes 16))\n";
		assertThat(warnings(source)).contains("rontolisp:RANDOM-BYTES " + LINE).contains("--host-random");
		// --host-random routes random_get at a host import, so the entropy IS the host's
		// and there is nothing left to warn about.
		assertThat(warnings(source, true, false)).doesNotContain(LINE);
	}

	@Test
	void theReactorComponentSaysItHasNoHookAtAll() {
		// --component --no-wasi runs its top level at INSTANTIATION, so no host can hand
		// the time in first; naming a hook that shape does not carry would be worse than
		// saying it has none.
		assertThat(warnings("(defvar *started* (get-universal-time))\n", false, true))
			.contains("GET-UNIVERSAL-TIME " + LINE)
			.contains("exposes no way to hand one in")
			.contains("Compile without --component");
	}

	/** clack's shape: the dispatch is inside a local function of the callee. */
	private static final String CLACKUP = """
			(defun eval-file (path) (with-open-file (in path) path))
			(defun clackup (app)
			  (flet ((build (app)
			           (let ((app (typecase app
			                        ((or pathname string) (eval-file app))
			                        (otherwise app))))
			             app)))
			    (build app)))
			(defun handler () nil)
			""";

	@Test
	void aBranchTheArgumentRulesOutIsNotOnTheLoadPath() {
		// The standing line this pass used to print for EVERY clack/ningle/tiny-routes
		// program: clackup keeps a (clackup "app.lisp") branch, and a reactor only ever
		// hands it a function, so the file loader behind that branch is reachable
		// statically and dead dynamically. A warning class whose only routine instance is
		// a false positive teaches the reader to skip the class.
		assertThat(warnings(CLACKUP + "(clackup #'handler)\n")).doesNotContain(LINE);
		// The other direction is the whole reason the narrowing may only ever refute: a
		// call that CAN take the branch still gets the line, chain and all.
		assertThat(warnings(CLACKUP + "(clackup \"app.lisp\")\n")).contains("WITH-OPEN-FILE " + LINE)
			.contains("a top-level form -> CLACKUP -> EVAL-FILE");
		// A shape nothing states is UNKNOWN, which satisfies every type and so prunes
		// nothing -- the direction a wrong answer must never fall in.
		assertThat(warnings(CLACKUP + "(clackup (compute-app))\n")).contains("WITH-OPEN-FILE " + LINE);
	}

	@Test
	void aTopLevelVariableStatesItsShapeTooAndARebindingRetractsIt() {
		// (clackup *app*) over a (defvar *app* #'handler) says exactly what (clackup
		// #'handler) says, one indirection out -- which is how every ningle and
		// tiny-routes Worker spells it.
		assertThat(warnings(CLACKUP + "(defvar *app* #'handler)\n(clackup *app*)\n")).doesNotContain(LINE);
		// Unless something in the program can put another value in it: a special variable
		// any caller may rebind states nothing about what a callee reads.
		assertThat(warnings(CLACKUP + """
				(defvar *app* #'handler)
				(defun with-file (path) (let ((*app* path)) (clackup *app*)))
				(clackup *app*)
				""")).contains("WITH-OPEN-FILE " + LINE);
	}

	@Test
	void aNameTheBodyRebindsCannotCarryTheCallersShape() {
		// The callee's own dolist variable has nothing to do with the caller's #'handler,
		// and a binding form this pass does not model scope for must therefore drop the
		// name to UNKNOWN for the whole body.
		assertThat(warnings("""
				(defun eval-file (path) (with-open-file (in path) path))
				(defun clackup (app)
				  (dolist (app (list "app.lisp"))
				    (typecase app
				      ((or pathname string) (eval-file app))
				      (otherwise app))))
				(defun handler () nil)
				(clackup #'handler)
				""")).contains("WITH-OPEN-FILE " + LINE);
	}

	@Test
	void aWasiCarryingBuildSaysNothing() {
		// Every one of these works on a build that imports WASI, so the lines belong to
		// the flag, not to the program.
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		try {
			new WasmLispCompiler().compile(LispReader.readAllFromString("""
					(defvar *started* (get-universal-time))
					(sleep 1)
					(print (read-line))
					"""));
		}
		finally {
			System.setErr(oldErr);
		}
		assertThat(err.toString()).doesNotContain(LINE);
	}

}
