@echo off
REM Serves the frontend on http://localhost:5500
cd /d "%~dp0frontend"
python -m http.server 5500
