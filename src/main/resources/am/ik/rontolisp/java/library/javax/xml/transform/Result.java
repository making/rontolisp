// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.xml.transform;

public interface Result {

    String PI_DISABLE_OUTPUT_ESCAPING = "javax.xml.transform.disable-output-escaping";

    String PI_ENABLE_OUTPUT_ESCAPING = "javax.xml.transform.enable-output-escaping";

    void setSystemId(String systemId);

    String getSystemId();

}
