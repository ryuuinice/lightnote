use crate::blob::{BlobTransport, InitResult};
use crate::error::{Error, Result};
use crate::outbox::backoff_ms;
use crate::util::{sha256_hex, uuid_v7};
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::Duration;

pub const CHUNK_SIZE: usize = 4 * 1024 * 1024;
pub const UPLOAD_ATTEMPTS: usize = 3;

pub struct BlobManager {
    blob_dir: PathBuf,
    fixed_retry_ms: Option<i64>,
}

impl BlobManager {
    pub fn new(blob_dir: impl AsRef<Path>) -> Self {
        BlobManager {
            blob_dir: blob_dir.as_ref().to_path_buf(),
            fixed_retry_ms: None,
        }
    }

    pub fn with_retry_delay(blob_dir: impl AsRef<Path>, retry_ms: i64) -> Self {
        BlobManager {
            blob_dir: blob_dir.as_ref().to_path_buf(),
            fixed_retry_ms: Some(retry_ms),
        }
    }

    pub fn blob_dir(&self) -> &Path {
        &self.blob_dir
    }

    pub fn local_path(&self, blob_id: &str) -> PathBuf {
        self.blob_dir.join(blob_id.trim_start_matches("sha256:"))
    }

    pub fn has_local(&self, blob_id: &str) -> bool {
        self.local_path(blob_id).is_file()
    }

    pub fn read_local(&self, blob_id: &str) -> Result<Vec<u8>> {
        let path = self.local_path(blob_id);
        fs::read(&path).map_err(|_| Error::BlobMissing(blob_id.to_string()))
    }

    pub fn write_local_atomic(&self, blob_id: &str, data: &[u8]) -> Result<()> {
        if sha256_hex(data) != blob_id.trim_start_matches("sha256:") {
            return Err(Error::Sync(format!("blob content hash mismatch for {blob_id}")));
        }
        fs::create_dir_all(&self.blob_dir)?;
        let tmp = self.blob_dir.join(format!(".tmp-{}", uuid_v7()));
        let final_path = self.local_path(blob_id);
        let mut f = File::create(&tmp)?;
        f.write_all(data)?;
        f.sync_all()?;
        drop(f);
        if let Err(e) = fs::rename(&tmp, &final_path) {
            let _ = fs::remove_file(&tmp);
            return Err(e.into());
        }
        Ok(())
    }

    pub fn upload(&self, transport: &dyn BlobTransport, blob_id: &str, mime_type: Option<&str>) -> Result<bool> {
        let data = match self.read_local(blob_id) {
            Ok(data) => data,
            Err(_) => return Ok(false),
        };
        let size = data.len() as u64;
        let init = self.retry(|| transport.init_upload(blob_id, size, mime_type))?;
        if init == InitResult::Exists {
            return Ok(false);
        }
        for (index, chunk) in data.chunks(CHUNK_SIZE).enumerate() {
            self.retry(|| transport.put_chunk(blob_id, index as u32, chunk))?;
        }
        self.retry(|| transport.complete_upload(blob_id))?;
        Ok(true)
    }

    pub fn download(&self, transport: &dyn BlobTransport, blob_id: &str) -> Result<()> {
        if self.has_local(blob_id) {
            return Ok(());
        }
        let data = transport.download(blob_id)?;
        self.write_local_atomic(blob_id, &data)
    }

    fn retry<T>(&self, op: impl Fn() -> Result<T>) -> Result<T> {
        let mut last_err = None;
        for attempt in 1..=UPLOAD_ATTEMPTS {
            match op() {
                Ok(v) => return Ok(v),
                Err(e) => {
                    last_err = Some(e);
                    if attempt < UPLOAD_ATTEMPTS {
                        std::thread::sleep(self.delay_for(attempt as i64));
                    }
                }
            }
        }
        Err(last_err.unwrap_or_else(|| Error::Sync("retry exhausted".into())))
    }

    fn delay_for(&self, attempt: i64) -> Duration {
        let ms = match self.fixed_retry_ms {
            Some(ms) => ms,
            None => backoff_ms(attempt),
        };
        Duration::from_millis(ms.max(0) as u64)
    }
}
