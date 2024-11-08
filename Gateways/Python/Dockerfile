FROM python:latest

WORKDIR /p2p-gateway

RUN apt-get update && apt-get install -y \
    python3-aiofiles \
    python3-httpx \
    python3-starlette \
    python3-uvloop \
    uvicorn \
    && apt-get clean

COPY main.py ./

EXPOSE 80

ENTRYPOINT ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "80"]
