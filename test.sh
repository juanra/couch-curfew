#!/usr/bin/env bash
# Runs the rules that don't need a TV (daily budget, PIN) on the computer's JDK.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out/test && mkdir -p out/test
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/test \
  app/src/cc/nunez/couchcurfew/Budget.java app/src/cc/nunez/couchcurfew/Pin.java test/cc/nunez/couchcurfew/RulesTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/test cc.nunez.couchcurfew.RulesTest
