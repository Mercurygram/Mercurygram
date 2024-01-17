// SPDX-License-Identifier: GPL-2.0-or-later

use axum::http::{HeaderMap, StatusCode};
use axum::{
    body::{Body, Bytes},
    extract::{Path, State},
    http::{HeaderName, HeaderValue},
    response::{IntoResponse, Response},
    routing::put,
    Router
};
use axum_macros::debug_handler;
use reqwest::Client;

#[debug_handler]
async fn topic(State(client): State<Client>, Path(path): Path<String>, body: Bytes) -> Response {
    // Forward the request to the target URL
    let reqwest_response = match client
        .post(&path)
        .body(body)
        .send()
        .await
    {
        Ok(res) => res,
        Err(err) => {
            eprintln!("Request to {} failed: {}", path, err);
            return (StatusCode::INTERNAL_SERVER_ERROR, Body::empty()).into_response();
        }
    };

    let response_builder = Response::builder().status(reqwest_response.status().as_u16());


    // Here the mapping of headers is required due to reqwest and axum differ on the http crate versions
    let mut headers = HeaderMap::with_capacity(reqwest_response.headers().len());
    headers.extend(reqwest_response.headers().into_iter().map(|(name, value)| {
        let name = HeaderName::from_bytes(name.as_ref()).unwrap();
        let value = HeaderValue::from_bytes(value.as_ref()).unwrap();
        (name, value)
    }));

    response_builder
        .body(Body::from_stream(reqwest_response.bytes_stream()))
        // This unwrap is fine because the body is empty here
        .unwrap()
}

#[tokio::main]
async fn main() {
    let client = Client::new();
    let app = Router::new().route("/*path", put(topic)).with_state(client);

    let listener = tokio::net::TcpListener::bind("127.0.0.1:8001").await.unwrap();
    println!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}
