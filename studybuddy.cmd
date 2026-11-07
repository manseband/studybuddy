@echo off
rem StudyBuddy launcher for Windows.
rem   studybuddy              opens the GUI
rem   studybuddy course list  runs a CLI command (any command from "studybuddy --help")
rem Builds target\studybuddy.jar first if it does not exist yet.
rem Run it from the project folder: .env and data\ are looked up in the current folder.
setlocal
set "JAR=%~dp0target\studybuddy.jar"

where java >nul 2>nul
if errorlevel 1 (
    echo Java 25 or newer is required, and "java" must be on the PATH. 1>&2
    exit /b 1
)

if not exist "%JAR%" (
    echo Building StudyBuddy for the first time, this takes about a minute...
    call "%~dp0mvnw.cmd" -q -f "%~dp0pom.xml" -DskipTests package
    if errorlevel 1 exit /b 1
)

if "%~1"=="" (
    java -jar "%JAR%" gui
) else (
    java -jar "%JAR%" %*
)
exit /b %ERRORLEVEL%
