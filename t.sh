#!/bin/bash
cd /home/claude/tether
rm -rf build/jvm
./kc.sh build/jvm "" src/com/pranvir/tether/{Core,World,Synth,Fx,Game}.kt jvmtest/shim/Graphics.kt jvmtest/*.kt 2>&1 | grep -E "error" | head -30
for m in "$@"; do java -Djava.awt.headless=true -cp build/jvm:/home/claude/tc/kotlin-stdlib-2.3.10-RC.jar $m 2>&1 | grep -v JAVA_TOOL | tail -40; done
