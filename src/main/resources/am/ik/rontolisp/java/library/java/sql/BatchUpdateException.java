// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

// The counts of the statements of a batch that ran: kept as a copy, answered as a copy,
// as ints and as longs (a long count past an int's range is its low 32 bits as an int).
public class BatchUpdateException extends SQLException {

    private int[] updateCounts;

    private long[] longUpdateCounts;

    public BatchUpdateException(String reason, String SQLState, int vendorCode, int[] updateCounts) {
        super(reason, SQLState, vendorCode);
        counts(updateCounts);
    }

    public BatchUpdateException(String reason, String SQLState, int[] updateCounts) {
        this(reason, SQLState, 0, updateCounts);
    }

    public BatchUpdateException(String reason, int[] updateCounts) {
        this(reason, null, 0, updateCounts);
    }

    public BatchUpdateException(int[] updateCounts) {
        this(null, null, 0, updateCounts);
    }

    public BatchUpdateException() {
        this(null, null, 0, (int[]) null);
    }

    public BatchUpdateException(Throwable cause) {
        this(cause == null ? null : cause.toString(), null, 0, (int[]) null, cause);
    }

    public BatchUpdateException(int[] updateCounts, Throwable cause) {
        this(cause == null ? null : cause.toString(), null, 0, updateCounts, cause);
    }

    public BatchUpdateException(String reason, int[] updateCounts, Throwable cause) {
        this(reason, null, 0, updateCounts, cause);
    }

    public BatchUpdateException(String reason, String SQLState, int[] updateCounts, Throwable cause) {
        this(reason, SQLState, 0, updateCounts, cause);
    }

    public BatchUpdateException(String reason, String SQLState, int vendorCode, int[] updateCounts, Throwable cause) {
        super(reason, SQLState, vendorCode, cause);
        counts(updateCounts);
    }

    public BatchUpdateException(String reason, String SQLState, int vendorCode, long[] updateCounts, Throwable cause) {
        super(reason, SQLState, vendorCode, cause);
        if (updateCounts != null) {
            this.longUpdateCounts = updateCounts.clone();
            this.updateCounts = new int[updateCounts.length];
            for (int i = 0; i < updateCounts.length; i++) {
                this.updateCounts[i] = (int) updateCounts[i];
            }
        }
    }

    private void counts(int[] updateCounts) {
        if (updateCounts != null) {
            this.updateCounts = updateCounts.clone();
            this.longUpdateCounts = new long[updateCounts.length];
            for (int i = 0; i < updateCounts.length; i++) {
                this.longUpdateCounts[i] = updateCounts[i];
            }
        }
    }

    public int[] getUpdateCounts() {
        return updateCounts == null ? null : updateCounts.clone();
    }

    public long[] getLargeUpdateCounts() {
        return longUpdateCounts == null ? null : longUpdateCounts.clone();
    }

}
