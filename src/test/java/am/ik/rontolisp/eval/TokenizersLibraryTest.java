package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code tokenizer} package ({@code tokenizers.lisp}, spliced/loaded by
 * {@link TokenizersLibrary}) against the tokenizers the models themselves ship.
 *
 * <p>
 * The oracle is not this repository: each {@code src/test/resources/tokenizers/*.lisp}
 * fixture carries a corpus and the ids the Python {@code tokenizers} library produces for
 * it with that model's own {@code tokenizer.json} -- ASCII, spaces and newlines, tabs,
 * numbers and non-decimal numerals, CJK, hangul, emoji (ZWJ sequences and flags
 * included), accents, combining marks, symbols, source code, Cyrillic/Greek/Arabic, and
 * the model's special tokens. The vocabulary and the merge list are trimmed to what the
 * corpus reaches, which is exact for that corpus ({@code .kb/tokenizers.md} has the
 * recipe and why the trim cannot change an id).
 *
 * <p>
 * This is the interpreter half; the cross-backend half is the {@code tokenizer} case of
 * {@code ci-spec.yaml}, which runs the same shapes on the JVM and both WASM backends.
 */
class TokenizersLibraryTest {

	/**
	 * Turns the fixture's code-point lists into strings and builds the tokenizer. The
	 * corpus is stored as code points rather than as string literals so that no editor,
	 * encoding or line-ending can rewrite a test case.
	 */
	private static final String BPE_DRIVER = """
			(defun cps-string (cps)
			  (let ((out (make-array (length cps) :fill-pointer 0)))
			    (dolist (c cps) (vector-push (code-char c) out))
			    (coerce out 'string)))
			(defun fixture-tokens ()
			  (let ((v (make-array *fixture-vocabulary-size* :initial-element nil)))
			    (dolist (pair *fixture-vocabulary* v)
			      (setf (aref v (car pair)) (cdr pair)))))
			(defparameter *tk*
			  (tokenizer:make-bpe (fixture-tokens) *fixture-merges*
			                      :kind *fixture-kind*
			                      :specials *fixture-specials*
			                      :ignore-merges *fixture-ignore-merges*))
			(defun fixture-text (i) (cps-string (nth i *fixture-corpus*)))
			(defun fixture-ids (i) (nth i *fixture-expected*))
			""";

	private static final String SP_DRIVER = """
			(defun cps-string (cps)
			  (let ((out (make-array (length cps) :fill-pointer 0)))
			    (dolist (c cps) (vector-push (code-char c) out))
			    (coerce out 'string)))
			(defparameter *tk*
			  (tokenizer:make-sentencepiece *fixture-pieces* *fixture-scores*))
			(defun fixture-text (i) (cps-string (nth i *fixture-corpus*)))
			(defun fixture-ids (i) (nth i *fixture-expected*))
			""";

	private LispEvaluator fixture(String name, String driver) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		for (LispVal expr : LispReader.readAllFromString(resource(name) + driver)) {
			evaluator.eval(expr);
		}
		return evaluator;
	}

	private String resource(String name) {
		try (InputStream in = getClass().getResourceAsStream("/tokenizers/" + name + ".lisp")) {
			assertThat(in).as(name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private String eval(LispEvaluator evaluator, String input) {
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result.print();
	}

	private String eval(String input) {
		return eval(new LispEvaluator(new PrintStream(new ByteArrayOutputStream())), input);
	}

	/**
	 * Every corpus entry, encoded and decoded back. The ids must be the reference
	 * tokenizer's exactly, and the text must survive the round trip.
	 */
	private void checkCorpus(String name, String driver) {
		LispEvaluator evaluator = fixture(name, driver);
		int cases = Integer.parseInt(eval(evaluator, "(length *fixture-corpus*)"));
		assertThat(cases).as(name).isGreaterThan(10);
		for (int i = 0; i < cases; i++) {
			String text = eval(evaluator, "(fixture-text %d)".formatted(i));
			assertThat(eval(evaluator, "(tokenizer:encode *tk* (fixture-text %d))".formatted(i)))
				.as("%s: encode %s", name, text)
				.isEqualTo(eval(evaluator, "(fixture-ids %d)".formatted(i)));
			assertThat(eval(evaluator, "(tokenizer:decode *tk* (fixture-ids %d))".formatted(i)))
				.as("%s: decode %s", name, text)
				.isEqualTo(text);
		}
	}

	// --- the byte-level BPE, one fixture per pre-tokenizer shape ------------------

	@Test
	void gpt2MatchesTheReferenceTokenizer() {
		checkCorpus("gpt2", BPE_DRIVER);
	}

	@Test
	void smollm2MatchesTheReferenceTokenizer() {
		checkCorpus("smollm2", BPE_DRIVER);
	}

	@Test
	void qwen25MatchesTheReferenceTokenizer() {
		checkCorpus("qwen25", BPE_DRIVER);
	}

	@Test
	void llama32MatchesTheReferenceTokenizer() {
		checkCorpus("llama32", BPE_DRIVER);
	}

	@Test
	void qwen35MatchesTheReferenceTokenizer() {
		checkCorpus("qwen35", BPE_DRIVER);
	}

	// --- the SentencePiece half ---------------------------------------------------

	/**
	 * The same encoder llama2.lisp carries, over the whole 512-piece vocabulary of
	 * {@code examples/llama2/tok512.bin}: the dummy prefix space, the byte-fallback
	 * pieces for everything outside a 512-token vocabulary (CJK and emoji here) and the
	 * greedy merge by score.
	 */
	@Test
	void sentencepieceMatchesRunCsEncoder() {
		checkCorpus("sentencepiece", SP_DRIVER);
	}

	// --- the pre-tokenizers on their own ------------------------------------------

	/** GPT-2 keeps a digit run together and hands the space to the following word. */
	@Test
	void gpt2PreTokenizerCutsWordsSpacesAndDigits() {
		assertThat(eval("""
				(tokenizer:pre-tokenize :gpt2 "Hello, world 1234!")
				""")).isEqualTo("(\"Hello\" \",\" \" world\" \" 1234\" \"!\")");
	}

	/** SmolLM2 splits every digit off on its own first. */
	@Test
	void smollmPreTokenizerSplitsEveryDigit() {
		assertThat(eval("""
				(tokenizer:pre-tokenize :smollm "world 1234!")
				""")).isEqualTo("(\"world\" \" \" \"1\" \"2\" \"3\" \"4\" \"!\")");
	}

	/** Llama 3 takes digits three at a time; Qwen takes them one at a time. */
	@Test
	void llama3AndQwenDifferOnlyInTheDigitRun() {
		assertThat(eval("(tokenizer:pre-tokenize :llama3 \"a 12345\")")).isEqualTo("(\"a\" \" \" \"123\" \"45\")");
		assertThat(eval("(tokenizer:pre-tokenize :qwen2 \"a 12345\")"))
			.isEqualTo("(\"a\" \" \" \"1\" \"2\" \"3\" \"4\" \"5\")");
	}

	/**
	 * A whitespace run ending in a newline stops after the newline for the Llama 3 / Qwen
	 * shapes, which have the {@code \\s*[\r\n]+} alternative; GPT-2, which has not, gives
	 * up its last character instead so the next word carries the space.
	 */
	@Test
	void newlineRunsCutDifferentlyPerShape() {
		assertThat(eval("(tokenizer:pre-tokenize :qwen2 \"a\n\n  b\")")).isEqualTo("(\"a\" \"\n\n\" \" \" \" b\")");
		assertThat(eval("(tokenizer:pre-tokenize :gpt2 \"a\n\n  b\")")).isEqualTo("(\"a\" \"\n\n \" \" b\")");
	}

	/** Qwen 3.5 keeps a combining mark with its letter; Qwen 2 splits it off. */
	@Test
	void qwen35KeepsCombiningMarksWithTheirLetter() {
		String text = "\"o\\u0327k\"".replace("\\u0327", "\u0327");
		assertThat(eval("(tokenizer:pre-tokenize :qwen35 %s)".formatted(text))).isEqualTo("(\"o\u0327k\")");
		assertThat(eval("(tokenizer:pre-tokenize :qwen2 %s)".formatted(text))).isEqualTo("(\"o\" \"\u0327k\")");
	}

	/** The GGUF spelling of the same pre-tokenizers is accepted. */
	@Test
	void ggufPreNamesAreAccepted() {
		assertThat(eval("(tokenizer:pre-tokenize \"llama-bpe\" \"a 12345\")"))
			.isEqualTo(eval("(tokenizer:pre-tokenize :llama3 \"a 12345\")"));
		assertThat(eval("(tokenizer:pre-tokenize \"gpt-2\" \"a 12345\")"))
			.isEqualTo(eval("(tokenizer:pre-tokenize :gpt2 \"a 12345\")"));
	}

	@Test
	void anUnknownPreTokenizerIsRefusedByName() {
		assertThat(eval("""
				(handler-case (tokenizer:pre-tokenize "bert" "x")
				  (error (e) (format nil "~a" e)))
				""")).contains("unknown pre-tokenizer");
	}

	// --- the accessors, and bos/eos -----------------------------------------------

	@Test
	void bosAndEosBracketTheIdsOnlyWhenAsked() {
		LispEvaluator evaluator = fixture("llama32", BPE_DRIVER);
		assertThat(eval(evaluator, "(tokenizer:bos-id *tk*)")).isEqualTo("NIL");
		String plain = eval(evaluator, "(tokenizer:encode *tk* \"hi\")");
		assertThat(eval(evaluator, """
				(let ((tk (tokenizer:make-bpe (fixture-tokens) *fixture-merges*
				                              :kind *fixture-kind*
				                              :specials *fixture-specials*
				                              :ignore-merges *fixture-ignore-merges*
				                              :bos 128000 :eos 128001)))
				  (list (tokenizer:bos-id tk) (tokenizer:eos-id tk)
				        (tokenizer:encode tk "hi")
				        (tokenizer:encode tk "hi" :bos t :eos t)))
				""")).isEqualTo(
				"(128000 128001 %s (128000 %s 128001))".formatted(plain, plain.substring(1, plain.length() - 1)));
	}

	@Test
	void theVocabularyIsReadableByIdAndByToken() {
		LispEvaluator evaluator = fixture("smollm2", BPE_DRIVER);
		assertThat(eval(evaluator, "(tokenizer:vocabulary-size *tk*)"))
			.isEqualTo(eval(evaluator, "*fixture-vocabulary-size*"));
		assertThat(eval(evaluator, "(tokenizer:token-id *tk* \"<|endoftext|>\")")).isEqualTo("0");
		assertThat(eval(evaluator, "(tokenizer:token-string *tk* 0)")).isEqualTo("\"<|endoftext|>\"");
		assertThat(eval(evaluator, "(tokenizer:token-id *tk* \"no such token\")")).isEqualTo("NIL");
	}

	/**
	 * The decode is byte-level: a multi-byte character straddling two tokens only appears
	 * once both are in hand, which is what a generation loop needs.
	 */
	@Test
	void decodeBytesIsTheStreamingHalf() {
		LispEvaluator evaluator = fixture("smollm2", BPE_DRIVER);
		String ids = eval(evaluator, "(tokenizer:encode *tk* \"\u65e5\")");
		assertThat(eval(evaluator, "(length (tokenizer:decode-bytes *tk* '%s))".formatted(ids))).isEqualTo("3");
		assertThat(eval(evaluator, "(tokenizer:decode *tk* (list (car '%s)))".formatted(ids))).isEqualTo("\"\"");
	}

	// --- the splice ---------------------------------------------------------------

	@Test
	void processSplicesOnlyWhenThePackageIsReferenced() {
		List<LispVal> untouched = LispReader.readAllFromString("(print (+ 1 2))");
		assertThat(TokenizersLibrary.process(untouched)).isSameAs(untouched);
		List<LispVal> using = LispReader.readAllFromString("(tokenizer:pre-tokenize :gpt2 \"hi\")");
		assertThat(TokenizersLibrary.process(using)).hasSizeGreaterThan(using.size());
	}

	@Test
	void theLibraryReachesForNothingButCommonLisp() {
		String source = TokenizersLibrary.forms().stream().map(LispVal::print).reduce("", String::concat);
		assertThat(source).doesNotContain("linalg:").doesNotContain("objc:").doesNotContain("java:");
		assertThat(source).doesNotContain("WITH-OPEN-FILE").doesNotContain("OPEN ").doesNotContain("READ-SEQUENCE");
	}

}
