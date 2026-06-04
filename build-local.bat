@echo off
setlocal EnableDelayedExpansion

:: Always run from the repo root (this script's dir) so gradlew.bat resolves
:: regardless of where the script was launched from.
cd /d "%~dp0"

echo === Kobol local build ===

:: Check gradle wrapper jar exists
if not exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
    echo ERROR: gradle\wrapper\gradle-wrapper.jar missing.
    echo Run: docker run --rm -v "%CD%:/project" -w /project gradle:8.14-jdk21 gradle wrapper --gradle-version 9.5.1 --distribution-type bin
    exit /b 1
)

:: Auto-detect JDK 21 in common Windows locations if JAVA_HOME not set
if "%JAVA_HOME%"=="" (
    for %%J in (
        "C:\Program Files\Java\jdk-21"
        "C:\Program Files\Eclipse Adoptium\jdk-21.0.0+35"
        "C:\Program Files\Microsoft\jdk-21.0.0.0"
        "C:\Program Files\GraalVM\graalvm-community-openjdk-21"
    ) do (
        if exist "%%~J\bin\java.exe" (
            set "JAVA_HOME=%%~J"
            echo Detected JAVA_HOME=!JAVA_HOME!
            goto :java_found
        )
    )
    echo NOTE: JAVA_HOME not set and JDK 21 not found in common locations.
    echo Gradle will attempt auto-download via Foojay ^(requires internet^).
)
:java_found

:: Build + install the kobol CLI (installLocal compiles, builds the fat jar, copies it
:: to %USERPROFILE%\.kobol and refreshes the User PATH). Using installLocal as the build
:: step means the `kobol` command on PATH is NEVER stale after a successful build.
echo.
echo [1/2] Building + installing kobol CLI...
call "%~dp0gradlew.bat" :compiler:installLocal --no-daemon
if !ERRORLEVEL! neq 0 (
    echo BUILD FAILED
    exit /b !ERRORLEVEL!
)

:: Run tests. cleanTest + --no-build-cache forces the test task to actually execute
:: (cleanTest alone is not enough: Gradle's build cache would restore it FROM-CACHE).
:: Compilation stays incremental, so this is much cheaper than --rerun-tasks.
echo.
echo [2/2] Running tests...
call "%~dp0gradlew.bat" cleanTest test --no-daemon --no-build-cache
set TEST_RESULT=!ERRORLEVEL!

if !TEST_RESULT! neq 0 (
    echo.
    echo TESTS FAILED. Reports:
    for /d /r %%D in (build\reports\tests\test) do (
        if exist "%%D\index.html" echo   %%D\index.html
    )
    exit /b !TEST_RESULT!
)

:: Make `kobol` usable in THIS terminal immediately (installLocal already patched the
:: persistent User PATH for new terminals).
set "KOBOL_BIN=%USERPROFILE%\.kobol\bin"
echo %PATH% | find /I "%KOBOL_BIN%" >nul
if errorlevel 1 set "PATH=%PATH%;%KOBOL_BIN%"

echo.
echo All tests passed.
echo kobol installed to %USERPROFILE%\.kobol  (added to PATH)
echo   This terminal: ready now.   New terminals: ready automatically.
echo   Try:  kobol --help
exit /b 0
