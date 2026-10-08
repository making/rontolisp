package am.ik.maven;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * Maven Resolver's version order ({@code GenericVersionScheme}, the scheme Maven's
 * session uses): a version is split at {@code .}, {@code -}, {@code _} and at every
 * digit/letter transition into numbers and qualifiers, trailing zero items are trimmed,
 * and the items compare pairwise -- numbers above qualifiers, the known qualifiers
 * ordered
 * {@code alpha < beta < milestone < rc = cr < snapshot < "" = ga = final = release
 * < sp}, any other qualifier above them all, case-insensitively.
 */
final class GenericVersion implements Comparable<GenericVersion> {

	private final List<Item> items;

	private GenericVersion(List<Item> items) {
		this.items = items;
	}

	/**
	 * Parses a version.
	 * @param version the text
	 * @return the version
	 */
	static GenericVersion parse(String version) {
		List<Item> items = new ArrayList<>();
		for (Tokenizer tokenizer = new Tokenizer(version); tokenizer.next();) {
			items.add(tokenizer.toItem());
		}
		trimPadding(items);
		return new GenericVersion(items);
	}

	private static void trimPadding(List<Item> items) {
		Boolean number = null;
		int end = items.size() - 1;
		for (int i = end; i > 0; i--) {
			Item item = items.get(i);
			if (!Boolean.valueOf(item.isNumber()).equals(number)) {
				end = i;
				number = item.isNumber();
			}
			if (end == i && (i == items.size() - 1 || items.get(i - 1).isNumber() == item.isNumber())
					&& item.compareTo(null) == 0) {
				items.remove(i);
				end--;
			}
		}
	}

	@Override
	public int compareTo(GenericVersion other) {
		List<Item> these = this.items;
		List<Item> those = other.items;
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

	private static int comparePadding(List<Item> items, int index, @Nullable Boolean number) {
		int rel = 0;
		for (int i = index; i < items.size(); i++) {
			Item item = items.get(i);
			if (number != null && number != item.isNumber()) {
				continue;
			}
			rel = item.compareTo(null);
			if (rel != 0) {
				break;
			}
		}
		return rel;
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof GenericVersion other && compareTo(other) == 0;
	}

	@Override
	public int hashCode() {
		return this.items.hashCode();
	}

	private static final class Tokenizer {

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
						// a leading zero is dropped
						start++;
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
				return this.token.length() < 10 ? new Item(Item.KIND_INT, Integer.parseInt(this.token))
						: new Item(Item.KIND_BIGINT, new BigInteger(this.token));
			}
			if (this.index >= this.version.length()) {
				if ("min".equalsIgnoreCase(this.token)) {
					return Item.MIN;
				}
				if ("max".equalsIgnoreCase(this.token)) {
					return Item.MAX;
				}
			}
			if (this.terminatedByNumber && this.token.length() == 1) {
				switch (this.token.charAt(0)) {
					case 'a', 'A' -> {
						return new Item(Item.KIND_QUALIFIER, QUALIFIER_ALPHA);
					}
					case 'b', 'B' -> {
						return new Item(Item.KIND_QUALIFIER, QUALIFIER_BETA);
					}
					case 'm', 'M' -> {
						return new Item(Item.KIND_QUALIFIER, QUALIFIER_MILESTONE);
					}
					default -> {
						// an ordinary one-letter qualifier
					}
				}
			}
			Integer qualifier = QUALIFIERS.get(this.token);
			return qualifier != null ? new Item(Item.KIND_QUALIFIER, qualifier)
					: new Item(Item.KIND_STRING, this.token.toLowerCase(Locale.ENGLISH));
		}

	}

	private record Item(int kind, Object value) {

		static final int KIND_MAX = 8;

		static final int KIND_BIGINT = 5;

		static final int KIND_INT = 4;

		static final int KIND_STRING = 3;

		static final int KIND_QUALIFIER = 2;

		static final int KIND_MIN = 0;

		static final Item MAX = new Item(KIND_MAX, "max");

		static final Item MIN = new Item(KIND_MIN, "min");

		boolean isNumber() {
			return (this.kind & KIND_QUALIFIER) == 0;
		}

		int compareTo(@Nullable Item that) {
			if (that == null) {
				return switch (this.kind) {
					case KIND_MIN -> -1;
					case KIND_MAX, KIND_BIGINT, KIND_STRING -> 1;
					default -> (Integer) this.value;
				};
			}
			int rel = this.kind - that.kind;
			if (rel != 0) {
				return rel;
			}
			return switch (this.kind) {
				case KIND_BIGINT -> ((BigInteger) this.value).compareTo((BigInteger) that.value);
				case KIND_INT, KIND_QUALIFIER -> ((Integer) this.value).compareTo((Integer) that.value);
				case KIND_STRING -> ((String) this.value).compareToIgnoreCase((String) that.value);
				default -> 0;
			};
		}

	}

}
