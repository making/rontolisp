package am.ik.rontolisp.cli;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.Version;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;

/**
 * CLI entry point for rontolisp.
 */
public final class RontoLispCli {

	private final PrintStream out;

	private final InputStream in;

	/**
	 * Create a new CLI instance.
	 * @param in the input stream
	 * @param out the output stream
	 */
	public RontoLispCli(InputStream in, PrintStream out) {
		this.in = in;
		this.out = out;
	}

	/**
	 * Run the CLI with the given arguments.
	 * @param args the command-line arguments
	 */
	public void run(String[] args) {
		CliOptions options = CliOptions.build(args);

		if (options.contains("-h") || options.contains("--help")) {
			printUsage();
			return;
		}
		if (options.contains("-v") || options.contains("--version")) {
			this.out.println(Version.getVersionAsJson());
			return;
		}

		if (!options.containsNoKey()) {
			repl();
			return;
		}

		String inputFile = Objects.requireNonNull(options.getNokey());
		String source = readFile(inputFile);

		if (options.contains("-o")) {
			String outputFile = Objects.requireNonNull(options.get("-o"));
			compileToFile(source, outputFile, options.contains("--dynamic"), options.contains("--component"));
		}
		else {
			interpret(source);
		}
	}

	private static boolean isJLineAvailable() {
		try {
			Class.forName("org.jline.reader.LineReader");
			return true;
		}
		catch (ClassNotFoundException _) {
			return false;
		}
	}

	private void repl() {
		LispEvaluator evaluator = new LispEvaluator(this.out, this.in);
		StringBuilder buffer = new StringBuilder();
		if (System.console() != null && isJLineAvailable()) {
			JLineRepl.run(evaluator, this.out, buffer);
		}
		else {
			replWithBufferedReader(evaluator, buffer);
		}
	}

	private void replWithBufferedReader(LispEvaluator evaluator, StringBuilder buffer) {
		BufferedReader reader = new BufferedReader(new InputStreamReader(this.in));
		this.out.print("> ");
		this.out.flush();
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				if ("(quit)".equals(line.trim())) {
					break;
				}
				buffer.append(line).append('\n');
				if (isBalanced(buffer.toString())) {
					evalBuffer(evaluator, this.out, buffer);
					this.out.print("> ");
					this.out.flush();
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static void evalBuffer(LispEvaluator evaluator, PrintStream out, StringBuilder buffer) {
		try {
			List<LispVal> exprs = LispReader.readAllFromString(buffer.toString());
			LispVal result = LispNil.INSTANCE;
			for (LispVal expr : exprs) {
				result = evaluator.eval(expr);
			}
			out.println(result.print());
		}
		catch (RuntimeException ex) {
			out.println("Error: " + ex.getMessage());
		}
		buffer.setLength(0);
	}

	private void interpret(String source) {
		LispEvaluator evaluator = new LispEvaluator(this.out, this.in);
		List<LispVal> exprs = LispReader.readAllFromString(source);
		for (LispVal expr : exprs) {
			evaluator.eval(expr);
		}
	}

	private void compileToFile(String source, String outputFile, boolean dynamic, boolean component) {
		List<LispVal> program = LispReader.readAllFromString(source);
		byte[] bytes;
		if (outputFile.endsWith(".wasm")) {
			bytes = new WasmLispCompiler(dynamic, component).compile(program);
		}
		else {
			String className = outputFile.replace(".class", "");
			bytes = new JvmLispCompiler(className, dynamic).compile(program);
		}
		try {
			Files.write(Path.of(outputFile), bytes);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void printUsage() {
		this.out.println("Usage: rontolisp [options] [file]");
		this.out.println("  (no args)          REPL mode");
		this.out.println("  file               Interpret the file");
		this.out.println("  file -o out.class   Compile to JVM bytecode");
		this.out.println("  file -o out.wasm    Compile to WASM");
		this.out.println();
		this.out.println("Options:");
		this.out.println("  -h, --help         Show this help message");
		this.out.println("  -v, --version      Show version");
		this.out.println("  --dynamic          Resolve unknown calls/vars at runtime (late binding)");
		this.out.println("                     Lets sources that define functions via load compile as-is");
		this.out.println("  --component        Emit a WASI 0.2 component (run with: wasmtime run)");
		this.out.println("                     WASM only; print works, reading/file I/O not yet supported");
		this.out.println("  --buffered-output  Block-buffer stdout (avoids interleaving when piped)");
		this.out.println("                     Off by default so the REPL responds to each line");
	}

	static boolean isBalanced(String input) {
		int depth = 0;
		boolean inString = false;
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (inString) {
				if (c == '\\' && i + 1 < input.length()) {
					i++;
				}
				else if (c == '"') {
					inString = false;
				}
			}
			else {
				if (c == '"') {
					inString = true;
				}
				else if (c == '(') {
					depth++;
				}
				else if (c == ')') {
					depth--;
				}
			}
		}
		return depth <= 0 && !inString;
	}

	private static String readFile(String path) {
		try {
			return Files.readString(Path.of(path));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Main entry point.
	 * @param args the command-line arguments
	 */
	public static void main(String[] args) {
		// By default stdout stays auto-flushing so each REPL input gets an immediate
		// response. Opt in to a block-buffered, non-auto-flushing stream with
		// --buffered-output: flushing on every print/format call interleaves badly when
		// the output is piped, and buffering coalesces it into whole writes. A shutdown
		// hook drains the buffer even on System.exit, and a final flush covers normal
		// exit.
		boolean buffered = false;
		for (String arg : args) {
			if ("--buffered-output".equals(arg)) {
				buffered = true;
				break;
			}
		}
		if (!buffered) {
			new RontoLispCli(System.in, System.out).run(args);
			return;
		}
		PrintStream bufferedOut = new PrintStream(
				new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16), false,
				StandardCharsets.UTF_8);
		System.setOut(bufferedOut);
		Runtime.getRuntime().addShutdownHook(new Thread(bufferedOut::flush));
		try {
			new RontoLispCli(System.in, bufferedOut).run(args);
		}
		finally {
			bufferedOut.flush();
		}
	}

}
