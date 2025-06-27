#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later

# Usage: uvicorn main:app


import uvloop

uvloop.install()

from starlette.applications import Starlette
from starlette.responses import Response
from starlette.routing import Route

import httpx


async def topic(request):
    path = request.path_params["path"]

    body = await request.body()

    headers = httpx.Headers({
        "TTL": "2592000", # 30 days
        "Content-Encoding": "aes128gcm", # Fake this encoding to be web push compliant
        "Urgency": "high"
    })

    # Forward the request to the target URL
    async with httpx.AsyncClient() as client:
        upstream_response = await client.post(
            url=path,
            data=body,
            headers=headers,
        )

    return Response(
        content=upstream_response.content, status_code=upstream_response.status_code
    )


app = Starlette(
    routes=[
        Route("/{path:path}", topic, methods=["PUT"]),
    ],
)
