// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

import java.util.Map;

// The client information properties that could not be set; the map is the one given. A
// cause given is set after the trace is written to DriverManager's log writer, so the
// trace does not show it, as a JDK 25's does not.
public class SQLClientInfoException extends SQLException {

    private Map<String, ClientInfoStatus> failedProperties;

    public SQLClientInfoException() {
    }

    public SQLClientInfoException(Map<String, ClientInfoStatus> failedProperties) {
        this.failedProperties = failedProperties;
    }

    public SQLClientInfoException(Map<String, ClientInfoStatus> failedProperties, Throwable cause) {
        super(cause == null ? null : cause.toString());
        initCause(cause);
        this.failedProperties = failedProperties;
    }

    public SQLClientInfoException(String reason, Map<String, ClientInfoStatus> failedProperties) {
        super(reason);
        this.failedProperties = failedProperties;
    }

    public SQLClientInfoException(String reason, Map<String, ClientInfoStatus> failedProperties, Throwable cause) {
        super(reason);
        initCause(cause);
        this.failedProperties = failedProperties;
    }

    public SQLClientInfoException(String reason, String SQLState, Map<String, ClientInfoStatus> failedProperties) {
        super(reason, SQLState);
        this.failedProperties = failedProperties;
    }

    public SQLClientInfoException(String reason, String SQLState, Map<String, ClientInfoStatus> failedProperties,
            Throwable cause) {
        super(reason, SQLState);
        initCause(cause);
        this.failedProperties = failedProperties;
    }

    public SQLClientInfoException(String reason, String SQLState, int vendorCode,
            Map<String, ClientInfoStatus> failedProperties) {
        super(reason, SQLState, vendorCode);
        this.failedProperties = failedProperties;
    }

    public SQLClientInfoException(String reason, String SQLState, int vendorCode,
            Map<String, ClientInfoStatus> failedProperties, Throwable cause) {
        super(reason, SQLState, vendorCode);
        initCause(cause);
        this.failedProperties = failedProperties;
    }

    public Map<String, ClientInfoStatus> getFailedProperties() {
        return failedProperties;
    }

}
