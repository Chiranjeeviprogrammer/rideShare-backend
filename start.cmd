@echo off
REM RideShare Backend Startup Script
REM Loads environment variables from .env and starts the Spring Boot application

setlocal

REM Load variables from .env file
for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
    if not "%%a"=="" if not "%%b"=="" (
        set "%%a=%%b"
    )
)

REM Check if Docker PostgreSQL container is running
where docker >nul 2>nul
if %ERRORLEVEL% equ 0 (
    echo [RideShare] Ensuring PostgreSQL database container 'rideshare-local-db' is running...
    docker start rideshare-local-db >nul 2>nul
)

echo [RideShare] Starting backend with Brevo email integration...
echo [RideShare] Brevo sender: %BREVO_SENDER_EMAIL%

call mvnw.cmd spring-boot:run -Dspring-boot.run.jvmArguments="-XX:TieredStopAtLevel=1"

endlocal
