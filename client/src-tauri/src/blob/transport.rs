use crate::error::{Error, Result};
use serde::Deserialize;
use std::io::Read;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InitResult {
    Exists,
    Created,
}

#[derive(Debug, Clone, Deserialize)]
struct InitResponse {
    status: String,
}

impl InitResponse {
    fn into_result(self) -> Result<InitResult> {
        match self.status.as_str() {
            "EXISTS" => Ok(InitResult::Exists),
            "CREATED" => Ok(InitResult::Created),
            other => Err(Error::Sync(format!("unexpected init status {other}"))),
        }
    }
}

pub trait BlobTransport: Send + Sync {
    fn init_upload(&self, blob_id: &str, size: u64, mime_type: Option<&str>) -> Result<InitResult>;
    fn put_chunk(&self, blob_id: &str, index: u32, data: &[u8]) -> Result<()>;
    fn complete_upload(&self, blob_id: &str) -> Result<()>;
    fn download(&self, blob_id: &str) -> Result<Vec<u8>>;
}

pub struct UreqBlobTransport {
    agent: ureq::Agent,
    base_url: String,
    token: String,
}

impl UreqBlobTransport {
    pub fn new(base_url: &str, token: &str) -> Result<Self> {
        if base_url.is_empty() {
            return Err(Error::InvalidArgument("base_url is empty".into()));
        }
        let agent = ureq::AgentBuilder::new()
            .timeout(std::time::Duration::from_secs(60))
            .build();
        Ok(UreqBlobTransport {
            agent,
            base_url: base_url.trim_end_matches('/').to_string(),
            token: token.to_string(),
        })
    }
}

impl BlobTransport for UreqBlobTransport {
    fn init_upload(&self, blob_id: &str, size: u64, mime_type: Option<&str>) -> Result<InitResult> {
        let url = format!("{}/api/v1/blobs/init", self.base_url);
        let mut body = serde_json::json!({
            "blob_id": blob_id,
            "size": size,
        });
        if let Some(mime) = mime_type {
            body["mime_type"] = serde_json::json!(mime);
        }
        let resp = self
            .agent
            .post(&url)
            .set("Authorization", &format!("Bearer {}", self.token))
            .send_json(body)?;
        if resp.status() != 200 {
            return Err(Error::Sync(format!("blob init http {}", resp.status())));
        }
        let parsed: InitResponse = resp.into_json()?;
        parsed.into_result()
    }

    fn put_chunk(&self, blob_id: &str, index: u32, data: &[u8]) -> Result<()> {
        let url = format!("{}/api/v1/blobs/{}/chunks/{}", self.base_url, blob_id, index);
        let resp = self
            .agent
            .put(&url)
            .set("Authorization", &format!("Bearer {}", self.token))
            .set("Content-Type", "application/octet-stream")
            .send_bytes(data)?;
        if resp.status() != 200 {
            return Err(Error::Sync(format!("blob chunk http {}", resp.status())));
        }
        Ok(())
    }

    fn complete_upload(&self, blob_id: &str) -> Result<()> {
        let url = format!("{}/api/v1/blobs/{}/complete", self.base_url, blob_id);
        let resp = self
            .agent
            .post(&url)
            .set("Authorization", &format!("Bearer {}", self.token))
            .send_bytes(&[])?;
        if resp.status() != 200 {
            return Err(Error::Sync(format!("blob complete http {}", resp.status())));
        }
        Ok(())
    }

    fn download(&self, blob_id: &str) -> Result<Vec<u8>> {
        let url = format!("{}/api/v1/blobs/{}", self.base_url, blob_id);
        let resp = self
            .agent
            .get(&url)
            .set("Authorization", &format!("Bearer {}", self.token))
            .call()?;
        if resp.status() != 200 {
            return Err(Error::Sync(format!("blob download http {}", resp.status())));
        }
        let mut buf = Vec::new();
        resp.into_reader().take(1 << 31).read_to_end(&mut buf)?;
        Ok(buf)
    }
}
