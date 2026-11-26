package am.ik.jdbc.postgresql;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What one statement answered: rows under their columns, or a count.
 */
final class Result {

	/** The columns of the rows, or {@code null} for a statement that answers none. */
	final Column @Nullable [] columns;

	/**
	 * The rows, each the body of its DataRow message: a count of columns, then for each a
	 * length (-1 for NULL) and its octets.
	 */
	final List<byte[]> rows = new ArrayList<>();

	/** The command tag, as {@code INSERT 0 2}; {@code null} for an empty query. */
	@Nullable String tag;

	Result(Column @Nullable [] columns) {
		this.columns = columns;
	}

	/**
	 * The rows a command changed or took, from the number that ends its tag; 0 for a
	 * command whose tag ends with none.
	 */
	long count() {
		if (this.tag == null) {
			return 0;
		}
		int space = this.tag.lastIndexOf(' ');
		if (space < 0) {
			return 0;
		}
		long count = 0;
		for (int i = space + 1; i < this.tag.length(); i++) {
			int digit = this.tag.charAt(i) - '0';
			if (digit < 0 || digit > 9) {
				return 0;
			}
			count = count * 10 + digit;
		}
		return count;
	}

}
