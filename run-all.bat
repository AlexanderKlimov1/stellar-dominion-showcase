@echo off
rem Run the game - all three ways it is started, and every check they need:
rem
rem     run-all.bat             server on 8080 + Vite dev server on 5173, the everyday way
rem     run-all.bat dist        server only; it serves the built client from moo3-client\dist
rem     run-all.bat published   the same, with the settings from run-published.cmd, and
rem                             then publish.bat puts port 8080 on the internet
rem
rem There are two clients and they are not interchangeable. The dev server rebuilds on
rem every keystroke, but it asks nobody for anything and hands out the project sources as
rem they are, so it never leaves this machine and its network. What goes outside is the
rem build, served by the game server itself on 8080.
rem
rem Each half is started in its own window and only if its port is still free. A second
rem server dies on bind - but only after Liquibase has already reached the database, and
rem the first jar keeps the file open so the next build.cmd fails on repackage. A second
rem Vite does not fail at all: it quietly moves to 5174, where neither the browser nor the
rem proxy to /api looks for it.
rem
rem The server is started from moo3-server on purpose: the paths to the JSON reference
rem files and to the built client are relative, and a server started elsewhere answers
rem 500 instead of reading them - see CLAUDE.md.

setlocal
set "ROOT=%~dp0"
set "SERVER_PORT=8080"
set "CLIENT_PORT=5173"
set "SERVER_JAR=%ROOT%moo3-server\target\moo3-server-0.1.0-SNAPSHOT.jar"
set "DIST=%ROOT%moo3-client\dist"
set "MODE=%~1"

echo.

if /i "%MODE%"=="" goto :server
if /i "%MODE%"=="dist" goto :server
if /i "%MODE%"=="published" goto :settings
echo Unknown mode "%MODE%". Use: run-all.bat [dist^|published]
goto :fail

rem --- settings of the published game ----------------------------------------

rem The server takes them from its environment at startup, so they are loaded here, before
rem it is started, and inherited by the window it runs in. A server that is already up
rem cannot be told any of this - it would have to be restarted.
:settings
if not exist "%ROOT%run-published.cmd" goto :no_settings
set "MOO3_RUN_ALL=1"
call "%ROOT%run-published.cmd"
set "MOO3_RUN_ALL="
if not defined MOO3_CLIENT_URL goto :no_address
if not defined MOO3_CORS_ORIGINS goto :no_address
echo %MOO3_CLIENT_URL% | findstr /i /c:"localhost" /c:"127.0.0.1" >nul
if not errorlevel 1 goto :local_address
echo [published] address: %MOO3_CLIENT_URL%
echo [published] /api is open to that origin only, /actuator shows %MOO3_ACTUATOR%
goto :server

:no_settings
echo [published] run-published.cmd is missing - it holds the address the game is shown under
goto :fail

:no_address
echo [published] run-published.cmd sets no MOO3_CLIENT_URL / MOO3_CORS_ORIGINS
echo [published] without them confirmation mail links outsiders to their own localhost
goto :fail

:local_address
echo [published] MOO3_CLIENT_URL points at localhost: %MOO3_CLIENT_URL%
echo [published] that is the address of whoever opens the mail, not of this machine
goto :fail

rem --- server ----------------------------------------------------------------

:server
call :listening %SERVER_PORT%
if not errorlevel 1 goto :server_busy
if not exist "%SERVER_JAR%" goto :no_jar
rem The script is called by its full path on purpose: with NoDefaultCurrentDirectoryInExePath
rem set - and some machines have it - cmd does not look for a command in the current
rem directory at all, and "cmd /k run.cmd" says "not recognized" from the right folder.
echo [server] port %SERVER_PORT% is free, starting
start "moo3 server" /D "%ROOT%moo3-server" cmd /k "%ROOT%moo3-server\run.cmd"
call :await %SERVER_PORT% 90
if errorlevel 1 (
  echo [server] port %SERVER_PORT% has not opened in 90 s - look at the server window
) else (
  echo [server] listening on %SERVER_PORT%
)
goto :client

rem A running server keeps the environment it was started with, and there is no way to
rem hand it new settings. Whether it got them is visible from outside: with MOO3_ACTUATOR
rem narrowed down, /actuator/metrics is gone.
:server_busy
echo [server] port %SERVER_PORT% is already taken - the server runs, not starting a second one
if /i not "%MODE%"=="published" goto :client
for /f %%c in ('curl -s -o nul -w "%%{http_code}" http://localhost:%SERVER_PORT%/actuator/metrics') do set "METRICS=%%c"
if "%METRICS%"=="200" echo [server] and it was started without the published settings - restart it to apply them
goto :client

:no_jar
echo [server] jar not found: %SERVER_JAR%
echo [server] build it first: moo3-server\build.cmd
goto :fail

rem --- client ----------------------------------------------------------------

:client
if /i "%MODE%"=="dist" goto :client_built
if /i "%MODE%"=="published" goto :client_built

call :listening %CLIENT_PORT%
if not errorlevel 1 goto :client_busy
if not exist "%ROOT%moo3-client\node_modules" goto :no_modules
echo [client] port %CLIENT_PORT% is free, starting
start "moo3 client" /D "%ROOT%moo3-client" cmd /k npm run dev
call :await %CLIENT_PORT% 60
if errorlevel 1 (
  echo [client] port %CLIENT_PORT% has not opened in 60 s - look at the client window
) else (
  echo [client] listening on %CLIENT_PORT%
)
goto :done

:client_busy
echo [client] port %CLIENT_PORT% is already taken - the client runs, not starting a second one
goto :done

:no_modules
echo [client] node_modules is missing - run moo3-client\setup.cmd
echo [client] npm install from the client directory would replace the junction to C:\react
goto :fail

rem The built client needs no process of its own: the server serves it. It is looked up on
rem every request, so a build made after the server started is picked up as it is - and in
rem published mode publish.bat builds it in a moment anyway.
:client_built
if /i "%MODE%"=="published" goto :publish
if not exist "%DIST%\index.html" goto :no_dist
echo [client] the built client is served by the server itself, no dev server started
goto :done_dist

:no_dist
echo [client] %DIST% is not built
echo [client] build it: npm run build --prefix "%ROOT%moo3-client"
goto :fail

rem --- publishing ------------------------------------------------------------

rem publish.bat does the rest: builds the client, refuses to publish sources along with
rem it, checks that the server really answers with the game, and turns the funnel on.
:publish
echo.
call "%ROOT%publish.bat"
if errorlevel 1 goto :fail
endlocal
exit /b 0

rem --- done ------------------------------------------------------------------

:done
echo.
echo Game:   http://localhost:%CLIENT_PORT%          development, this machine only
echo Build:  http://localhost:%SERVER_PORT%          what publish.bat puts on the internet
echo Server: http://localhost:%SERVER_PORT%/actuator/health
endlocal
exit /b 0

:done_dist
echo.
echo Game:   http://localhost:%SERVER_PORT%
echo Server: http://localhost:%SERVER_PORT%/actuator/health
endlocal
exit /b 0

:fail
echo.
echo Something did not start - see the messages above.
endlocal
pause
exit /b 1

rem --- routines --------------------------------------------------------------

:listening
rem %1 - TCP port. Returns 0 when somebody already listens on it.
rem
rem The pattern anchors the port to the colon of the local address, so :8080 does not
rem match :18080, and LISTENING keeps established connections to that port out.
netstat -ano | findstr /r /c:":%~1 .*LISTENING" >nul
exit /b %errorlevel%

:await
rem %1 - TCP port, %2 - how many seconds to wait for it to open.
rem
rem ping instead of timeout: timeout refuses to work when the script is started with
rem redirected input, and this one is meant to be run from anywhere.
setlocal
set /a left=%~2
:await_loop
call :listening %1
if not errorlevel 1 (
  endlocal
  exit /b 0
)
if %left% leq 0 (
  endlocal
  exit /b 1
)
set /a left-=1
ping -n 2 127.0.0.1 >nul
goto :await_loop
