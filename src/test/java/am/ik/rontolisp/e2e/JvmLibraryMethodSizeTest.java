package am.ik.rontolisp.e2e;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
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
		Map<String, Integer> oversized = new LinkedHashMap<>();
		for (MethodModel method : ClassFile.of().parse(classBytes).methods()) {
			String name = method.methodName().stringValue();
			if (name.equals("main") || name.startsWith("_top$") || name.equals("<clinit>")) {
				continue;
			}
			int codeLength = method.findAttribute(Attributes.code()).orElseThrow().codeLength();
			if (codeLength > 8000) {
				oversized.put(name, codeLength);
			}
		}
		assertThat(oversized).as("methods past HotSpot's HugeMethodLimit (8000 bytecodes)").isEmpty();
	}

}
