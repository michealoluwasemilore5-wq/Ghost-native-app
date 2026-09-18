# MG GHOST AI Native v6.1

MG GHOST Android client + separate server backend.

## No user API key required

Users do **not** enter an OpenAI API key, database URL, or backend token.

The Android app connects to the MG GHOST API automatically. The API keeps `OPENAI_API_KEY` and `DATABASE_URL` on the server. On first AI use the app silently requests a random installation session token from `/v1/session` and stores it locally.

## Deploy the backend separately

See `backend/README.md`.

Set these Render environment variables:

- `DATABASE_URL` = your new PostgreSQL URL
- `OPENAI_API_KEY` = your OpenAI secret key
- optional `OPENAI_MODEL` = `gpt-5.6-luna`
- optional `DB_SSL` = `true` if your database requires TLS

The database schema is created automatically.

## Deploy/build the Android app separately

The app default API URL is compiled as:

`https://mg-ghost-api.onrender.com`

If you deploy the backend at that same Render URL, no APK change is needed. If you use a different API hostname, change `BuildConfig.API_BASE_URL` in `app/build.gradle.kts` and rebuild the APK once. End users still never type a key.

## Important

Do not put `OPENAI_API_KEY` or `DATABASE_URL` in the Android project, GitHub repository, or APK.
