@echo off
set SCRIPT_DIR=%~dp0
taskkill /F /IM java.exe /FI "WINDOWTITLE eq bot.jar"
timeout 5 > nul
set TZ=Asia/Shanghai
if exist "%SCRIPT_DIR%bot.jar.new" (
  set TS=%date:~0,4%%date:~5,2%%date:~8,2%%time:~0,2%%time:~3,2%%time:~6,2%
  set TS=%TS: =0%
  if exist "%SCRIPT_DIR%bot.jar" ren "%SCRIPT_DIR%bot.jar" bot.jar.bak.%TS%
  move /Y "%SCRIPT_DIR%bot.jar.new" "%SCRIPT_DIR%bot.jar" > nul
)
start "" javaw -Dfile.encoding=UTF-8 -jar "%SCRIPT_DIR%bot.jar" --spring.config.location=file:./config/
