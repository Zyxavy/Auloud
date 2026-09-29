@rem Gradle startup script for Windows (minimal WP1 bootstrap).
@echo off
set APP_HOME=%~dp0
set DEFAULT_JVM_OPTS=-Xmx2g
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" %DEFAULT_JVM_OPTS% -jar "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" %*
) else (
  java %DEFAULT_JVM_OPTS% -jar "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" %*
)
