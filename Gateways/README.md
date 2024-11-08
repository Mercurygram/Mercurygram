Both gateways support running in a Docker container. To do so:

```bash
cd Rust # or Python

cp example.env .env
```

The `.env` file specifies the port to be used for the gateway.

Start:

```bash
docker compose up -d
```

Stop:

```bash
docker compose down
```
