#!/bin/sh
# 复用脚本：绕过沙箱下 mvn 脚本 glob 不展开的问题，直接 java 拉起 classworlds 引导类。
# 仅用于本机编译，不影响交付（交付用你自己的 Maven 或 Docker）。
JH="D:/Code/java/Spring_agent/.tooling/maven/apache-maven-3.9.9"
CWJAR="$JH/boot/plexus-classworlds-2.8.0.jar"
exec java -classpath "$CWJAR" \
  "-Dclassworlds.conf=$JH/bin/m2.conf" \
  "-Dmaven.home=$JH" \
  "-Dmaven.multiModuleProjectDirectory=D:/Code/java/Spring_agent" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
