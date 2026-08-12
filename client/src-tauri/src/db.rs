use crate::error::Result;
use crate::migration;
use rusqlite::Connection;
use std::path::{Path, PathBuf};

pub struct Db {
    conn: Connection,
    blob_dir: PathBuf,
}

impl Db {
    pub fn open(db_path: impl AsRef<Path>, blob_dir: impl AsRef<Path>) -> Result<Self> {
        let db_path = db_path.as_ref();
        let blob_dir = blob_dir.as_ref();
        if let Some(parent) = db_path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::create_dir_all(blob_dir)?;
        let conn = Connection::open(db_path)?;
        Self::init(conn, blob_dir.to_path_buf())
    }

    pub fn open_in_memory(blob_dir: impl AsRef<Path>) -> Result<Self> {
        std::fs::create_dir_all(blob_dir.as_ref())?;
        let conn = Connection::open_in_memory()?;
        Self::init(conn, blob_dir.as_ref().to_path_buf())
    }

    fn init(mut conn: Connection, blob_dir: PathBuf) -> Result<Self> {
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA busy_timeout = 5000;
             PRAGMA foreign_keys = ON;",
        )?;
        migration::migrate(&mut conn)?;
        Ok(Db { conn, blob_dir })
    }

    pub fn connection(&self) -> &Connection {
        &self.conn
    }

    pub fn blob_dir(&self) -> &Path {
        &self.blob_dir
    }

    pub fn blob_path(&self, blob_id: &str) -> PathBuf {
        self.blob_dir
            .join(blob_id.trim_start_matches("sha256:"))
    }

    pub fn tx(&mut self) -> Result<Tx<'_>> {
        let tx = self.conn.transaction()?;
        Ok(Tx { tx })
    }
}

pub struct Tx<'a> {
    tx: rusqlite::Transaction<'a>,
}

impl<'a> Tx<'a> {
    pub fn commit(self) -> Result<()> {
        self.tx.commit()?;
        Ok(())
    }

    pub fn rollback(self) {
        let _ = self.tx.rollback();
    }
}

impl<'a> std::ops::Deref for Tx<'a> {
    type Target = Connection;

    fn deref(&self) -> &Connection {
        &self.tx
    }
}
