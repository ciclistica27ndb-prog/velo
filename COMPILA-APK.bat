@echo off
setlocal EnableDelayedExpansion
title Velo - compilazione APK
cd /d "%~dp0"
set LOG=%~dp0build-log.txt
echo === Velo build %date% %time% === > "%LOG%"

rem --- Java (usa quello incluso in Android Studio) ---
set "JH="
for %%P in ("%ProgramFiles%\Android\Android Studio\jbr" "%ProgramFiles%\Android\Android Studio\jre" "%LOCALAPPDATA%\Programs\Android Studio\jbr" "%ProgramFiles%\Android\Android Studio Preview\jbr") do (
  if not defined JH if exist "%%~P\bin\java.exe" set "JH=%%~P"
)
if not defined JH if defined JAVA_HOME set "JH=%JAVA_HOME%"
echo JAVA_HOME=!JH! >> "%LOG%"
if not defined JH (
  echo ERRORE: Java non trovato >> "%LOG%"
  echo Java non trovato. & pause & exit /b 1
)
set "JAVA_HOME=!JH!"

rem --- Android SDK ---
set "SDK="
for %%P in ("%LOCALAPPDATA%\Android\Sdk" "%ANDROID_HOME%" "%ANDROID_SDK_ROOT%") do (
  if not defined SDK if exist "%%~P\platform-tools" set "SDK=%%~P"
)
if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK=%LOCALAPPDATA%\Android\Sdk"
echo ANDROID_SDK=!SDK! >> "%LOG%"
if defined SDK (
  set "SDKESC=!SDK:\=\\!"
  > local.properties echo sdk.dir=!SDKESC!
) else (
  echo ERRORE: Android SDK non trovato >> "%LOG%"
)

echo.
echo Compilo Velo... (1-3 minuti, la prima volta di piu')
echo Non chiudere questa finestra.
echo.
echo === GRADLE === >> "%LOG%"
call gradlew.bat --no-daemon veloRelease >> "%LOG%" 2>&1
set RC=!errorlevel!
echo === EXIT !RC! === >> "%LOG%"

if "!RC!"=="0" (
  copy /y "app\build\velo-release\velo.apk" "velo.apk" >nul
  copy /y "app\build\velo-release\update.json" "update.json" >nul
  echo FATTO: velo.apk creato in %~dp0
  echo === APK OK === >> "%LOG%"
) else (
  echo Compilazione non riuscita: Claude leggera' build-log.txt
)
echo === FINE === >> "%LOG%"
timeout /t 10
