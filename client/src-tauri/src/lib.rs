#![forbid(unsafe_code)]

pub mod apply;
pub mod blob;
pub mod change;
pub mod commands;
pub mod cursor;
pub mod db;
pub mod engine;
pub mod error;
pub mod fts;
pub mod migration;
pub mod models;
pub mod outbox;
pub mod repo;
pub mod sync;
pub mod util;

pub use blob::{BlobManager, BlobTransport, DownloadQueue, DownloadReport, InitResult, UreqBlobTransport};
pub use error::{Error, Result};
pub use models::{
    Attribute, Blob, Branch, ConflictInfo, EntityType, Note, NoteMeta, Operation, SearchResult,
    SyncStatus, Tag, TreeNode,
};
pub use sync::{PullChange, PushResult, PushStatus, SyncTransport, UreqTransport};

#[cfg(test)]
mod tests;
