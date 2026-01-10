# Air Receiver Build Environment Setup Script
# Run this script in PowerShell as Administrator

Write-Host "Setting up build environment for Air Receiver..." -ForegroundColor Cyan

# Check if running as admin
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Please run this script as Administrator" -ForegroundColor Red
    exit 1
}

# Install JDK 17
Write-Host "`nInstalling JDK 17..." -ForegroundColor Yellow
winget install --id Microsoft.OpenJDK.17 --accept-source-agreements --accept-package-agreements

# Refresh environment
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

# Set JAVA_HOME
$javaPath = "C:\Program Files\Microsoft\jdk-17*"
$javaHome = (Get-ChildItem $javaPath -Directory | Select-Object -First 1).FullName
if ($javaHome) {
    [Environment]::SetEnvironmentVariable("JAVA_HOME", $javaHome, "User")
    $env:JAVA_HOME = $javaHome
    Write-Host "JAVA_HOME set to: $javaHome" -ForegroundColor Green
}

# Install Android Command Line Tools
Write-Host "`nInstalling Android SDK Command Line Tools..." -ForegroundColor Yellow

$androidSdkPath = "$env:LOCALAPPDATA\Android\Sdk"
$cmdlineToolsPath = "$androidSdkPath\cmdline-tools"

# Create SDK directory
New-Item -ItemType Directory -Force -Path $androidSdkPath | Out-Null

# Download command line tools
$cmdlineToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$cmdlineToolsZip = "$env:TEMP\cmdline-tools.zip"

Write-Host "Downloading Android Command Line Tools..."
Invoke-WebRequest -Uri $cmdlineToolsUrl -OutFile $cmdlineToolsZip

Write-Host "Extracting..."
Expand-Archive -Path $cmdlineToolsZip -DestinationPath $cmdlineToolsPath -Force
Rename-Item "$cmdlineToolsPath\cmdline-tools" "$cmdlineToolsPath\latest" -ErrorAction SilentlyContinue

# Set ANDROID_HOME
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $androidSdkPath, "User")
$env:ANDROID_HOME = $androidSdkPath

# Add to PATH
$sdkPaths = "$androidSdkPath\cmdline-tools\latest\bin;$androidSdkPath\platform-tools;$androidSdkPath\build-tools\34.0.0"
$currentPath = [Environment]::GetEnvironmentVariable("Path", "User")
if (-not $currentPath.Contains($androidSdkPath)) {
    [Environment]::SetEnvironmentVariable("Path", "$currentPath;$sdkPaths", "User")
}

# Accept licenses and install SDK components
Write-Host "`nInstalling SDK components..." -ForegroundColor Yellow
$sdkmanager = "$cmdlineToolsPath\latest\bin\sdkmanager.bat"

# Accept licenses
Write-Host "y" | & $sdkmanager --licenses 2>$null

# Install required SDK components
& $sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Build environment setup complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "`nPlease restart your terminal/PowerShell for changes to take effect."
Write-Host "`nTo build the project:"
Write-Host "  cd $PWD"
Write-Host "  .\gradlew assembleDebug"
Write-Host "`nThe APK will be at: app\build\outputs\apk\debug\app-debug.apk"
