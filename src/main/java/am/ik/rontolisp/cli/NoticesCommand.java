package am.ik.rontolisp.cli;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.Version;
import org.jspecify.annotations.Nullable;

/**
 * The {@code rontolisp notices} subcommand: the licence notices a distribution owes the
 * code rontolisp puts into it. By default those of a compiled program -- rontolisp's own
 * licence and {@code NOTICE}, which the run-time code every output carries is under, and
 * every {@code THIRD-PARTY-NOTICES.txt} entry a compiled program may carry -- and, with
 * {@code --native}, the notices of the Rust crates the native runner links, for the
 * target platform. {@code --tool} prints instead what the rontolisp jar or native binary
 * itself owes: every entry but the repository's own, the crates of the precompile shim
 * and of every runner it carries and, in the native binary, the GraalVM and JDK notices
 * of the runtime built into it. All of it is read from the classpath ({@code META-INF/},
 * and the files {@code rontolisp-native/build.sh} lays beside each runner), so the native
 * binary answers the same as the jar.
 */
final class NoticesCommand {

	/** The crates of a platform's runners, beside them (rontolisp-native/build.sh). */
	static final String RUNNER_NOTICES = "runner-notices.txt";

	/**
	 * The GraalVM and JDK notices a native binary carries
	 * (src/native/graalvm-notices.sh).
	 */
	static final String GRAALVM_NOTICES = "META-INF/graalvm-notices.txt";

	/** The crates of a platform's precompile shim, beside it. */
	static final String SHIM_NOTICES = "shim-notices.txt";

	private final PrintStream out;

	/**
	 * Create the command.
	 * @param out the stream the notices go to without {@code -o}
	 */
	NoticesCommand(PrintStream out) {
		this.out = out;
	}

	/**
	 * Run the command.
	 * @param args the arguments after the {@code notices} subcommand name
	 * @return the exit code: 0 when the notices were written, 2 on a wrong command line
	 * or a build that does not carry what was asked for
	 */
	int run(String[] args) {
		boolean nativeRunner = false;
		boolean tool = false;
		String platform = null;
		String output = null;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "-h", "--help" -> {
					printUsage();
					return 0;
				}
				case "--native" -> nativeRunner = true;
				case "--tool" -> tool = true;
				case "--native-target", "-o" -> {
					if (i + 1 == args.length) {
						return fail(args[i] + " needs a value");
					}
					if ("-o".equals(args[i])) {
						output = args[++i];
					}
					else {
						platform = args[++i];
					}
				}
				default -> {
					return fail("unknown argument '" + args[i] + "' (try: rontolisp notices --help)");
				}
			}
		}
		if (tool && (nativeRunner || platform != null)) {
			return fail("--tool prints every runner this build carries; it takes no --native or --native-target");
		}
		if (platform != null && !nativeRunner) {
			return fail("--native-target names the platform of --native");
		}
		String text;
		try {
			text = tool ? toolNotices() : programNotices(nativeRunner, platform);
		}
		catch (IllegalArgumentException | UnsupportedOperationException ex) {
			return fail(ex.getMessage());
		}
		if (output == null) {
			this.out.print(text);
			return 0;
		}
		try {
			Files.writeString(Path.of(output), text, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			return fail(output + ": " + ex.getMessage());
		}
		return 0;
	}

	/**
	 * The notices a compiled program's distribution owes rontolisp's code in it.
	 * @param nativeRunner whether the program is a native executable
	 * @param platform its {@code --native-target}, or {@code null} for this machine's
	 * @return the text
	 * @throws UnsupportedOperationException when this build carries no runner for the
	 * platform
	 */
	static String programNotices(boolean nativeRunner, @Nullable String platform) {
		ThirdPartyNotices notices = ThirdPartyNotices.parse(resource(ThirdPartyNotices.RESOURCE));
		StringBuilder text = new StringBuilder();
		text.append("The notices of the code rontolisp ")
			.append(Version.getVersion())
			.append(" puts into a compiled program")
			.append(nativeRunner ? ", its native runner included" : "")
			.append(":\nrontolisp's licence and NOTICE, then the third-party code and data a program may\n")
			.append("carry. Distribute them with the program.\n\n");
		appendOwn(text, notices);
		for (ThirdPartyNotices.Entry entry : notices.shipping(ThirdPartyNotices.OUTPUT)) {
			text.append(ThirdPartyNotices.RULE).append('\n').append(entry.raw());
		}
		if (nativeRunner) {
			String target = NativeTarget.of(platform, null).platform();
			String runner = optionalResource(NativeToolchain.RESOURCE_ROOT + target + "/" + RUNNER_NOTICES);
			if (runner == null) {
				throw new UnsupportedOperationException("--native-target " + target
						+ ": this build of rontolisp carries no runner for it, so no notices of one");
			}
			appendFile(text, "The native runner, " + target, runner);
		}
		return text.toString();
	}

	/**
	 * What the rontolisp jar or native binary itself owes: its licence and NOTICE, every
	 * entry but the repository's own, the crates of each native half it carries and, in
	 * the native binary, the notices of the GraalVM runtime linked into it.
	 */
	static String toolNotices() {
		ThirdPartyNotices notices = ThirdPartyNotices.parse(resource(ThirdPartyNotices.RESOURCE));
		StringBuilder text = new StringBuilder();
		text.append("The notices of rontolisp ").append(Version.getVersion()).append(" itself.\n\n");
		appendOwn(text, notices);
		for (ThirdPartyNotices.Entry entry : notices.shipping(ThirdPartyNotices.OUTPUT, ThirdPartyNotices.TOOL)) {
			text.append(ThirdPartyNotices.RULE).append('\n').append(entry.raw());
		}
		for (String platform : NativeTarget.PLATFORMS) {
			for (String file : List.of(SHIM_NOTICES, RUNNER_NOTICES)) {
				String crates = optionalResource(NativeToolchain.RESOURCE_ROOT + platform + "/" + file);
				if (crates != null) {
					appendFile(text,
							(file.equals(SHIM_NOTICES) ? "The precompile shim, " : "The native runner, ") + platform,
							crates);
				}
			}
		}
		String graalvm = optionalResource(GRAALVM_NOTICES);
		if (graalvm != null) {
			appendFile(text, "The GraalVM runtime of this binary", graalvm);
		}
		return text.toString();
	}

	private static void appendOwn(StringBuilder text, ThirdPartyNotices notices) {
		text.append(ThirdPartyNotices.RULE)
			.append("\nrontolisp\n")
			.append(ThirdPartyNotices.DASH)
			.append('\n')
			.append(resource("META-INF/NOTICE"))
			.append('\n')
			.append(resource("META-INF/LICENSE"))
			.append('\n')
			.append(ThirdPartyNotices.RULE)
			.append('\n')
			.append(notices.preamble());
	}

	private static void appendFile(StringBuilder text, String title, String body) {
		text.append(ThirdPartyNotices.RULE)
			.append('\n')
			.append(title)
			.append('\n')
			.append(ThirdPartyNotices.DASH)
			.append('\n')
			.append(body);
	}

	private static String resource(String name) {
		String text = optionalResource(name);
		if (text == null) {
			throw new IllegalStateException(name + " is missing from the classpath");
		}
		return text;
	}

	/**
	 * A resource of rontolisp's own jar (or classes directory): {@code META-INF/NOTICE}
	 * is a name other jars on a class path carry too, so it is looked up beside this
	 * class first, and on the class loader only where no such location exists (the native
	 * binary, which carries only rontolisp's).
	 */
	private static @Nullable String optionalResource(String name) {
		try (InputStream in = open(name)) {
			return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(name + ": " + ex.getMessage(), ex);
		}
	}

	private static @Nullable InputStream open(String name) throws IOException {
		String self = NoticesCommand.class.getName().replace('.', '/') + ".class";
		URL own = NoticesCommand.class.getClassLoader().getResource(self);
		if (own != null && own.toString().endsWith(self)) {
			String root = own.toString().substring(0, own.toString().length() - self.length());
			try {
				return URI.create(root + name).toURL().openStream();
			}
			catch (FileNotFoundException | NoSuchFileException ex) {
				return null;
			}
			catch (IllegalArgumentException ex) {
				// not a URL this JVM opens: fall back to the class loader
			}
		}
		return NoticesCommand.class.getClassLoader().getResourceAsStream(name);
	}

	private int fail(@Nullable String message) {
		System.err.println("rontolisp: notices: " + message);
		return 2;
	}

	private void printUsage() {
		this.out.println("Usage: rontolisp notices [--native [--native-target PLATFORM]] [-o FILE]");
		this.out.println("       rontolisp notices --tool [-o FILE]");
		this.out.println();
		this.out.println("Prints the licence notices a compiled program's distribution owes the");
		this.out.println("code rontolisp puts into it: rontolisp's licence and NOTICE, and the");
		this.out.println("third-party code and data a program may carry. Ship them with the");
		this.out.println("program. A library a program loads (Quicklisp, Maven, deps.edn) is not");
		this.out.println("among them: its own licence travels with what it put in.");
		this.out.println();
		this.out.println("Options:");
		this.out.println("  --native           Also the Rust crates the native runner of a --native");
		this.out.println("                     output links (this machine's platform)");
		this.out.println("  --native-target P  The runner of platform P instead");
		this.out.println("  --tool             What the rontolisp jar or binary itself owes instead");
		this.out.println("  -o FILE            Write to FILE instead of standard output");
		this.out.println("  -h, --help         Show this help");
	}

}
