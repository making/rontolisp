# The `.todo/529` spike, reproduced

What was run on 2026-08-26 to establish that a `rontolisp:http-handler` program
already has everything a Servlet transport needs, and that the war needs no
`web.xml` and no configuration of any kind. Nothing here is production shape --
the two adapter classes are what the spike compiled, and `Launch.java` /
`JettyLaunch.java` are throwaway embedded-container harnesses.

Versions: Tomcat 11.0.24 (`tomcat-embed-core`, Servlet 6.1), Jetty 12.0.31
EE10, `jakarta.servlet-api` 6.0.0 for the adapter's own compile, JDK 25.

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
M2=~/.m2/repository
SERVLET=$M2/jakarta/servlet/jakarta.servlet-api/6.0.0/jakarta.servlet-api-6.0.0.jar
TOMCAT=$M2/org/apache/tomcat/embed/tomcat-embed-core/11.0.24/tomcat-embed-core-11.0.24.jar:\
$M2/org/apache/tomcat/embed/tomcat-embed-el/11.0.24/tomcat-embed-el-11.0.24.jar:\
$M2/org/apache/tomcat/tomcat-annotations-api/11.0.18/tomcat-annotations-api-11.0.18.jar

# 1. The program class. Nothing about this compile knows what a servlet is.
java -jar $JAR spike-handler.lisp -o out/App.class --class-name App

# 2. The adapter and the initializer, against the servlet API and the emitted
#    runtime classes only.
javac -cp "$SERVLET:out" -d sci-classes RontoHttpServlet.java RontoHttpServletInitializer.java

# 3. An ordinary war -- with NO web.xml. The only non-class file is the one-line
#    service declaration that names the initializer.
mkdir -p war/WEB-INF/classes/META-INF/services
cp -r out/* sci-classes/* war/WEB-INF/classes/
echo 'am.ik.rontolisp.runtime.RontoHttpServletInitializer' \
  > war/WEB-INF/classes/META-INF/services/jakarta.servlet.ServletContainerInitializer
(cd war && jar --create --file ../app.war .)

# 4. Deploy it, on either container. Second arg is a context path.
javac -cp "$TOMCAT" -d . Launch.java && mkdir -p tomcat-base/webapps
java -cp "$TOMCAT:." Launch app.war                       # Tomcat, :18080

# Jetty needs its classpath resolved first (see .todo/529 for the two coordinates).
javac -cp "$JETTY_CP" -d . JettyLaunch.java
java -cp "$JETTY_CP:." JettyLaunch app.war                # Jetty, :18081
```

## Two things the spike sources stand in for

**`spike-handler.lisp` carries a pointless `rontolisp:jvm-export`.** That is not
decoration: a `jvm-export` is the only thing that makes today's
`JvmLispCompiler` set `topLevelInClinit`, and the initializer reaches the top
level through `Class.forName(name, true, loader)`. Compile the same file with
the export removed and the war deploys, the initializer finds the class, and
every request 500s with `NullPointerException: Cannot load from object array` --
the handler slot was never filled because the top level lives in `main` and
nothing called it. `.todo/530` makes war mode set that flag directly.

**The handler is registered through `rontolisp::%http-server-start` on an
ephemeral port, then immediately stopped**, because today's
`rontolisp:http-handler` directive compiles to the blocking
`RontoHttpServer.serve`. That register-and-return is the arm `.todo/530` makes
the directive emit in war mode; the spike just reaches it by the one route the
current compiler offers.
