package am.ik.rontolisp.e2e;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.cli.LoadInliner;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.EnvironmentLibrary;
import am.ik.rontolisp.eval.LibraryDefunPruner;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HotSpot refuses to JIT-compile any method above the 8000-bytecode
 * {@code HugeMethodLimit} ({@code .kb/hot-path-method-size.md}), and nothing reports it
 * -- a program is just several times slower than it should be. The invariant's tests used
 * to watch only rontolisp's own methods ({@code LispEvaluatorHotMethodSizeTest}); this
 * one watches what the EMITTER produces over THIRD-PARTY code, because the size of a
 * compiled library method is set by our codegen, not by the library: ironclad's
 * {@code update-sha256-block} sat at 96% of the cliff before integer fusion outlined its
 * round trees (`.kb/jvm-int-fusion.md`), and a codegen change that pushes a library's hot
 * function over the limit must fail here rather than surface as an unexplained slowdown.
 */
class JvmLibraryMethodSizeTest {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "ironclad")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :ironclad)
			(print (ironclad:byte-array-to-hex-string
			        (ironclad:digest-sequence :sha256 (ironclad:ascii-string-to-byte-array "abc"))))
			""";

	@Test
	void noCompiledIroncladMethodCrossesHotSpotsHugeMethodLimit() {
		// The CLI compile pipeline, mirroring AsdfLibraryE2eSupport: inline the system,
		// expand its macros, splice the prelude, prune -- then compile for the JVM.
		List<LispVal> program = LibraryDefunPruner.prune(
				EnvironmentLibrary
					.process(
							am.ik.rontolisp.eval.UnreadCharLibrary
								.process(
										am.ik.rontolisp.eval.GrayStreamsLibrary
											.process(
													LispPreludeLibrary.process(
															UserMacroExpander.expand(LoadInliner.inline(
																	LispReader.readAllFromString(EXERCISE,
																			Features.JVM),
																	SourceLoader.fileSystem(), null,
																	List.of(SYSTEM_DIR), Features.JVM)),
															Features.JVM))),
							WitExportDirective.Backend.OTHER));
		byte[] classBytes = new JvmLispCompiler("IroncladSize").compile(program);
		// Every method that can run per evaluated form must stay under the limit --
		// including the tail continuations a body that would have crossed it is split
		// into (_k$N, JvmBodyOutliner), which is what keeps ironclad's 80-round
		// UPDATE-SHA512-BLOCK off the cliff. The top-level chunks (and main, which only
		// calls them) run once per process and are bounded by the 64 KB method cap
		// instead, so they are the one exclusion.
		assertThat(oversizedMethods(classBytes, Map.of())).as("methods past HotSpot's HugeMethodLimit (8000 bytecodes)")
			.isEmpty();
	}

	/**
	 * The two {@code fast-http} parsers that have no split point on the compile path:
	 * {@code proc-parse}'s {@code match-i-case} generates a decision tree over the header
	 * bytes with the whole "not one of ours" continuation duplicated at every character
	 * position, and the result is one form -- no tail spine for {@code JvmBodyOutliner}
	 * to cut, and every candidate sub-form contains a {@code go} to a label in the frame
	 * it was emitted in. They are named here with the size they compile to today, so they
	 * cannot GROW while the shape waits for a splitter that can cut a branch
	 * ({@code .kb/hot-path-method-size.md}, "Two shapes still have no split point").
	 */
	private static final Map<String, Integer> CLACK_SHAPES_WITHOUT_A_SPLIT_POINT = Map.of(
			"FAST-HTTP$dotPARSER$colon$colonPARSE-HEADER-FIELD-AND-VALUE", 40_000,
			"FAST-HTTP$dotMULTIPART-PARSER$colonHTTP-MULTIPART-PARSE", 33_000);

	/**
	 * The same guard over the clack / ningle stack, which is where the invariant actually
	 * bites: an HTTP request's whole hot path is library code -- {@code fast-http}'s
	 * parsers, {@code lack}'s request decoding, and the CLOS runtime
	 * ({@code %slot-value-runtime}, the {@code %sbr-*} slot dispatch, {@code %mmi-*}
	 * initialization) that every {@code ningle} controller runs through. A steady-state
	 * request loop over the compiled {@code examples/net/httpbin-ningle.lisp} took 11.2 s
	 * per 10,000 requests with those methods over the limit and 6.2 s with them under it:
	 * the cliff was 1.8x of the program, invisible to every functional test.
	 *
	 * <p>
	 * Needs the Quicklisp cache (it quickloads clack and ningle), so it is gated like the
	 * other tests over that stack:
	 *
	 * <pre>{@code
	 * RONTOLISP_NINGLE_E2E=1 ./mvnw -Dtest=JvmLibraryMethodSizeTest -DfailIfNoTests=false test
	 * }</pre>
	 */
	@Test
	@EnabledIfEnvironmentVariable(named = "RONTOLISP_NINGLE_E2E", matches = "1")
	void noCompiledClackNingleMethodCrossesHotSpotsHugeMethodLimit() throws Exception {
		Path example = Path.of("examples", "net", "httpbin-ningle.lisp").toAbsolutePath();
		String source = Files.readString(example);
		byte[] classBytes = new JvmSourceCompiler("HttpbinNingleSize").compile(source, example.toString()).classBytes();
		assertThat(oversizedMethods(classBytes, CLACK_SHAPES_WITHOUT_A_SPLIT_POINT))
			.as("methods past HotSpot's HugeMethodLimit (8000 bytecodes)")
			.isEmpty();
	}

	/**
	 * {@return the emitted methods that are over their limit, by name}
	 *
	 * Every method that can run per evaluated form must stay under 8000 -- including the
	 * tail continuations a body that would have crossed it is split into ({@code _k$N},
	 * {@code JvmBodyOutliner}), the shared emission helpers ({@code _hbGuard}, the
	 * {@code _p*} predicates) and the {@code _ql$N} literal builders. The top-level
	 * chunks (and {@code main}, which only calls them) run once per process and are
	 * bounded by the 64 KB method cap instead, so they are the one blanket exclusion.
	 * @param classBytes the emitted class
	 * @param allowances methods with a HIGHER ceiling than the cliff, by name
	 */
	private static Map<String, Integer> oversizedMethods(byte[] classBytes, Map<String, Integer> allowances) {
		Map<String, Integer> oversized = new LinkedHashMap<>();
		for (MethodModel method : ClassFile.of().parse(classBytes).methods()) {
			String name = method.methodName().stringValue();
			if (name.equals("main") || name.startsWith("_top$") || name.equals("<clinit>")) {
				continue;
			}
			int codeLength = method.findAttribute(Attributes.code()).orElseThrow().codeLength();
			if (codeLength > allowances.getOrDefault(name, 8000)) {
				oversized.put(name, codeLength);
			}
		}
		return oversized;
	}

}
