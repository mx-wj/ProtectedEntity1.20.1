@echo off
py -3 "%~dp0generate_at.py" %*
if "%~1"=="" pause
