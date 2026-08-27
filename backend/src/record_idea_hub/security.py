from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, status


class DeviceAuthenticator:
    def __init__(self, expected_token: str) -> None:
        self.expected_token = expected_token

    def verify(self, authorization: str | None = Header(default=None)) -> None:
        prefix = "Bearer "
        if not authorization or not authorization.startswith(prefix):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="missing bearer token",
            )
        supplied = authorization[len(prefix) :]
        if not hmac.compare_digest(supplied, self.expected_token):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid bearer token",
            )
