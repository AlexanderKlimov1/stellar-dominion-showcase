@echo off
rem Открывает запертые описания сборки. Ключ - файл, который есть только у автора.
rem     unlock.cmd C:\path\to\showcase-key.txt
if "%~1"=="" (
  echo укажите файл ключа: unlock.cmd C:\path\to\showcase-key.txt
  exit /b 1
)
for /r %%f in (*.enc) do (
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 300000 -pass "file:%~1" -in "%%f" -out "%%~dpnf"
  echo открыт %%~dpnf
)
