package am.ik.rontolisp.clojure;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * The order tools.deps picks the newer of two {@code :mvn/version} coordinates by: its
 * {@code compare-versions} parses both with maven-resolver's {@code GenericVersionScheme}
 * and compares them. This is that scheme's version order (maven-resolver-util 1.9.27, the
 * build the oracle's {@code clj} 1.12.6 runs), ported from its class files: a version is
 * split at {@code .}, {@code -}, {@code _} and every digit/letter transition; numbers
 * compare numerically (leading zeros dropped), the well-known qualifiers by rank
 * ({@code alpha} &lt; {@code beta} &lt; {@code milestone} &lt; {@code rc} = {@code cr}
 * &lt; {@code snapshot} &lt; release = {@code ga} = {@code final} &lt; {@code sp}), any
 * other word above every qualifier and below every number, case-insensitively, and a
 * shorter version is padded with the release value. {@code ClojureMavenVersionsTest} pins
 * it against the oracle's own comparisons.
 */
final class ClojureMavenVersions {

	private static final int KIND_MAX = 8;

	private static final int KIND_BIGINT = 5;

	private static final int KIND_INT = 4;

	private static final int KIND_STRING = 3;

	private static final int KIND_QUALIFIER = 2;

	private static final int KIND_MIN = 0;

	private static final int QUALIFIER_ALPHA = -5;

	private static final int QUALIFIER_BETA = -4;

	private static final int QUALIFIER_MILESTONE = -3;

	private static final Map<String, Integer> QUALIFIERS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	static {
		QUALIFIERS.put("alpha", QUALIFIER_ALPHA);
		QUALIFIERS.put("beta", QUALIFIER_BETA);
		QUALIFIERS.put("milestone", QUALIFIER_MILESTONE);
		QUALIFIERS.put("cr", -2);
		QUALIFIERS.put("rc", -2);
		QUALIFIERS.put("snapshot", -1);
		QUALIFIERS.put("ga", 0);
		QUALIFIERS.put("final", 0);
		QUALIFIERS.put("release", 0);
		QUALIFIERS.put("", 0);
		QUALIFIERS.put("sp", 1);
	}

	private ClojureMavenVersions() {
	}

	/**
	 * Compares two versions.
	 * @param a one version
	 * @param b the other
	 * @return negative, zero or positive as {@code a} is older than, the same as or newer
	 * than {@code b}
	 */
	static int compare(String a, String b) {
		List<Item> these = parse(a);
		List<Item> those = parse(b);
		boolean number = true;
		for (int index = 0;; index++) {
			if (index >= these.size() && index >= those.size()) {
				return 0;
			}
			if (index >= these.size()) {
				return -comparePadding(those, index, null);
			}
			if (index >= those.size()) {
				return comparePadding(these, index, null);
			}
			Item thisItem = these.get(index);
			Item thatItem = those.get(index);
			if (thisItem.isNumber() != thatItem.isNumber()) {
				return number == thisItem.isNumber() ? comparePadding(these, index, number)
						: -comparePadding(those, index, number);
			}
			int rel = thisItem.compareTo(thatItem);
			if (rel != 0) {
				return rel;
			}
			number = thisItem.isNumber();
		}
	}

	/** The rest of a version against the padding, skipping members of the other kind. */
	private static int comparePadding(List<Item> items, int index, @Nullable Boolean number) {
		int rel = 0;
		for (int i = index; i < items.size(); i++) {
			Item item = items.get(i);
			if (number != null && number != item.isNumber()) {
				continue;
			}
			rel = item.compareToPadding();
			if (rel != 0) {
				break;
			}
		}
		return rel;
	}

	private static List<Item> parse(String version) {
		List<Item> items = new ArrayList<>();
		Tokenizer tokenizer = new Tokenizer(version);
		while (tokenizer.next()) {
			items.add(tokenizer.toItem());
		}
		trimPadding(items);
		return items;
	}

	/** Drops the trailing members equal to the padding, run by run of one kind. */
	private static void trimPadding(List<Item> items) {
		@Nullable Boolean number = null;
		int end = items.size() - 1;
		for (int i = end; i > 0; i--) {
			Item item = items.get(i);
			if (!Boolean.valueOf(item.isNumber()).equals(number)) {
				end = i;
				number = item.isNumber();
			}
			if (end == i && (i == items.size() - 1 || items.get(i - 1).isNumber() == item.isNumber())
					&& item.compareToPadding() == 0) {
				items.remove(i);
				end--;
			}
		}
	}

	/** One member of a parsed version. */
	private record Item(int kind, Object value) {

		boolean isNumber() {
			return (this.kind & KIND_QUALIFIER) == 0;
		}

		/** Against the padding (0, or the release qualifier). */
		int compareToPadding() {
			return switch (this.kind) {
				case KIND_MIN -> -1;
				case KIND_INT, KIND_QUALIFIER -> (Integer) this.value;
				default -> 1; // a word, a big number, max
			};
		}

		int compareTo(Item that) {
			int rel = this.kind - that.kind;
			if (rel != 0) {
				return rel;
			}
			return switch (this.kind) {
				case KIND_BIGINT -> ((BigInteger) this.value).compareTo((BigInteger) that.value);
				case KIND_INT, KIND_QUALIFIER -> ((Integer) this.value).compareTo((Integer) that.value);
				case KIND_STRING -> ((String) this.value).compareToIgnoreCase((String) that.value);
				default -> 0; // min, max
			};
		}

	}

	/** Splits a version into its members, one {@link #next} at a time. */
	private static final class Tokenizer {

		private final String version;

		private int index;

		private String token = "";

		private boolean number;

		private boolean terminatedByNumber;

		Tokenizer(String version) {
			this.version = version.isEmpty() ? "0" : version;
		}

		boolean next() {
			int length = this.version.length();
			if (this.index >= length) {
				return false;
			}
			int state = -2;
			int start = this.index;
			int end = length;
			this.terminatedByNumber = false;
			for (; this.index < length; this.index++) {
				char c = this.version.charAt(this.index);
				if (c == '.' || c == '-' || c == '_') {
					end = this.index;
					this.index++;
					break;
				}
				int digit = Character.digit(c, 10);
				if (digit >= 0) {
					if (state == -1) {
						end = this.index;
						this.terminatedByNumber = true;
						break;
					}
					if (state == 0) {
						start++; // a leading zero
					}
					state = (state > 0 || digit > 0) ? 1 : 0;
				}
				else {
					if (state >= 0) {
						end = this.index;
						break;
					}
					state = -1;
				}
			}
			if (end - start > 0) {
				this.token = this.version.substring(start, end);
				this.number = state >= 0;
			}
			else {
				this.token = "0";
				this.number = true;
			}
			return true;
		}

		Item toItem() {
			if (this.number) {
				return this.token.length() < 10 ? new Item(KIND_INT, Integer.parseInt(this.token))
						: new Item(KIND_BIGINT, new BigInteger(this.token));
			}
			if (this.index >= this.version.length()) {
				if (this.token.equalsIgnoreCase("min")) {
					return new Item(KIND_MIN, "min");
				}
				if (this.token.equalsIgnoreCase("max")) {
					return new Item(KIND_MAX, "max");
				}
			}
			if (this.terminatedByNumber && this.token.length() == 1) {
				switch (this.token.charAt(0)) {
					case 'a', 'A' -> {
						return new Item(KIND_QUALIFIER, QUALIFIER_ALPHA);
					}
					case 'b', 'B' -> {
						return new Item(KIND_QUALIFIER, QUALIFIER_BETA);
					}
					case 'm', 'M' -> {
						return new Item(KIND_QUALIFIER, QUALIFIER_MILESTONE);
					}
					default -> {
						// any other one-letter word is a word
					}
				}
			}
			Integer qualifier = QUALIFIERS.get(this.token);
			if (qualifier != null) {
				return new Item(KIND_QUALIFIER, qualifier);
			}
			return new Item(KIND_STRING, this.token.toLowerCase(Locale.ENGLISH));
		}

	}

}
