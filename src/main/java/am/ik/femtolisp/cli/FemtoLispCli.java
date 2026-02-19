package am.ik.femtolisp.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.femtolisp.LispNil;
import am.ik.femtolisp.LispVal;
import am.ik.femtolisp.codegen.jvm.JvmLispCompiler;
import am.ik.femtolisp.codegen.wasm.WasmLispCompiler;
import am.ik.femtolisp.eval.LispEvaluator;
import am.ik.femtolisp.reader.LispReader;

/**
 * CLI entry point for femtolisp.
 */
public final class FemtoLispCli {

	private static final String VERSION = "0.1.0";

	private final PrintStream out;

	private final InputStream in;

	public FemtoLispCli(InputStream in, PrintStream out) {
		this.in = in;
		this.out = out;
	}

	public void run(String[] args) {
		CliOptions options = CliOptions.build(args);

		if (options.contains("-h") || options.contains("--help")) {
			printUsage();
			return;
		}
		if (options.contains("-v") || options.contains("--version")) {
			this.out.println("femtolisp " + VERSION);
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
			compileToFile(source, outputFile);
		}
		else {
			interpret(source);
		}
	}

	private void repl() {
		LispEvaluator evaluator = new LispEvaluator(this.out);
		BufferedReader reader = new BufferedReader(new InputStreamReader(this.in));
		this.out.print("> ");
		this.out.flush();
		StringBuilder buffer = new StringBuilder();
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				if ("(quit)".equals(line.trim())) {
					break;
				}
				buffer.append(line).append('\n');
				if (isBalanced(buffer.toString())) {
					try {
						List<LispVal> exprs = LispReader.readAllFromString(buffer.toString());
						LispVal result = LispNil.INSTANCE;
						for (LispVal expr : exprs) {
							result = evaluator.eval(expr);
						}
						this.out.println(result.print());
					}
					catch (RuntimeException ex) {
						this.out.println("Error: " + ex.getMessage());
					}
					buffer.setLength(0);
					this.out.print("> ");
					this.out.flush();
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void interpret(String source) {
		LispEvaluator evaluator = new LispEvaluator(this.out);
		List<LispVal> exprs = LispReader.readAllFromString(source);
		for (LispVal expr : exprs) {
			evaluator.eval(expr);
		}
	}

	private void compileToFile(String source, String outputFile) {
		List<LispVal> program = LispReader.readAllFromString(source);
		byte[] bytes;
		if (outputFile.endsWith(".wasm")) {
			bytes = new WasmLispCompiler().compile(program);
		}
		else {
			String className = outputFile.replace(".class", "");
			bytes = new JvmLispCompiler(className).compile(program);
		}
		try {
			Files.write(Path.of(outputFile), bytes);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void printUsage() {
		this.out.println("Usage: femtolisp [options] [file]");
		this.out.println("  (no args)          REPL mode");
		this.out.println("  file               Interpret the file");
		this.out.println("  file -o out.class   Compile to JVM bytecode");
		this.out.println("  file -o out.wasm    Compile to WASM");
		this.out.println();
		this.out.println("Options:");
		this.out.println("  -h, --help         Show this help message");
		this.out.println("  -v, --version      Show version");
	}

	private static boolean isBalanced(String input) {
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

	public static void main(String[] args) {
		new FemtoLispCli(System.in, System.out).run(args);
	}

}
