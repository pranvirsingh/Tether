#!/bin/bash
# usage: kc.sh <outdir> <classpath-extra> files...
TC=/home/claude/tc
OUT=$1; shift; CPX=$1; shift
java -Xmx3g -cp "$TC/kotlin-jupyter-kernel-0.19.0-944-all.jar:$TC/kotlin-stdlib-2.3.10-RC.jar:$TC/kotlin-reflect-2.3.10-RC.jar:$TC/kotlin-script-runtime-2.4.0-dev-6891.jar:$TC/annotations-13.0.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib -jvm-target 1.8 -cp "$TC/kotlin-stdlib-2.3.10-RC.jar:$CPX" -d "$OUT" "$@" 2>&1 | { grep -v JAVA_TOOL_OPTIONS || true; }
