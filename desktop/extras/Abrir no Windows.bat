@echo off
rem Abre o Gestao Confeitaria. Precisa do Java 17 ou mais novo.
rem (A versao "GestaoConfeitaria-Windows.zip" do GitHub ja traz o Java e nao precisa deste arquivo.)
setlocal
cd /d "%~dp0"
set "JAVAW="

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVAW for %%J in (javaw.exe) do if not "%%~$PATH:J"=="" set "JAVAW=%%~$PATH:J"
if not defined JAVAW for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\*" "%ProgramFiles%\Java\*" "%ProgramFiles%\Microsoft\jdk*" "%ProgramFiles%\Zulu\*" "%ProgramFiles%\Amazon Corretto\*" "%ProgramFiles%\Android\Android Studio\jbr") do if exist "%%~D\bin\javaw.exe" set "JAVAW=%%~D\bin\javaw.exe"

if not defined JAVAW (
  echo.
  echo  O Java nao esta instalado neste computador.
  echo.
  echo  Vou abrir a pagina de download. Baixe o instalador .msi do Java 17 ^(JRE^),
  echo  instale e depois abra este arquivo de novo.
  echo.
  start "" "https://adoptium.net/temurin/releases/?os=windows&arch=x64&package=jre&version=17"
  pause
  exit /b 1
)

start "" "%JAVAW%" -jar "%~dp0GestaoConfeitaria.jar"
