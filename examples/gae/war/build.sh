#!/usr/bin/env bash
# Compile the same examples/net/httpbin-clack.lisp as ../jar, for the Servlet
# transport, and lay it out the way App Engine wants it.
#
# -o app.war: the Servlet 6 war -- a program class implementing
#   RontoHttpServer.Handler, the two-class servlet transport, and the one-line
#   ServletContainerInitializer service declaration. It deploys unmodified into
#   Tomcat or Jetty; App Engine is the host that needs it EXPLODED.
#
# App Engine's Java buildpack does not accept a .war FILE. It takes a directory
# that looks like an unpacked one (WEB-INF/ at the root, appengine-web.xml
# inside it), so the war is unpacked in place and the archive itself removed --
# leaving it beside app.yaml would only be uploaded and ignored.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

# A previous build's classes would ship alongside this one's.
rm -rf "$here/WEB-INF/classes" "$here/META-INF" "$here/app.war"

echo "compiling ../../net/httpbin-clack.lisp -> app.war"
java -jar "$jar" "$repo_root/examples/net/httpbin-clack.lisp" -o "$here/app.war"

echo "unpacking app.war in place"
(cd "$here" && unzip -q -o app.war && rm app.war)

find "$here/WEB-INF" -name '*.class' | wc -l | xargs echo "class files:"
echo "done. Deploy it with:  gcloud app deploy $here/app.yaml --project YOUR_PROJECT"
