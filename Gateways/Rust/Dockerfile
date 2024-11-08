FROM rust:latest AS builder

WORKDIR /p2p-gateway

COPY Cargo.toml Cargo.lock ./

RUN mkdir src && \
    echo "fn main() {}" > src/main.rs

RUN cargo build --release && \
    rm -rvf src

COPY src src

RUN touch src/main.rs \
    && cargo build --release


FROM debian:latest AS runner

RUN apt-get update \
    && apt-get install -y libssl3 \
    && apt-get clean

COPY --from=builder /p2p-gateway/target/release/p2p-gateway /p2p-gateway/

EXPOSE 8001

ENTRYPOINT ["/p2p-gateway/p2p-gateway"]
