# The `.todo/529` spike, reproduced

What was run on 2026-08-26 to establish that a `rontolisp:http-handler` program
already has everything a Servlet transport needs. Nothing here is production
shape -- `RontoHttpServlet.java` is the seventy lines the spike compiled, and
`Launch.java` is a throwaway embedded-Tomcat harness.

Versions: Tomcat 11.0.24 (`tomcat-embed-core`, Servlet 6.1),
`jakarta.servlet-api` 6.0.0 for the adapter's own compile, JDK 25.

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
M2=~/.m2/repository
SERVLET=$M2/jakarta/servlet/jakarta.servlet-api/6.0.0/jakarta.servlet-api-6.0.0.jar
TOMCAT=$M2/org/apache/tomcat/embed/tomcat-embed-core/11.0.24/tomcat-embed-core-11.0.24.jar:\
$M2/org/apache/tomcat/embed/tomcat-embed-el/11.0.24/tomcat-embed-el-11.0.24.jar:\
$M2/org/apache/tomcat/tomcat-annotations-api/11.0.18/tomcat-annotations-api-11.0.18.jar

# 1. The program class. Nothing about this compile knows what a servlet is.
java -jar $JAR spike-handler.lisp -o out/App.class --class-name App

# 2. The adapter, against the servlet API and the emitted runtime classes only.
javac -cp "$SERVLET:out" -d servlet-classes RontoHttpServlet.java

# 3. An ordinary war.
mkdir -p war/WEB-INF/classes && cp -r out/* servlet-classes/* war/WEB-INF/classes/
cp web.xml war/WEB-INF/web.xml
(cd war && jar --create --file ../app.war .)

# 4. Deploy it.
javac -cp "$TOMCAT" -d . Launch.java
mkdir -p tomcat-base/webapps
java -cp "$TOMCAT:." Launch app.war          # add a second arg for a context path
```

`spike-handler.lisp` registers through `rontolisp::%http-server-start` on an
ephemeral port and immediately stops it, because today's `rontolisp:http-handler`
directive calls the blocking `RontoHttpServer.serve`. That register-and-return is
exactly the arm `.todo/530` makes the directive emit in war mode; the spike just
reaches it by the one route the current compiler offers.
