@echo off
REM ============================================================
REM  Kobol Demo -- build, run, and test
REM  Usage:
REM    run.bat                     build + start server (default)
REM    run.bat --port 8081         build + start server on port 8081
REM    run.bat --build             build only
REM    run.bat --test              build + run tests
REM
REM  Port precedence: --port arg  >  %PORT% env  >  demo default (8080).
REM  Dependencies downloaded automatically from Maven Central.
REM  Requires Java 21+ on PATH.
REM ============================================================
setlocal EnableDelayedExpansion

REM Clear JAVA_HOME so java on PATH takes precedence
set "JAVA_HOME="

REM Always run from this script's directory
cd /d "%~dp0"

REM ----- parse args: --port N, --build, --test -----
set "MODE=server"
:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="--port" (
    set "PORT=%~2"
    shift
    shift
    goto parse_args
)
if /I "%~1"=="--build" ( set "MODE=build" & shift & goto parse_args )
if /I "%~1"=="--test"  ( set "MODE=test"  & shift & goto parse_args )
echo WARNING: ignoring unknown argument "%~1"
shift
goto parse_args
:args_done

if not defined PORT set "PORT=8080"

echo ===  Kobol Demo  ===
echo.

REM ----- locate kobol command -----
where kobol >nul 2>&1
if %errorlevel%==0 (
    set "KOBOL=kobol"
    goto kobol_found
)

REM Fall back to Gradle distribution script
set "DIST_SCRIPT=%~dp0..\..\compiler\build\install\compiler\bin\compiler.bat"
if exist "%DIST_SCRIPT%" (
    echo Using: !DIST_SCRIPT!
    echo.
    set "KOBOL=!DIST_SCRIPT!"
    goto kobol_found
)

echo ERROR: kobol not found on PATH and compiler distribution not built.
echo.
echo Build + install kobol first ^(from repo root^):
echo   build-local.bat
echo Then open a NEW terminal so the updated PATH is loaded.
echo.
echo Or build just the distribution:
echo   gradlew.bat :compiler:installDist --no-daemon
exit /b 1

:kobol_found

if "%MODE%"=="test"  goto run_tests
if "%MODE%"=="build" goto build_only

REM ----- default: build + start server -----
echo [1/2] Building (downloads H2 automatically on first run)...
call "%KOBOL%" build
if errorlevel 1 (echo. & echo ERROR: build failed & exit /b 1)

echo.
echo [2/2] Starting server on http://localhost:%PORT%
echo       Endpoints:
echo         GET  /ping             health check
echo         GET  /status           version info
echo         POST /echo             echo JSON body
echo         GET  /hello/{name}     greeting with path param
echo.
echo       Press Ctrl+C to stop.
echo.
call "%KOBOL%" run
goto end

REM ----- build + test -----
:run_tests
echo Building...
call "%KOBOL%" build
if errorlevel 1 (echo. & echo ERROR: build failed & exit /b 1)
echo.
echo Running tests...
call "%KOBOL%" test
if errorlevel 1 (echo. & echo TESTS FAILED & exit /b 1)
echo All tests passed.
goto end

REM ----- build only -----
:build_only
call "%KOBOL%" build
if errorlevel 1 (echo. & echo ERROR: build failed & exit /b 1)
echo Build complete.  Run "run.bat" to start the server.
goto end

:end
endlocal
