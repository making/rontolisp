// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

// A value cut short: SQLState 01004 when it was read, 22001 when it was written.
public class DataTruncation extends SQLWarning {

    private int index;

    private boolean parameter;

    private boolean read;

    private int dataSize;

    private int transferSize;

    public DataTruncation(int index, boolean parameter, boolean read, int dataSize, int transferSize) {
        super("Data truncation", read ? "01004" : "22001");
        this.index = index;
        this.parameter = parameter;
        this.read = read;
        this.dataSize = dataSize;
        this.transferSize = transferSize;
    }

    public DataTruncation(int index, boolean parameter, boolean read, int dataSize, int transferSize, Throwable cause) {
        super("Data truncation", read ? "01004" : "22001", cause);
        this.index = index;
        this.parameter = parameter;
        this.read = read;
        this.dataSize = dataSize;
        this.transferSize = transferSize;
    }

    public int getIndex() {
        return index;
    }

    public boolean getParameter() {
        return parameter;
    }

    public boolean getRead() {
        return read;
    }

    public int getDataSize() {
        return dataSize;
    }

    public int getTransferSize() {
        return transferSize;
    }

}
