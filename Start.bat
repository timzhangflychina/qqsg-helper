@echo off
chcp 936 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

title QQSG Helper

set "JAR=%~dp0target\helper-1.0-SNAPSHOT-jar-with-dependencies.jar"
set "LOG=%~dp0start.log"

if not exist "%JAR%" (
    echo.
    echo  [ERROR] 找不到程序包:
    echo          %JAR%
    echo.
    echo  如果你下载的是源码包, 请先自行构建:  mvn clean package
    echo.
    pause
    exit /b 1
)

rem ---------------- 定位 java ----------------
rem 本包已自带精简 JRE (jre\ 目录), 正常情况无需安装 Java
set "JAVAW="

rem 1) 随包附带的 JRE (优先使用)
if exist "%~dp0jre\bin\javaw.exe" set "JAVAW=%~dp0jre\bin\javaw.exe"

rem 2) JAVA_HOME
if not defined JAVAW if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"

rem 3) 常见安装目录 (Adoptium / Oracle / Java / Microsoft)
if not defined JAVAW for %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk*") do if not defined JAVAW if exist "%%~fD\bin\javaw.exe" set "JAVAW=%%~fD\bin\javaw.exe"
if not defined JAVAW for %%D in ("%ProgramFiles%\Java\jdk*") do if not defined JAVAW if exist "%%~fD\bin\javaw.exe" set "JAVAW=%%~fD\bin\javaw.exe"
if not defined JAVAW for %%D in ("%ProgramFiles%\Microsoft\jdk*") do if not defined JAVAW if exist "%%~fD\bin\javaw.exe" set "JAVAW=%%~fD\bin\javaw.exe"
if not defined JAVAW for %%D in ("%LocalAppData%\Programs\Eclipse Adoptium\jdk*") do if not defined JAVAW if exist "%%~fD\bin\javaw.exe" set "JAVAW=%%~fD\bin\javaw.exe"

rem 4) PATH
if not defined JAVAW for %%J in (javaw.exe) do if not "%%~$PATH:J"=="" set "JAVAW=%%~$PATH:J"

if not defined JAVAW (
    echo.
    echo  [ERROR] 没有找到 Java 运行环境 —— jre\ 目录好像不完整.
    echo.
    echo  你可能是只下载了部分文件, 或者解压时漏掉了 jre 目录.
    echo.
    echo  解决办法 (任选其一):
    echo    1. 重新下载完整压缩包并完整解压 (推荐, 自带 jre 不用装 Java)
    echo    2. 把 JDK/JRE 11 或更高版本解压到本目录下的 jre\ 目录
    echo    3. 自行安装 Java:  https://adoptium.net/
    echo.
    echo  注意: 解压后请保持目录结构, jre\ 要和 target\ 在同一层.
    echo.
    pause
    exit /b 1
)

rem ---------------- 提权 ----------------
rem 游戏客户端是高完整性进程, 未提权时 UIPI 会拦掉发送过去的按键/窗口消息
net session >nul 2>&1
if errorlevel 1 (
    echo [ELEVATE] 正在请求管理员权限 ...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b 0
)

echo ============================================
echo   QQSG Helper
echo   java : %JAVAW%
echo   admin: yes
echo ============================================
echo.

>>"%LOG%" echo [%date% %time%] start javaw="%JAVAW%"
start "" "%JAVAW%" -jar "%JAR%"
>>"%LOG%" echo [%date% %time%] launched errorlevel=%errorlevel%

exit /b 0
