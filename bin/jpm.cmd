@echo off
rem Launcher for the jar build of jpm on Windows. The native binary (dist\jpm.exe) needs no launcher.
setlocal
set "HERE=%~dp0.."
set "JAR="
for /f "delims=" %%f in ('dir /b /o-d "%HERE%\dist\jpm-*.jar" 2^>nul') do if not defined JAR set "JAR=%HERE%\dist\%%f"
if not defined JAR set "JAR=%HERE%\.cache\bootstrap\jpm.jar"
if not exist "%JAR%" (
  echo jpm is not built yet; run bootstrap.sh 1>&2
  exit /b 1
)
rem scripts, hooks and extensions call back into jpm through JPM_EXE
set "JPM_EXE=%HERE%\bin\jpm.cmd"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
"%JAVA%" -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -jar "%JAR%" %*
