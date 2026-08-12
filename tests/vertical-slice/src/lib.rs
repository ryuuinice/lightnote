use lightnote_core::commands::Core;
use lightnote_core::engine::SyncEngine;
use lightnote_core::sync::{PushChange, PullResponse, PushResponse, SyncTransport, UreqTransport};
use lightnote_core::Result;
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

pub const USERNAME: &str = "admin";
pub const PASSWORD: &str = "admin123";
const JWT_SECRET: &str = "vertical-slice-jwt-secret";

static SERVER_BIN: OnceLock<PathBuf> = OnceLock::new();

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("crate must live in tests/")
        .parent()
        .expect("tests must live in repo root")
        .to_path_buf()
}

fn newest_go_mtime(dir: &Path) -> std::time::SystemTime {
    fn walk(dir: &Path, newest: &mut std::time::SystemTime) {
        for entry in std::fs::read_dir(dir).expect("read dir") {
            let entry = entry.expect("dir entry");
            let p = entry.path();
            if p.is_dir() {
                walk(&p, newest);
            } else if p.extension().map(|e| e == "go").unwrap_or(false) {
                let m = p.metadata().expect("metadata").modified().expect("mtime");
                if m > *newest {
                    *newest = m;
                }
            }
        }
    }
    let mut newest = std::time::SystemTime::UNIX_EPOCH;
    walk(dir, &mut newest);
    newest
}

fn go_binary() -> PathBuf {
    if Command::new("go").arg("version").output().map(|o| o.status.success()).unwrap_or(false) {
        return PathBuf::from("go");
    }
    let home = std::env::var("HOME").unwrap_or_default();
    let candidate = PathBuf::from(&home).join(".local/go/bin/go");
    if candidate.exists() {
        candidate
    } else {
        PathBuf::from("go")
    }
}

fn server_binary() -> &'static PathBuf {
    SERVER_BIN.get_or_init(|| {
        let out = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("target").join("lightnote-server");
        let server_dir = repo_root().join("server");
        let stale = newest_go_mtime(&server_dir)
            > out.metadata()
                .and_then(|m| m.modified())
                .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
        if stale {
            let status = Command::new(go_binary())
                .current_dir(&server_dir)
                .arg("build")
                .arg("-o")
                .arg(&out)
                .arg("./cmd/lightnote-server")
                .status()
                .expect("failed to run `go build` (is Go installed?)");
            assert!(status.success(), "go build of lightnote-server failed");
        }
        out
    })
}

fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .expect("bind probe listener")
        .local_addr()
        .expect("probe local addr")
        .port()
}

fn wait_ready(base_url: &str) -> bool {
    let agent = ureq::AgentBuilder::new().timeout(Duration::from_secs(1)).build();
    let deadline = Instant::now() + Duration::from_secs(20);
    while Instant::now() < deadline {
        if let Ok(resp) = agent.get(&format!("{base_url}/api/v1/healthz")).call() {
            if resp.status() == 200 {
                return true;
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    false
}

pub struct Server {
    child: Child,
    pub base_url: String,
    _dir: tempfile::TempDir,
    db_path: PathBuf,
    port: u16,
}

impl Server {
    pub fn start() -> Server {
        let bin = server_binary();
        for _ in 0..10 {
            let port = free_port();
            let dir = tempfile::tempdir().expect("server tempdir");
            let db = dir.path().join("server.db");
            let mut child = Command::new(bin)
                .env("LIGHTNOTE_ADDR", format!("127.0.0.1:{port}"))
                .env("LIGHTNOTE_DB_PATH", &db)
                .env("LIGHTNOTE_JWT_SECRET", JWT_SECRET)
                .env("LIGHTNOTE_USERNAME", USERNAME)
                .env("LIGHTNOTE_PASSWORD", PASSWORD)
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .expect("spawn lightnote-server");
            let base_url = format!("http://127.0.0.1:{port}");
            if wait_ready(&base_url) {
                return Server { child, base_url, _dir: dir, db_path: db, port };
            }
            let _ = child.kill();
            let _ = child.wait();
        }
        panic!("lightnote-server did not become ready");
    }

    pub fn kill(&mut self) {
        if self.child.try_wait().expect("try_wait").is_some() {
            return;
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
        let deadline = Instant::now() + Duration::from_secs(5);
        while self.child.try_wait().expect("try_wait").is_some() {
            if Instant::now() > deadline {
                break;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    pub fn restart(&mut self) {
        self.kill();
        let bin = server_binary();
        let mut child = Command::new(bin)
            .env("LIGHTNOTE_ADDR", format!("127.0.0.1:{}", self.port))
            .env("LIGHTNOTE_DB_PATH", &self.db_path)
            .env("LIGHTNOTE_JWT_SECRET", JWT_SECRET)
            .env("LIGHTNOTE_USERNAME", USERNAME)
            .env("LIGHTNOTE_PASSWORD", PASSWORD)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("respawn lightnote-server");
        if !wait_ready(&self.base_url) {
            let _ = child.kill();
            panic!("lightnote-server did not come back after restart");
        }
        self.child = child;
    }

    pub fn login(&self, device_name: &str) -> String {
        let agent = ureq::AgentBuilder::new().timeout(Duration::from_secs(10)).build();
        let body = serde_json::json!({
            "username": USERNAME,
            "password": PASSWORD,
            "device_name": device_name,
            "device_type": "desktop",
        });
        let resp = agent
            .post(&format!("{}/api/v1/auth/login", self.base_url))
            .send_json(body)
            .expect("login request");
        assert_eq!(resp.status(), 200, "login failed for {device_name}");
        let v: serde_json::Value = resp.into_json().expect("login response json");
        v["access_token"].as_str().expect("access_token").to_string()
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

pub fn setup(names: &[&str]) -> (Server, Vec<Client>) {
    let server = Server::start();
    let clients: Vec<Client> = names.iter().map(|n| Client::new(&server, n)).collect();
    (server, clients)
}

pub struct LimitTransport {
    inner: UreqTransport,
    limit: u32,
}

impl LimitTransport {
    pub fn new(inner: UreqTransport, limit: u32) -> LimitTransport {
        LimitTransport { inner, limit }
    }
}

impl SyncTransport for LimitTransport {
    fn push_changes(&self, changes: &[PushChange]) -> Result<PushResponse> {
        self.inner.push_changes(changes)
    }

    fn pull_changes(&self, after: i64, _limit: u32) -> Result<PullResponse> {
        self.inner.pull_changes(after, self.limit)
    }
}

pub struct Client {
    pub core: Core,
    pub engine: SyncEngine,
    pub dir: tempfile::TempDir,
    pub name: String,
    base_url: String,
    token: String,
}

impl Client {
    pub fn new(server: &Server, name: &str) -> Client {
        let dir = tempfile::tempdir().expect("client tempdir");
        let token = server.login(name);
        let core = Core::open(
            dir.path().join("data.db"),
            dir.path().join("blobs"),
            format!("client-{name}"),
            format!("device-{name}"),
        )
        .expect("open client core");
        let transport = UreqTransport::new(&server.base_url, &token).expect("transport");
        let engine = SyncEngine::new(Box::new(transport), format!("client-{name}"));
        Client {
            core,
            engine,
            dir,
            name: name.to_string(),
            base_url: server.base_url.clone(),
            token,
        }
    }

    pub fn limit_pull(&mut self, limit: u32) {
        let transport = UreqTransport::new(&self.base_url, &self.token).expect("transport");
        self.engine = SyncEngine::new(
            Box::new(LimitTransport::new(transport, limit)),
            format!("client-{}", self.name),
        );
    }

    pub fn from_paths(
        server: &Server,
        name: &str,
        db_path: &Path,
        blobs_path: &Path,
        token: &str,
    ) -> Client {
        let core = Core::open(
            db_path,
            blobs_path,
            format!("client-{name}"),
            format!("device-{name}"),
        )
        .expect("open client core");
        let transport = UreqTransport::new(&server.base_url, token).expect("transport");
        let engine = SyncEngine::new(Box::new(transport), format!("client-{name}"));
        Client {
            core,
            engine,
            dir: tempfile::tempdir().expect("client tempdir"),
            name: name.to_string(),
            base_url: server.base_url.clone(),
            token: token.to_string(),
        }
    }

    pub fn sync_allow_error(&mut self) -> bool {
        self.core.sync_trigger(&self.engine).is_err()
    }

    pub fn raw_transport(&self) -> UreqTransport {
        UreqTransport::new(&self.base_url, &self.token).expect("transport")
    }

    pub fn token(&self) -> &str {
        &self.token
    }

    pub fn sync(&mut self) {
        self.core.sync_trigger(&self.engine).expect("sync failed");
    }

    pub fn outbox_count(&self) -> i64 {
        lightnote_core::outbox::outbox_count(self.core.db().connection()).expect("outbox count")
    }

    pub fn cursor(&self) -> i64 {
        lightnote_core::cursor::get(
            self.core.db().connection(),
            &format!("client-{}", self.name),
            lightnote_core::util::now_ms(),
        )
        .expect("cursor")
    }

    pub fn note(&self, note_id: &str) -> lightnote_core::Note {
        self.core.get_note(note_id).expect("get note")
    }

    pub fn seed_blob_from(&self, peer: &Client, blob_id: &str) {
        let fname = blob_id.trim_start_matches("sha256:");
        let src = peer.dir.path().join("blobs").join(fname);
        let dst = self.dir.path().join("blobs").join(fname);
        std::fs::copy(src, dst).expect("copy blob file");
    }
}
