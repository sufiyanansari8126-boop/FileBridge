@rem = 2>nul & cls
@echo off
@setlocal
@set DIRNAME=%~dp0
@if "%DIRNAME%" == "" set DIRNAME=.
@call gradle %*
@endlocal
