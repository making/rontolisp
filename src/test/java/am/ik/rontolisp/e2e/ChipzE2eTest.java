package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL chipz 0.8 sources
 * (vendored unmodified under {@code src/test/resources/chipz}, BSD) load via
 * {@code asdf:load-system} and inflate gzip and zlib streams on ALL FOUR backends via
 * {@link AsdfLibraryE2eSupport}. Nothing else in the loadable set decompresses, and
 * {@code size-report/programs/zlib} is built on it.
 *
 * <p>
 * What the library exercises: a {@code macrolet}-driven {@code labels} state machine
 * whose transitions store {@code #'local-function} in a struct slot and {@code funcall}
 * it back, {@code catch}/{@code throw} out of that machine (so every compiled artifact is
 * in EH mode), {@code (unsigned-byte 32)} bit buffers with variable-width
 * {@code ldb}/{@code ash}, {@code (unsigned-byte 16)} Huffman tables, {@code fill} with
 * {@code :start}/{@code :end}, and an eleven-argument {@code funcall} through a function
 * value -- the shape that outgrew the wasm per-arity dispatchers and now routes through
 * {@code apply} ({@code WasmArityBundler.spreadOverArityFuncalls}).
 *
 * <p>
 * The CRC32 case is the one mito depends on: its advisory-lock id is chipz's crc32 of the
 * database name, and {@code 285543882} for {@code "mydb"} is the value SBCL 2.2.9 answers
 * ({@code .kb/mito.md}).
 */
class ChipzE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "chipz").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system :chipz)
			(defparameter *gz*
			  (make-array 165 :element-type '(unsigned-byte 8) :initial-contents
			              '(31 139 8 0 0 0 0 0 2 255 237 143 81 18 195 32 8 68 175 194 213 168 33 213 6 131 99 109
			          167 199 111 138 209 30 34 251 195 40 44 236 190 156 54 33 9 209 104 181 79 171 214 104
			          75 106 84 184 48 237 246 150 124 147 250 127 248 204 203 80 231 223 190 166 204 100 207
			          192 181 47 46 162 141 137 181 68 166 199 75 147 180 83 56 15 245 153 139 135 192 63 189
			          63 101 30 44 207 136 179 31 34 215 99 171 27 223 77 87 74 251 146 134 177 7 28 18 63 235
			          165 7 140 214 68 79 249 97 157 193 15 126 240 131 31 252 224 7 63 248 193 15 126 240 131
			          31 252 224 191 0 255 23 85 45 83 213 0 16 0 0)))
			(defparameter *zl*
			  (make-array 153 :element-type '(unsigned-byte 8) :initial-contents
			              '(120 218 237 143 81 18 195 32 8 68 175 194 213 168 33 213 6 131 99 109 167 199 111 138
			          209 30 34 251 195 40 44 236 190 156 54 33 9 209 104 181 79 171 214 104 75 106 84 184 48
			          237 246 150 124 147 250 127 248 204 203 80 231 223 190 166 204 100 207 192 181 47 46 162
			          141 137 181 68 166 199 75 147 180 83 56 15 245 153 139 135 192 63 189 63 101 30 44 207
			          136 179 31 34 215 99 171 27 223 77 87 74 251 146 134 177 7 28 18 63 235 165 7 140 214 68
			          79 249 97 157 193 15 126 240 131 31 252 224 7 63 248 193 15 126 240 131 31 252 224 191 0
			          255 23 146 99 232 172)))
			(defun fnv1a (octets)
			  (let ((hash 2166136261))
			    (dotimes (i (length octets) hash)
			      (setq hash (logand #xFFFFFFFF (* (logxor hash (aref octets i)) 16777619))))))
			(defun head16 (octets)
			  (let ((out nil))
			    (dotimes (i 16 (nreverse out)) (push (aref octets i) out))))
			(let ((raw (chipz:decompress nil 'chipz:gzip *gz*)))
			  (print (list (length raw) (fnv1a raw)))
			  (print (head16 raw)))
			(let ((raw (chipz:decompress nil 'chipz:zlib *zl*)))
			  (print (list (length raw) (fnv1a raw))))
			(let ((state (chipz:make-dstate 'chipz:gzip))
			      (out (make-array 8192 :element-type '(unsigned-byte 8))))
			  (multiple-value-bind (consumed produced) (chipz:decompress out state *gz*)
			    (print (list consumed produced))))
			(let ((sum (chipz::make-crc32))
			      (name (make-array 4 :element-type '(unsigned-byte 8) :initial-contents '(109 121 100 98))))
			  (chipz::update-crc32 sum name 0 4)
			  (print (chipz::produce-crc32 sum)))
			""";

	private static final List<String> EXPECTED = List.of("(4096 2189302853)",
			"(109 105 107 101 32 101 99 104 111 32 102 111 120 116 114 111)", "(4096 2189302853)", "(165 4096)",
			"285543882");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "ChipzE2e";
	}

}
