# CloudIDE Run Guide

## Prerequisites

- `Node.js 20 LTS` or newer
- `npm`
- `Java 17` for Android builds
- `Android Studio` with SDK 34
- A running `MySQL` instance for the backend

## Repository Layout

```text
cloudide/
├── backend/
├── desktop/
├── android/
├── shared/
└── RUNNING.md
```

## 1. Run the Backend

### Setup

1. Create the env file:

```powershell
Copy-Item backend\.env.example backend\.env
```

2. Edit `backend/.env` and set:

- `DB_HOST`
- `DB_USER`
- `DB_PASS`
- `DB_NAME`
- `JWT_SECRET`
- `GOOGLE_CLIENT_ID`
- `FIREBASE_CREDENTIALS_JSON`

3. Install dependencies:

```powershell
cd backend
npm install
```

### Start

```powershell
npm start
```

Expected health check:

```text
http://localhost:3000/health
```

## 2. Run the Desktop App

### Setup

1. Create the env file:

```powershell
Copy-Item desktop\.env.example desktop\.env
```

2. Edit `desktop/.env` and set:

- `VITE_GOOGLE_CLIENT_ID`
- `VITE_GOOGLE_REDIRECT_URI`
- `VITE_API_BASE_URL`
- `VITE_JUDGE0_URL` or leave it unset and use the fallback value in code
- `VITE_PISTON_URL`
- `VITE_APP_VERSION`
- `VITE_DRIVE_CLOUDIDE_FOLDER`

3. Install dependencies:

```powershell
cd desktop
npm install
```

### Start in development

```powershell
npm run dev
```

This starts:

- Vite on `http://localhost:5173`
- Electron pointed at the Vite renderer

### Build

```powershell
npm run build
```

## 3. Run the Android App

### Setup

1. Open `android/` in Android Studio.
2. Set values in `android/local.properties`.
3. Make sure the Android app has the required assets and Gradle configuration from the spec.

Minimum local setup:

- Android SDK 34
- NDK/CMake for the JNI PTY bridge
- Emulator or physical Android device

### Run from Android Studio

1. Sync Gradle.
2. Select a device.
3. Press `Run`.

### Run from command line

From the repo root:

```powershell
cd android
.\gradlew assembleDebug
.\gradlew installDebug
```

## 4. Run Both Backend and Desktop Together

Use two terminals.

### Terminal 1

```powershell
cd backend
npm install
npm start
```

### Terminal 2

```powershell
cd desktop
npm install
npm run dev
```

## 5. Notes

- The backend must be running before Google auth verification from the desktop or Android app will work.
- The desktop app currently depends on native modules like `keytar` and `node-pty`, so `npm install` must complete successfully on your machine.
- The Android app is not fully implemented yet, so these steps describe the intended run path once its Gradle project and source are completed.
