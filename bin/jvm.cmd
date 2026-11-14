@echo off
rem Launcher for `jvm` on the jar build of jpm. With the native binary, copy jpm.exe to jvm.exe instead.
set "JPM_AS=jvm"
call "%~dp0jpm.cmd" %*
