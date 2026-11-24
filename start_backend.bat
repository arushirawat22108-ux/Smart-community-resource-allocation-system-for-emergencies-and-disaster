@echo off
REM Starts the Spring Boot backend on http://localhost:8080 (needs JDK 17+, Maven and MySQL running)
cd /d "%~dp0backend"
mvn spring-boot:run
