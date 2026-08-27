from __future__ import annotations


class RecordIdeaHubError(Exception):
    """Base application error."""


class ConfigurationError(RecordIdeaHubError):
    """Required runtime configuration is absent or unsafe."""


class QuotaDeferred(RecordIdeaHubError):
    """The shared provider limiter declined a request for now."""

    def __init__(self, reason: str, retry_after_seconds: int = 30) -> None:
        super().__init__(reason)
        self.reason = reason
        self.retry_after_seconds = max(1, int(retry_after_seconds))


class GitHubConflict(RecordIdeaHubError):
    """GitHub main moved or a deterministic intake path conflicts."""
