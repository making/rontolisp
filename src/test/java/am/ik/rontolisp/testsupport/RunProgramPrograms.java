package am.ik.rontolisp.testsupport;

/**
 * {@code uiop:run-program} pinned identically on the interpreter and the JVM, the
 * backends that start a program: a token list run as it is and a string through
 * {@code /bin/sh}, an output slurped as a string, its lines or into a stream, an input
 * vomited from a stream, a file appended to, the error output merged into the output, the
 * working directory, the exit status a {@code subprocess-error} carries unless ignored,
 * and a program that cannot start. Every answer is SBCL 2.2.9's for the same program
 * (2026-11-06). See {@code .kb/uiop.md}, "uiop/launch-program and uiop/run-program".
 */
public final class RunProgramPrograms {

	private RunProgramPrograms() {
	}

	/**
	 * The program; {@code %p} is the path of a scratch file it appends to.
	 * @param file the scratch file
	 * @return the program text
	 */
	public static String program(String file) {
		return PROGRAM.replace("%p", file);
	}

	private static final String PROGRAM = """
			(print (multiple-value-list (uiop:run-program '("echo" "hello" "world") :output :string)))
			(print (uiop:run-program "echo out; echo err >&2" :output '(:string :stripped t)
			                         :error-output :output))
			(print (uiop:run-program '("printf" "a\\\\nb\\\\n") :output :lines))
			(print (with-output-to-string (s) (uiop:run-program '("echo" "into a stream") :output s)))
			(print (with-input-from-string (in "abc")
			         (uiop:run-program '("tr" "a-z" "A-Z") :input in :output :string)))
			(uiop:run-program '("echo" "one") :output "%p" :if-output-exists :supersede)
			(uiop:run-program '("echo" "two") :output "%p" :if-output-exists :append)
			(print (uiop:read-file-lines "%p"))
			(print (uiop:run-program '("pwd") :directory "/" :output '(:string :stripped t)))
			(print (handler-case (uiop:run-program '("sh" "-c" "exit 3"))
			         (uiop:subprocess-error (c)
			           (list (uiop:subprocess-error-code c) (uiop:subprocess-error-command c)))))
			(print (nth-value 2 (uiop:run-program '("sh" "-c" "exit 3") :ignore-error-status t)))
			(print (handler-case (uiop:run-program '("no-such-program-rontolisp"))
			         (error (e) (and (search "no-such-program-rontolisp" (princ-to-string e)) t))))
			""";

	/** What {@link #program} prints. */
	public static final String EXPECTED = """
			("hello world
			" NIL 0)
			"out
			err"
			("a" "b")
			"into a stream
			"
			"ABC"
			("one" "two")
			"/"
			(3 ("sh" "-c" "exit 3"))
			3
			T""";

}
