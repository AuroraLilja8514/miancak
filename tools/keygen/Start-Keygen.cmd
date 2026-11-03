@echo off
setlocal
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "PSModulePath=%SystemRoot%\System32\WindowsPowerShell\v1.0\Modules"
set "KEYGEN_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if defined PROCESSOR_ARCHITEW6432 set "KEYGEN_PS=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%~dp0Miancak-Keygen.ps1" (
  echo Please extract the entire ZIP first, then run Start-Keygen.cmd.
  pause
  exit /b 1
)
if /I "%~1"=="--check" (
  "%KEYGEN_PS%" -NoLogo -NoProfile -NonInteractive -STA -ExecutionPolicy Bypass -File "%~dp0Miancak-Keygen.ps1" -SmokeTestUi
) else (
  "%KEYGEN_PS%" -NoLogo -NoProfile -NonInteractive -STA -ExecutionPolicy Bypass -File "%~dp0Miancak-Keygen.ps1"
)
if errorlevel 1 (
  echo Generator could not start. Read README.txt for system requirements.
  pause
  exit /b 1
)
endlocal
