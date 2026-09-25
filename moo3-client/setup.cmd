@echo off
rem Install React and every framework into C:\react (spec item 2.3).
rem
rem Packages live in C:\react\node_modules; moo3-client\node_modules is a junction
rem pointing there. A plain `npm install` run from the client replaces that junction
rem with a real directory, so installation always goes through --prefix C:\react
rem and the junction is recreated afterwards.

setlocal
set "STORE=C:\react"

if not exist "%STORE%" mkdir "%STORE%"
copy /Y "%~dp0package.json" "%STORE%\package.json" >nul

call npm install --prefix "%STORE%" --cache "%STORE%\npm-cache" --no-fund --no-audit
if errorlevel 1 goto :error

if exist "%~dp0node_modules" rmdir /S /Q "%~dp0node_modules" 2>nul
mklink /J "%~dp0node_modules" "%STORE%\node_modules" >nul
if errorlevel 1 goto :error

echo.
echo Done: packages installed into %STORE%\node_modules, junction recreated.
endlocal
exit /b 0

:error
echo.
echo Setup failed.
endlocal
exit /b 1
