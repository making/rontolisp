#!/bin/bash
CURRENT_VERSION=$(./get-release-version.sh)

./mvnw versions:set -DnewVersion=${CURRENT_VERSION} -DgenerateBackupPoms=false
# rontolisp-maven-plugin is outside the reactor but embeds this rontolisp, so its
# version has to say which one.
./mvnw -f rontolisp-maven-plugin/pom.xml versions:set -DnewVersion=${CURRENT_VERSION} -DgenerateBackupPoms=false
git add pom.xml rontolisp-maven-plugin/pom.xml
git commit -m "Bump to ${CURRENT_VERSION}"