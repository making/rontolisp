package am.ik.jdbc.postgresql;

import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;

/**
 * Rows the driver makes itself, kept as the server's would be: each the body of a DataRow
 * message under columns of the server's types, so a result set reads them as it reads any
 * other.
 */
final class Rows {

	private final Result result;

	private final int width;

	/**
	 * @param names the columns' labels
	 * @param types each column's type, an object identifier of {@link TypeId}
	 */
	Rows(String[] names, int[] types) {
		Column[] columns = new Column[names.length];
		for (int i = 0; i < names.length; i++) {
			columns[i] = new Column(names[i], 0, 0, types[i], -1, -1);
		}
		this.result = new Result(columns);
		this.width = names.length;
	}

	/** A row: each value's text, {@code null} for NULL. */
	Rows add(@Nullable Object... values) {
		byte[] @Nullable [] texts = new byte[this.width][];
		int size = 2;
		for (int i = 0; i < this.width; i++) {
			Object value = values[i];
			texts[i] = value == null ? null : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
			size += 4 + (texts[i] == null ? 0 : texts[i].length);
		}
		byte[] row = new byte[size];
		row[0] = (byte) (this.width >> 8);
		row[1] = (byte) this.width;
		int at = 2;
		for (byte[] text : texts) {
			int length = text == null ? -1 : text.length;
			row[at] = (byte) (length >> 24);
			row[at + 1] = (byte) (length >> 16);
			row[at + 2] = (byte) (length >> 8);
			row[at + 3] = (byte) length;
			at += 4;
			if (text != null) {
				System.arraycopy(text, 0, row, at, text.length);
				at += text.length;
			}
		}
		this.result.rows.add(row);
		return this;
	}

	Result result() {
		return this.result;
	}

}
