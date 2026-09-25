@echo off
rem Build the game server with JDK 23.
setlocal
set "JAVA_HOME=C:\openjdk-23.0.1"
call mvn -B -DskipTests package
endlocal
