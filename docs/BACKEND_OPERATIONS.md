# Backend operations

## Required secrets

```text
RECORD_IDEA_HUB_DEVICE_TOKEN       random 32+ character phone credential
IDEA_HUB_GITHUB_TOKEN              fine-grained token: Contents write on idea-hub only
GOOGLE_AI_LIMITER_SUPABASE_URL     dedicated shared-limiter project
GOOGLE_AI_LIMITER_SUPABASE_SERVICE_KEY
GOOGLE_AI_NORMAL_KEY_ENVS          env-name pool already registered by events-bot-new
<each env named by GOOGLE_AI_NORMAL_KEY_ENVS> = actual Google API key
```

## Non-secret configuration

```text
GEMINI_LITE_MODEL=gemini-3.1-flash-lite
IDEA_HUB_GITHUB_REPOSITORY=onedayonemasterpiece/idea-hub
IDEA_HUB_GITHUB_BRANCH=main
RECORD_IDEA_HUB_DATA_DIR=/data
```

Use a persistent volume for `/data`. A stateless deployment can lose the only server copy after the phone has uploaded a chunk but before GitHub verification.

`backend/fly.example.toml` is a template; copy it to `fly.toml`, choose the application name and create the volume before deployment.
