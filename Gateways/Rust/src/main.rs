// SPDX-License-Identifier: GPL-2.0-or-later

use axum::{
    body::{Body, Bytes},
    extract::{DefaultBodyLimit, Path, Query, State},
    http::header::LOCATION,
    http::{HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{post, put},
    Router,
};
use listenfd::ListenFd;
use reqwest::dns::{Addrs, Name, Resolve, Resolving};
use reqwest::header::{HeaderMap as ReqwestHeaderMap, HeaderName as ReqwestHeaderName};
use reqwest::Client;
use serde::Deserialize;
use std::collections::HashMap;
use std::fmt;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::time::Instant;
use tracing::{error, info, warn};
use url::Url;

/// Shared application state: HTTP client + correlation cache.
struct AppState {
    client: Client,
    /// Records the time a POST /aesgcm was successfully forwarded for an endpoint.
    /// Key: endpoint URL string. Value: Instant of last successful forward.
    /// Used by the PUT handler to suppress duplicate wake-ups when an encrypted
    /// payload already arrived.
    recent_posts: Mutex<HashMap<String, Instant>>,
}

/// Correlation window: PUT handler waits this long for a matching POST /aesgcm.
/// If a POST arrives within this window (or already arrived), the PUT is a duplicate
/// and is suppressed. Otherwise a synthetic empty wake-up is forwarded.
const CORRELATION_WINDOW: Duration = Duration::from_millis(200);

/// PUT /<url> — Simple Push (token_type=4) handler.
///
/// Telegram sends a PUT to this route for every push event when token_type=4 is registered.
/// For regular messages, a matching POST /aesgcm also arrives within CORRELATION_WINDOW —
/// in that case the encrypted payload already woke the app, so we suppress the PUT.
/// For encrypted (secret) chats, only the PUT arrives (no content to encrypt); after
/// waiting the full window we forward an empty synthetic body to wake the app so it
/// connects and fetches the pending messages via MTProto.
async fn put_proxy(
    State(state): State<Arc<AppState>>,
    Path(path): Path<String>,
    body: Bytes,
) -> Response {
    let endpoint = match validate_endpoint(&path) {
        Ok(url) => url,
        Err(resp) => return resp,
    };

    let key = endpoint.as_str().to_owned();

    // Fast path: check if a POST already arrived before we even started waiting.
    if recent_post_within_window(&state.recent_posts, &key) {
        info!("put_proxy correlated (pre-wait) for {}", endpoint);
        return StatusCode::OK.into_response();
    }

    // Wait for the correlation window, then check again.
    tokio::time::sleep(CORRELATION_WINDOW).await;

    if recent_post_within_window(&state.recent_posts, &key) {
        info!("put_proxy correlated (post-wait) for {}", endpoint);
        return StatusCode::OK.into_response();
    }

    // No matching POST arrived — forward the original Simple Push body (typically "version=N")
    // as a wake-up signal. The app receives it, aesgcm decryption fails on the non-encrypted
    // payload, and it falls back to the MTProto wake-up path to retrieve pending messages.
    info!("put_proxy synthetic wake-up for {}", endpoint);
    match forward(&state.client, &endpoint, body).await {
        Ok(upstream) => {
            let status = upstream.status();
            if !status.is_success() {
                warn!("put_proxy synthetic upstream rejected {}: status={}", endpoint, status);
            }
            (status, Body::from_stream(upstream.bytes_stream())).into_response()
        }
        Err(e) => e,
    }
}

fn recent_post_within_window(cache: &Mutex<HashMap<String, Instant>>, key: &str) -> bool {
    // Use the eviction threshold (2s) rather than CORRELATION_WINDOW (200ms) as the lookup age.
    // CORRELATION_WINDOW only controls how long put_proxy waits for a POST to arrive.
    // A POST that arrived up to 2s ago is still valid evidence that the event was already
    // delivered as an encrypted payload — we don't need to send a synthetic wake-up.
    // Using CORRELATION_WINDOW here would cause false misses when POST arrived just over
    // 200ms before the PUT (which is possible under load), needlessly sending a synthetic.
    const LOOKUP_AGE: Duration = Duration::from_secs(2);
    cache
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .get(key)
        .map(|t| t.elapsed() < LOOKUP_AGE)
        .unwrap_or(false)
}

#[derive(Deserialize)]
struct AesgcmParams {
    e: String,
}

/// POST /aesgcm?e=<url-encoded-endpoint>
///
/// Serializes WebPush aesgcm headers (Encryption, Crypto-Key) into the body before
/// forwarding to the UnifiedPush endpoint. This is required because UP distributors
/// strip HTTP headers, making client-side aesgcm decryption impossible without this step.
///
/// Body format sent to the UP endpoint:
///   aesgcm\n
///   Encryption: <value>\n
///   Crypto-Key: <value>\n
///   <original binary ciphertext>
///
/// On success, records the endpoint in the correlation cache so the PUT handler
/// (Simple Push, token_type=4) knows this event was already delivered as encrypted.
async fn aesgcm(
    State(state): State<Arc<AppState>>,
    Query(params): Query<AesgcmParams>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if params.e.is_empty() {
        warn!("aesgcm request missing ?e= parameter");
        return StatusCode::BAD_REQUEST.into_response();
    }

    let endpoint = match validate_endpoint(&params.e) {
        Ok(url) => url,
        Err(resp) => return resp,
    };

    let encryption = headers
        .get("encryption")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let crypto_key = headers
        .get("crypto-key")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");

    if encryption.is_empty() || crypto_key.is_empty() {
        warn!(
            "aesgcm request to {} missing Encryption/Crypto-Key headers — decryption will fail on device",
            endpoint
        );
    }

    let new_body = make_aesgcm_body(encryption, crypto_key, &body);

    let upstream = match forward(&state.client, &endpoint, new_body).await {
        Ok(r) => r,
        Err(e) => return e,
    };

    let upstream_status = upstream.status();
    info!("aesgcm forwarded to {}: status={}", endpoint, upstream_status);

    if upstream_status.is_success() {
        // Record that an encrypted payload was successfully delivered for this endpoint.
        // The PUT handler checks this to suppress duplicate Simple Push wake-ups.
        state
            .recent_posts
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(endpoint.as_str().to_owned(), Instant::now());

        // Normalize any 2xx to 201 Created per WebPush spec to avoid Telegram backoff.
        let location_val = upstream
            .headers()
            .get(LOCATION)
            .cloned()
            .or_else(|| HeaderValue::from_str(endpoint.as_str()).ok())
            .unwrap_or_else(|| HeaderValue::from_static(""));

        return (StatusCode::CREATED, [(LOCATION, location_val)]).into_response();
    }

    warn!("aesgcm upstream rejected {}: status={}", endpoint, upstream_status);
    (upstream_status, Body::from_stream(upstream.bytes_stream())).into_response()
}

// ── security helpers ──────────────────────────────────────────────────────────

/// Returns true if `ip` is a public, routable address.
/// Rejects loopback, private RFC-1918, link-local, ULA, unspecified, multicast,
/// CGNAT (100.64.0.0/10), NAT64 prefixes, and IPv4-compatible/mapped IPv6.
fn is_ip_safe(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            !v4.is_loopback()
                && !v4.is_private()
                && !v4.is_link_local()
                && !v4.is_broadcast()
                && !v4.is_documentation()
                && !v4.is_unspecified()
                && !v4.is_multicast()
                && o[0] != 0
                && !(o[0] == 100 && (o[1] & 0xc0) == 64) // 100.64.0.0/10 (CGNAT)
                && !(o[0] == 192 && o[1] == 0 && o[2] == 2) // 192.0.2.0/24 TEST-NET
                && !(o[0] == 198 && o[1] == 51 && o[2] == 100) // 198.51.100.0/24 TEST-NET-2
                && !(o[0] == 203 && o[1] == 0 && o[2] == 113) // 203.0.113.0/24 TEST-NET-3
        }
        IpAddr::V6(v6) => {
            let o = v6.octets();
            !v6.is_loopback()
                && !v6.is_unspecified()
                && !v6.is_multicast()
                && (o[0] & 0xfe) != 0xfc // not ULA (fc00::/7)
                && !(o[0] == 0xfe && (o[1] & 0xc0) == 0x80) // not link-local (fe80::/10)
                && o[..12] != [0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0] // not 64:ff9b::/96 (NAT64 well-known)
                && !(o[0] == 0x00 && o[1] == 0x64 && o[2] == 0xff && o[3] == 0x9b && o[4] == 0x00 && o[5] == 0x01) // not 64:ff9b:1::/48 (local NAT64)
                && o[..12] != [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0] // not 0000::/96 (IPv4-compatible)
                && o[..12] != [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff] // not ::ffff:0:0/96 (IPv4-mapped)
        }
    }
}

/// Lazy `Display` wrapper that formats blocked IPs for the warning log
/// without allocating a `Vec<String>` or a joined `String`.
struct BlockedIps<'a>(&'a [SocketAddr]);

impl fmt::Display for BlockedIps<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut first = true;
        for addr in self.0 {
            if !first {
                f.write_str(", ")?;
            }
            write!(f, "{}", addr.ip())?;
            first = false;
        }
        Ok(())
    }
}

/// A reqwest DNS resolver that enforces `is_ip_safe` at connection time.
/// This is the single DNS resolution used for each connection — reqwest connects
/// to exactly the IPs returned here, eliminating any TOCTOU gap.
#[derive(Clone, Copy, Debug, Default)]
struct SafeResolver;

impl Resolve for SafeResolver {
    fn resolve(&self, name: Name) -> Resolving {
        let host = name.as_str().to_owned();

        Box::pin(async move {
            let iter = tokio::net::lookup_host((host.as_str(), 0u16))
                .await
                .map_err(|e| {
                    warn!("SECURITY block {host:?}: DNS resolution failed ({e})");
                    Box::new(e) as Box<dyn std::error::Error + Send + Sync>
                })?;

            let mut safe = Vec::with_capacity(4);
            let mut blocked = Vec::new();

            for addr in iter {
                if is_ip_safe(addr.ip()) {
                    safe.push(addr);
                } else {
                    blocked.push(addr);
                }
            }

            if safe.is_empty() {
                warn!(
                    "SECURITY block {host:?}: all resolved IPs are non-public ({})",
                    BlockedIps(&blocked)
                );
                return Err(Box::new(std::io::Error::new(
                    std::io::ErrorKind::ConnectionRefused,
                    "endpoint resolves only to non-public addresses",
                )) as _);
            }

            Ok(Box::new(safe.into_iter()) as Addrs)
        })
    }
}

/// Validate that `endpoint` uses http/https, has a host, and carries no credentials.
/// Returns the parsed `Url` on success so callers avoid re-parsing.
/// Returns `Err(403 Forbidden)` on any violation.
/// IP safety is enforced at connection time by `SafeResolver` (no TOCTOU gap).
fn validate_endpoint(endpoint: &str) -> Result<Url, Response> {
    let parsed = Url::parse(endpoint).map_err(|e| {
        warn!("SECURITY reject {endpoint:?}: invalid URL ({e})");
        StatusCode::FORBIDDEN.into_response()
    })?;

    let scheme = parsed.scheme();
    if scheme != "https" && scheme != "http" {
        warn!("SECURITY reject {endpoint:?}: scheme {scheme:?} is not http/https");
        return Err(StatusCode::FORBIDDEN.into_response());
    }

    if !parsed.username().is_empty() || parsed.password().is_some() {
        warn!("SECURITY reject {endpoint:?}: URL contains credentials");
        return Err(StatusCode::FORBIDDEN.into_response());
    }

    match parsed.host() {
        None => {
            warn!("SECURITY reject {endpoint:?}: no host");
            return Err(StatusCode::FORBIDDEN.into_response());
        }
        Some(url::Host::Ipv4(ip)) => {
            if !is_ip_safe(IpAddr::V4(ip)) {
                warn!("SECURITY reject {endpoint:?}: literal IP {ip} is non-public");
                return Err(StatusCode::FORBIDDEN.into_response());
            }
        }
        Some(url::Host::Ipv6(ip)) => {
            if !is_ip_safe(IpAddr::V6(ip)) {
                warn!("SECURITY reject {endpoint:?}: literal IP {ip} is non-public");
                return Err(StatusCode::FORBIDDEN.into_response());
            }
        }
        Some(url::Host::Domain(_)) => {}
    }

    Ok(parsed)
}

/// Forward `body` via POST to `endpoint` with WebPush headers.
/// Returns the raw reqwest response on success, or an error `Response` on network failure.
async fn forward(
    client: &Client,
    endpoint: &Url,
    body: impl Into<reqwest::Body>,
) -> Result<reqwest::Response, Response> {
    client
        .post(endpoint.clone())
        .body(body)
        .send()
        .await
        .map_err(|err| {
            error!("forward error: {err}");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        })
}


/// Periodically evict stale entries from the correlation cache.
/// Runs every 5 seconds; removes entries older than 2 seconds.
async fn cleanup_recent_posts(state: Arc<AppState>) {
    const EVICT_AFTER: Duration = Duration::from_secs(2);
    let mut interval = tokio::time::interval(Duration::from_secs(5));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        interval.tick().await;
        let mut guard = state.recent_posts.lock().unwrap_or_else(|p| p.into_inner());
        guard.retain(|_, t| t.elapsed() < EVICT_AFTER);
    }
}

#[inline]
fn make_aesgcm_body(encryption: &str, crypto_key: &str, body: &[u8]) -> Vec<u8> {
    const PREFIX: &[u8] = b"aesgcm\nEncryption: ";
    const CRYPTO_KEY_HDR: &[u8] = b"Crypto-Key: ";

    let capacity = PREFIX.len() + encryption.len() + 1
        + CRYPTO_KEY_HDR.len() + crypto_key.len() + 1
        + body.len();

    let mut new_body = Vec::with_capacity(capacity);
    new_body.extend_from_slice(PREFIX);
    new_body.extend_from_slice(encryption.as_bytes());
    new_body.push(b'\n');
    new_body.extend_from_slice(CRYPTO_KEY_HDR);
    new_body.extend_from_slice(crypto_key.as_bytes());
    new_body.push(b'\n');
    new_body.extend_from_slice(body);
    new_body
}

fn init_logging() {
    use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));

    match tracing_journald::layer() {
        Ok(journald) => {
            tracing_subscriber::registry()
                .with(filter)
                .with(journald)
                .init();
        }
        Err(_) => {
            tracing_subscriber::registry()
                .with(filter)
                .with(tracing_subscriber::fmt::layer().with_writer(std::io::stderr))
                .init();
        }
    }
}

#[tokio::main]
async fn main() {
    init_logging();

    let mut default_headers = ReqwestHeaderMap::with_capacity(3);
    default_headers.insert(
        ReqwestHeaderName::from_static("ttl"),
        HeaderValue::from_static("2592000"),
    );
    default_headers.insert(
        ReqwestHeaderName::from_static("urgency"),
        HeaderValue::from_static("high"),
    );
    default_headers.insert(
        ReqwestHeaderName::from_static("content-encoding"),
        HeaderValue::from_static("aes128gcm"),
    );

    let client = Client::builder()
        .default_headers(default_headers)
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::none())
        .dns_resolver(Arc::new(SafeResolver))
        .build()
        .unwrap();

    let state = Arc::new(AppState {
        client,
        recent_posts: Mutex::new(HashMap::new()),
    });

    tokio::spawn(cleanup_recent_posts(Arc::clone(&state)));

    let app = Router::new()
        .route("/aesgcm", post(aesgcm))
        .route("/{*path}", put(put_proxy))
        .layer(DefaultBodyLimit::max(65536))
        .with_state(state);

    let listener = {
        let mut listenfd = ListenFd::from_env();
        match listenfd.take_tcp_listener(0).unwrap() {
            Some(std_listener) => {
                std_listener.set_nonblocking(true).unwrap();
                tokio::net::TcpListener::from_std(std_listener).unwrap()
            }
            None => {
                // No socket-activated fd: bind directly. LISTEN_ADDR lets a
                // container override the default loopback bind (the systemd
                // deployment uses socket activation and never sets it).
                let addr = std::env::var("LISTEN_ADDR")
                    .unwrap_or_else(|_| "127.0.0.1:8001".to_owned());
                tokio::net::TcpListener::bind(&addr).await.unwrap()
            }
        }
    };

    info!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}
