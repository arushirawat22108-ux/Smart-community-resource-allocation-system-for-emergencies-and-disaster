@echo off
REM Starts the optional AI service on http://localhost:8001
cd /d "%~dp0"
python ai\serve.py --port 8001
