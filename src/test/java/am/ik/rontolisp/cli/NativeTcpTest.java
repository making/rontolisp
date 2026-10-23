package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.SocketsLibrary;
import am.ik.rontolisp.testsupport.SocketTest;
import am.ik.wasm.WasmImports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code rontolisp:tcp-*} in a {@code --native} output: the module a native output
 * carries reaches sockets over the runner's {@code rltcp} imports
 * ({@code runner-sockets.lisp}, the runner's provider of the socket dispatch layer), and
 * an executable runs the loopback programs the component's {@code componentTcp*} tests
 * run, with the interpreter's error texts. The output starts with the network runner
 * ({@code NativeFetchTest}).
 */
class NativeTcpTest {

	@TempDir
	Path tempDir;

	/** The module the command line builds for a native output. */
	private static byte[] module(String source) {
		CompileFrontend.Result frontend = CompileFrontend.run(CompileFrontend.Request.builder()
			.source(source)
			.options(CompileFrontend.Options.builder().wasm(true).nativePlatform("linux-x86_64").build())
			.build());
		return WasmLispCompiler.builder()
			.runnerFetch(true)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.program());
	}

	@Test
	void aSocketProgramImportsTheRunnersHostAndAUsocketOneTheSame() {
		// The tree shaker keeps the imports the program reaches: a listener alone binds
		// three of the twelve, the whole family every one.
		byte[] listener = module("(print (rontolisp:tcp-local-port (rontolisp:tcp-listen 0 \"127.0.0.1\")))");
		assertThat(WasmImports.functionFields(listener, SocketsLibrary.RUNNER_IMPORT_MODULE))
			.containsExactlyInAnyOrder("listen", "localPort", "lastError");
		assertThat(WasmLispCompiler.importModules(listener)).containsExactlyInAnyOrder("wasi_snapshot_preview1",
				SocketsLibrary.RUNNER_IMPORT_MODULE);
		byte[] family = module("""
				(let* ((l (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (c (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port l)))
				       (s (rontolisp:tcp-accept l)))
				  (rontolisp:tcp-set-timeout c 10)
				  (write-line (rontolisp:tcp-local-address l) c)
				  (print (list (read-line s) (rontolisp:tcp-peer-address s) (rontolisp:tcp-peer-port s)))
				  (close c))
				""");
		assertThat(WasmImports.functionFields(family, SocketsLibrary.RUNNER_IMPORT_MODULE)).containsExactlyInAnyOrder(
				"connect", "listen", "accept", "read", "write", "close", "localPort", "localAddress", "peerAddress",
				"peerPort", "setTimeout", "lastError");
		// The usocket shim rides the same provider.
		byte[] usocket = module("(print (usocket:socket-listen \"127.0.0.1\" usocket:*auto-port*))");
		assertThat(WasmLispCompiler.importModules(usocket)).contains(SocketsLibrary.RUNNER_IMPORT_MODULE);
	}

	/**
	 * The loopback programs of the component's tests, in one executable: the echo round
	 * trip (a line, a byte, nil at the peer's close), wire-transparent bytes both ways,
	 * the sequence ops and the eof-tolerant reads in a defun and at the top level (the
	 * sync dispatch and the promoted shape), a bare read signalling end-of-file, the
	 * usocket shim with its typed re-signal, the address accessors, the read deadline,
	 * and the host failures with the interpreter's texts.
	 */
	@SocketTest
	void anExecutableRunsTheLoopbackProgramsWithTheInterpretersErrorTexts() throws Exception {
		assumeTrue(NativeToolchain.availableOnHost(), "no --native shim for this host on the classpath");
		String program = """
				(defun msg (thunk)
				  (handler-case (progn (funcall thunk) :no-error)
				    (simple-error (c) (princ-to-string c))
				    (error (c) (list :other (princ-to-string c)))))
				(defun prefix (got want) (if (eql 0 (search want got)) :ok got))
				(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				       (port (rontolisp:tcp-local-port listener))
				       (client (rontolisp:tcp-connect "localhost" port)))
				  (write-line "hello" client)
				  (write-byte 65 client)
				  (let* ((server (rontolisp:tcp-accept listener))
				         (line (read-line server)))
				    (write-line line server)
				    (print (read-line client))
				    (print (read-byte server))
				    (print (string= (rontolisp:tcp-peer-address server) "127.0.0.1"))
				    (print (= (rontolisp:tcp-peer-port server) (rontolisp:tcp-local-port client)))
				    (print (streamp client))
				    (write-string "AÇB" client)
				    (print (list (read-byte server) (read-byte server) (read-byte server) (read-byte server)))
				    (write-byte 199 client)
				    (write-byte 184 client)
				    (print (char-code (read-char server)))
				    (rontolisp:tcp-set-timeout server 50)
				    (print (msg (lambda () (read-line server))))
				    (rontolisp:tcp-set-timeout server nil)
				    (close client)
				    (print (read-line server))
				    (print (read-char server nil :eof))
				    (print (handler-case (read-byte server) (end-of-file () :signalled)))
				    (print (open-stream-p client))
				    (close server)
				    (close listener)))
				(defun run-sync ()
				  (let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
				         (port (rontolisp:tcp-local-port listener))
				         (client (rontolisp:tcp-connect "127.0.0.1" port))
				         (server (rontolisp:tcp-accept listener))
				         (buf (make-array 4 :initial-element 0)))
				    (write-sequence (vector 1 2 250 4) client)
				    (read-sequence buf server)
				    (print (list (aref buf 0) (aref buf 1) (aref buf 2) (aref buf 3)))
				    (write-sequence (format nil "xyz~%") client :start 1)
				    (print (read-line server nil :eof))
				    (close client)
				    (print (read-byte server nil :eof))
				    (print (handler-case (read-char server) (end-of-file () :signalled)))
				    (close server)
				    (close listener)))
				(run-sync)
				(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
				       (port (usocket:get-local-port listener))
				       (client (usocket:socket-connect "127.0.0.1" port :element-type '(unsigned-byte 8))))
				  (write-line "shim" (usocket:socket-stream client))
				  (let ((server (usocket:socket-accept listener)))
				    (print (read-line (usocket:socket-stream server)))
				    (print (usocket:get-peer-address server))
				    (usocket:socket-close server)
				    (usocket:socket-close client)
				    (usocket:socket-close listener)))
				(print (handler-case (usocket:socket-connect "127.0.0.1" 0)
				         (usocket:socket-error (e) (prefix (princ-to-string e) "tcp-connect: cannot connect to 127.0.0.1:0: "))))
				(print (prefix (msg (lambda () (rontolisp:tcp-connect "127.0.0.1" 0))) "tcp-connect: cannot connect to 127.0.0.1:0: "))
				(let* ((l (rontolisp:tcp-listen 0 "127.0.0.1")) (port (rontolisp:tcp-local-port l)))
				  (print (prefix (msg (lambda () (rontolisp:tcp-listen port "127.0.0.1")))
				                 (format nil "tcp-listen: cannot listen on 127.0.0.1:~D: " port)))
				  (print (msg (lambda () (rontolisp:tcp-accept 1))))
				  (print (msg (lambda () (rontolisp:tcp-peer-port l))))
				  (close l))
				""";
		Path source = this.tempDir.resolve("tcp.lisp");
		Files.writeString(source, program, StandardCharsets.UTF_8);
		Path executable = this.tempDir.resolve("tcp");
		new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			.run(new String[] { source.toString(), "--native", "-o", executable.toString() });
		Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("the executable ended; output: %s", output).isTrue();
		assertThat(output.trim()).as("exit %d", process.exitValue())
			.isEqualTo(String.join("\n", "\"hello\"", "65", "T", "T", "T", "(65 195 135 66)", "504",
					"\"read-line: Read timed out\"", "NIL", ":EOF", ":SIGNALLED", "NIL", "(1 2 250 4)", "\"yz\"",
					":EOF", ":SIGNALLED", "\"shim\"", "\"127.0.0.1\"", ":OK", ":OK", ":OK",
					"\"TCP-ACCEPT expects a listener handle\"", "\"TCP-PEER-PORT expects a connected socket handle\""));
		assertThat(process.exitValue()).isZero();
	}

}
