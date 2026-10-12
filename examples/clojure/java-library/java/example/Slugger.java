package example;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A small Java library the Clojure program calls: a static method, a constructor, an
 * instance method taking a Java collection, and one taking a functional interface.
 */
public final class Slugger {

	public static final String DEFAULT_SEPARATOR = "-";

	private final String separator;

	public Slugger(String separator) {
		this.separator = separator;
	}

	/** {@code "Crème Brûlée!"} -> {@code "creme-brulee"} with the default separator. */
	public static String slug(String text) {
		return new Slugger(DEFAULT_SEPARATOR).apply(text);
	}

	public String apply(String text) {
		String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		String[] words = plain.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
		List<String> kept = new ArrayList<>();
		for (String word : words) {
			if (!word.isEmpty()) {
				kept.add(word);
			}
		}
		return String.join(this.separator, kept);
	}

	public List<String> applyAll(List<String> texts, Predicate<String> keep) {
		List<String> slugs = new ArrayList<>();
		for (String text : texts) {
			String slug = apply(text);
			if (keep.test(slug)) {
				slugs.add(slug);
			}
		}
		return slugs;
	}

}
