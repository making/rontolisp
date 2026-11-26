package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.runtime.RontoInflate;
import am.ik.rontolisp.testsupport.InflateCases;
import am.ik.rontolisp.testsupport.InflateCases.Case;
import am.ik.rontolisp.testsupport.InflateCases.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wasm targets' decoder of a compressed reply, {@code inflate.lisp}: the same decoder
 * as the interpreter's and the JVM's {@link RontoInflate} unit for unit -- the octets,
 * the message of a malformed stream, what is cut short -- however the input is chunked
 * ({@code RontoInflateTest} holds that one to {@code java.util.zip}); and spliced into a
 * wasm program that names it, and nothing else.
 */
class InflateLibraryTest {

	/**
	 * Feeds a case's octets to the Lisp decoder a chunk at a time, each update answering
	 * at most LIMIT octets and drained before the next chunk, the way
	 * {@code RontoInflateTest.decode} feeds the Java one.
	 */
	private static final String DRIVER = """
			(defun inflate-case (kind input chunk limit)
			  (let ((st (rontolisp::%inflate-new kind)) (out nil) (i 0) (n (length input)) (failed nil))
			    (loop
			      (let* ((end (min n (+ i chunk)))
			             (piece (make-array (- end i) :element-type '(unsigned-byte 8))))
			        (replace piece input :start2 i :end2 end)
			        (setq i end)
			        (let ((answer (rontolisp::%inflate-update st piece limit)))
			          (loop
			            (if (stringp answer) (progn (setq failed answer) (return nil)))
			            (setq out (cons answer out))
			            (if (or (= (length answer) 0) (null limit)) (return nil))
			            (setq answer (rontolisp::%inflate-update
			                          st (make-array 0 :element-type '(unsigned-byte 8)) limit)))))
			      (if (or failed (>= i n)) (return nil)))
			    (if failed
			        (list :zip failed)
			        (let ((short (rontolisp::%inflate-finish st)))
			          (if short
			              (list :eof short)
			              (let* ((total (reduce #'+ (mapcar #'length out)))
			                     (joined (make-array total :element-type '(unsigned-byte 8)))
			                     (k 0))
			                (dolist (part (reverse out))
			                  (replace joined part :start1 k)
			                  (setq k (+ k (length part))))
			                (list :ok joined)))))))
			""";

	@Test
	void theLispDecoderIsTheJavaDecoderWhereverTheChunksAreCut() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		// The library's definitions override the interpreter's natives in this evaluator,
		// so what runs below is the Lisp decoder the wasm targets compile.
		for (LispVal form : InflateLibrary.forms()) {
			evaluator.eval(form);
		}
		evaluator.eval(LispReader.readFromString(DRIVER));
		// The interpreter is slow at a decoder: the small cases, each kind and shape
		List<Case> cases = InflateCases.generate(7, 12).stream().filter(c -> c.input().length <= 400).toList();
		assertThat(cases).hasSizeGreaterThan(150);
		int[][] feeds = { { 1, -1 }, { 5, 1 }, { Integer.MAX_VALUE, -1 } };
		for (Case c : cases) {
			for (int[] feed : feeds) {
				Outcome java = javaOutcome(c, feed[0], feed[1]);
				Outcome lisp = lispOutcome(evaluator, c, feed[0], feed[1]);
				assertThat(lisp).as("%s in chunks of %d, %d at a time", c, feed[0], feed[1]).isEqualTo(java);
			}
		}
	}

	private static Outcome javaOutcome(Case c, int chunk, int limit) {
		RontoInflate decoder = new RontoInflate(c.kind());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] input = c.input();
		int i = 0;
		do {
			int n = Math.min(chunk, input.length - i);
			Object answer = decoder.update(input, i, n, limit);
			i += n;
			while (true) {
				if (answer instanceof String message) {
					return Outcome.zip(message);
				}
				byte[] octets = (byte[]) answer;
				out.writeBytes(octets);
				if (octets.length == 0 || limit < 0) {
					break;
				}
				answer = decoder.update(new byte[0], 0, 0, limit);
			}
		}
		while (i < input.length);
		int short_ = decoder.finish();
		return (short_ == 0) ? Outcome.ok(out.toByteArray()) : Outcome.eof(Integer.toString(short_));
	}

	private static Outcome lispOutcome(LispEvaluator evaluator, Case c, int chunk, int limit) {
		StringJoiner octets = new StringJoiner(" ");
		for (byte b : c.input()) {
			octets.add(Integer.toString(b & 0xff));
		}
		String call = "(inflate-case " + c.kind() + " (make-array " + c.input().length
				+ " :element-type '(unsigned-byte 8) :initial-contents '(" + octets + ")) "
				+ Math.min(chunk, Math.max(1, c.input().length)) + " " + (limit < 0 ? "nil" : Integer.toString(limit))
				+ ")";
		LispVal answer = evaluator.eval(LispReader.readFromString(call));
		LispCons pair = (LispCons) answer;
		String tag = ((LispSymbol) pair.car()).name();
		LispVal value = ((LispCons) pair.cdr()).car();
		return switch (tag) {
			case ":OK" -> Outcome.ok(((LispIntVector) value).octets().clone());
			case ":ZIP" -> Outcome.zip(((LispString) value).value());
			default -> Outcome.eof(value.print());
		};
	}

	@Test
	void theDecoderIsSplicedIntoAWasmProgramThatNamesItAndNoOtherProgram() {
		List<LispVal> naming = LispReader.readAllFromString(
				"(defun f (x) (rontolisp::%inflate-update (rontolisp::%inflate-new 2) x nil))", Features.WASM);
		List<LispVal> spliced = InflateLibrary.process(naming, WitExportDirective.Backend.WASM_GC);
		assertThat(spliced).hasSize(InflateLibrary.forms().size() + naming.size());
		assertThat(InflateLibrary.process(naming, WitExportDirective.Backend.WASM_COMPONENT)).hasSize(spliced.size());
		// the interpreter and the JVM have the decoder in Java
		assertThat(InflateLibrary.process(naming, WitExportDirective.Backend.OTHER)).isSameAs(naming);
		List<LispVal> plain = LispReader.readAllFromString("(defun f (x) (print x))", Features.WASM);
		assertThat(InflateLibrary.process(plain, WitExportDirective.Backend.WASM_GC)).isSameAs(plain);
		// spliced once
		assertThat(InflateLibrary.process(spliced, WitExportDirective.Backend.WASM_GC)).isSameAs(spliced);
	}

	@Test
	void theInterpretersDecoderIsTheJavaOne() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		byte[] gzip = InflateCases.gzip("héllo".getBytes(StandardCharsets.UTF_8));
		StringJoiner octets = new StringJoiner(" ");
		for (byte b : gzip) {
			octets.add(Integer.toString(b & 0xff));
		}
		LispVal decoder = evaluator.eval(LispReader.readFromString("(defparameter *d* (rontolisp::%inflate-new 2))"));
		assertThat(decoder).isNotNull();
		LispVal out = evaluator.eval(LispReader.readFromString("(rontolisp::%inflate-update *d* (make-array "
				+ gzip.length + " :element-type '(unsigned-byte 8) :initial-contents '(" + octets + ")) nil)"));
		assertThat(new String(((LispIntVector) out).octets(), StandardCharsets.UTF_8)).isEqualTo("héllo");
		assertThat(evaluator.eval(LispReader.readFromString("(rontolisp::%inflate-finish *d*)")))
			.isEqualTo(LispNil.INSTANCE);
		assertThat(evaluator.eval(LispReader.readFromString(
				"(rontolisp::%inflate-update (rontolisp::%inflate-new 0) (make-array 1 :element-type '(unsigned-byte 8) :initial-element 7) nil)")))
			.isEqualTo(new LispString("invalid block type"));
	}

}
