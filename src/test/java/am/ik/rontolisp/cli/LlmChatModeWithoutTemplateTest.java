package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * examples/llm/llm.lisp's {@code -m chat} on a checkpoint with no chat template used to
 * fall through to plain generation silently: the user asked for chat mode and got a
 * well-formed answer to a different question, with no diagnostic. {@code stories260K.bin}
 * (a plain llama, checked in beside {@code tok512.bin}) has no {@code <|im_start|>} in
 * its 512-token vocabulary, so it reproduces this without a download.
 */
class LlmChatModeWithoutTemplateTest {

	private static final Path LLM_LISP = Path.of("examples/llm/llm.lisp").toAbsolutePath();

	private static final Path CHECKPOINT = Path.of("examples/llm/stories260K.bin").toAbsolutePath();

	private static final Path TOKENIZER = Path.of("examples/llm/tok512.bin").toAbsolutePath();

	@Test
	void chatModeOnATemplatelessCheckpointRefusesInsteadOfSilentlyGenerating() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out));
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		int code;
		try {
			code = RontoLispCli.runReporting(cli, new String[] { LLM_LISP.toString(), "--", CHECKPOINT.toString(), "-z",
					TOKENIZER.toString(), "-m", "chat", "-i", "Once upon a time", "-n", "5" });
		}
		finally {
			System.setErr(oldErr);
		}
		String stderr = err.toString(StandardCharsets.UTF_8);
		// A usage error, not a default: the message must name the checkpoint that has no
		// template AND say which mode it does support, so it tells the user which of the
		// two to change.
		assertThat(code).as("stdout: %s, stderr: %s", out, stderr).isNotZero();
		assertThat(stderr).contains(CHECKPOINT.toString()).contains("generate");
	}

}
