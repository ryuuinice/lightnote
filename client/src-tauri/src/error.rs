use std::fmt;

#[derive(Debug)]
pub enum Error {
    Database(rusqlite::Error),
    Json(serde_json::Error),
    Io(std::io::Error),
    NoteNotFound(String),
    BranchNotFound(String),
    AttributeNotFound(String),
    BlobMissing(String),
    InvalidArgument(String),
    NotAuthenticated,
    Sync(String),
}

impl Error {
    pub fn code(&self) -> &'static str {
        match self {
            Error::Database(_) | Error::Json(_) | Error::Io(_) => "DATABASE_ERROR",
            Error::NoteNotFound(_) => "NOTE_NOT_FOUND",
            Error::BranchNotFound(_) => "BRANCH_NOT_FOUND",
            Error::AttributeNotFound(_) => "ATTRIBUTE_NOT_FOUND",
            Error::BlobMissing(_) => "BLOB_MISSING",
            Error::InvalidArgument(_) => "INVALID_ARGUMENT",
            Error::NotAuthenticated => "NOT_AUTHENTICATED",
            Error::Sync(_) => "SYNC_ERROR",
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Database(e) => write!(f, "database error: {e}"),
            Error::Json(e) => write!(f, "json error: {e}"),
            Error::Io(e) => write!(f, "io error: {e}"),
            Error::NoteNotFound(id) => write!(f, "note not found: {id}"),
            Error::BranchNotFound(id) => write!(f, "branch not found: {id}"),
            Error::AttributeNotFound(id) => write!(f, "attribute not found: {id}"),
            Error::BlobMissing(id) => write!(f, "blob missing: {id}"),
            Error::InvalidArgument(msg) => write!(f, "invalid argument: {msg}"),
            Error::NotAuthenticated => write!(f, "not authenticated"),
            Error::Sync(msg) => write!(f, "sync error: {msg}"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Database(e) => Some(e),
            Error::Json(e) => Some(e),
            Error::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<rusqlite::Error> for Error {
    fn from(e: rusqlite::Error) -> Self {
        Error::Database(e)
    }
}

impl From<serde_json::Error> for Error {
    fn from(e: serde_json::Error) -> Self {
        Error::Json(e)
    }
}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(e)
    }
}

impl From<ureq::Error> for Error {
    fn from(e: ureq::Error) -> Self {
        Error::Sync(e.to_string())
    }
}

pub type Result<T> = std::result::Result<T, Error>;
