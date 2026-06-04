@echo off
setlocal EnableDelayedExpansion

:: Always run from the repo root (this script's dir) so gradlew.bat resolves
:: regardless of where the script was launched from.
cd /d "%~dp0"

echo === Kobol test suite ===

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

echo.
echo Running tests...
:: cleanTest + --no-build-cache forces the test task to actually execute every run
:: (cleanTest alone is not enough: the build cache would restore it FROM-CACHE).
:: Compilation stays incremental, so this is much cheaper than --rerun-tasks.
call "%~dp0gradlew.bat" cleanTest test --no-daemon --no-build-cache
set TEST_RESULT=!ERRORLEVEL!

:: -- Summary (parse JUnit XML results) ------------------------------------
echo.
echo --- Test Summary ---
powershell -NoProfile -Command "$x=Get-ChildItem -Recurse -Filter 'TEST-*.xml' | Where-Object{$_.FullName -like '*test-results*'}; $t=0;$f=0;$sk=0; $x | ForEach-Object{[xml]$d=Get-Content $_.FullName; $s=$d.testsuite; $t+=[int]$s.tests; $f+=[int]$s.failures+[int]$s.errors; $sk+=[int]$s.skipped}; $p=$t-$f-$sk; Write-Host ('  Total:   '+$t); if($f-eq 0){Write-Host ('  Passed:  '+$p) -ForegroundColor Green}else{Write-Host ('  Passed:  '+$p) -ForegroundColor Green; Write-Host ('  Failed:  '+$f) -ForegroundColor Red}; if($sk-gt 0){Write-Host ('  Skipped: '+$sk) -ForegroundColor Yellow}"
echo --------------------

if !TEST_RESULT! neq 0 (
    echo.
    echo TESTS FAILED. Reports:
    for /d /r %%D in (build\reports\tests\test) do (
        if exist "%%D\index.html" echo   %%D\index.html
    )
    exit /b !TEST_RESULT!
)

echo.
echo All tests passed.
exit /b 0
