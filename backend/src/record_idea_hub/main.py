from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .api import build_router
from .config import Settings
from .database import Database
from .gemini_lite import GeminiLiteService
from .github_writer import IdeaHubGitHubWriter
from .processor import Processor
from .security import DeviceAuthenticator

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)


def create_app(
    settings: Settings | None = None,
    *,
    gemini: GeminiLiteService | None = None,
    github: IdeaHubGitHubWriter | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.audio_dir.mkdir(parents=True, exist_ok=True)
    database = Database(settings.database_path)
    database.initialize()
    gemini = gemini or GeminiLiteService(settings.gemini_model)
    github = github or IdeaHubGitHubWriter(
        repository=settings.github_repository,
        branch=settings.github_branch,
        token=settings.github_token,
    )
    processor = Processor(
        database=database,
        gemini=gemini,
        github=github,
        poll_seconds=settings.processor_poll_seconds,
    )
    authenticator = DeviceAuthenticator(settings.device_token)

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        processor.cleanup_verified_audio()
        task: asyncio.Task[None] | None = None
        if settings.processor_enabled:
            task = asyncio.create_task(processor.run(), name="voice-intake-processor")
            processor.notify()
        try:
            yield
        finally:
            await processor.stop()
            if task is not None:
                await task
            await github.aclose()

    app = FastAPI(
        title="Record Idea Hub",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.include_router(
        build_router(
            settings=settings,
            database=database,
            processor=processor,
            authenticator=authenticator,
        )
    )
    app.state.settings = settings
    app.state.database = database
    app.state.processor = processor
    return app
