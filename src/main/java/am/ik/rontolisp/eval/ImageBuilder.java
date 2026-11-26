package am.ik.rontolisp.eval;

import java.util.List;

/**
 * What compiles an image the interpreter dumps ({@code uiop:dump-image}, which
 * {@code asdf:make} of a {@code program-op} system reaches). The compilers live in
 * {@code cli}, which {@code eval} may not import, so the command line hands each
 * evaluator it creates a builder ({@link LispEvaluator#setImageBuilder}) before the
 * program runs; an evaluator without one refuses to dump.
 */
public interface ImageBuilder {

	/**
	 * Compiles the program an image dumps into the artifact its file names.
	 * @param image the program and where it goes
	 */
	void build(Image image);

	/**
	 * A dumped image: the program that loads what the image loaded and then restores it,
	 * and what that program needs to be read.
	 *
	 * @param source the program's Common Lisp source
	 * @param output the artifact's path as the image named it: no type is a native
	 * executable, {@code .jar} or {@code .class} a JVM program, {@code .wasm} a module
	 * @param systemPath the directories the program's systems are found in, the
	 * {@code .asd} directories of the image's systems first
	 * @param declaredFeatures the {@code --feature} names the image read its sources with
	 */
	record Image(String source, String output, List<String> systemPath, List<String> declaredFeatures) {

		/**
		 * The image.
		 * @param source the program's source
		 * @param output the artifact's path
		 * @param systemPath the directories the systems are found in
		 * @param declaredFeatures the declared features
		 */
		public Image {
			systemPath = List.copyOf(systemPath);
			declaredFeatures = List.copyOf(declaredFeatures);
		}

	}

}
