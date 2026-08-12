use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EntityType {
    Note,
    Branch,
    Attribute,
    Blob,
}

impl EntityType {
    pub fn as_str(&self) -> &'static str {
        match self {
            EntityType::Note => "note",
            EntityType::Branch => "branch",
            EntityType::Attribute => "attribute",
            EntityType::Blob => "blob",
        }
    }

    pub fn parse(s: &str) -> Option<EntityType> {
        match s {
            "note" => Some(EntityType::Note),
            "branch" => Some(EntityType::Branch),
            "attribute" => Some(EntityType::Attribute),
            "blob" => Some(EntityType::Blob),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Operation {
    Create,
    Update,
    Delete,
}

impl Operation {
    pub fn as_str(&self) -> &'static str {
        match self {
            Operation::Create => "CREATE",
            Operation::Update => "UPDATE",
            Operation::Delete => "DELETE",
        }
    }

    pub fn parse(s: &str) -> Option<Operation> {
        match s {
            "CREATE" => Some(Operation::Create),
            "UPDATE" => Some(Operation::Update),
            "DELETE" => Some(Operation::Delete),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Note {
    pub note_id: String,
    pub title: String,
    pub note_type: String,
    pub blob_id: Option<String>,
    pub is_deleted: bool,
    pub version: i64,
    pub updated_at: i64,
    pub updated_by: Option<String>,
    pub created_at: i64,
    pub conflict_of_note_id: Option<String>,
}

impl Note {
    pub fn new(note_id: String, title: String, note_type: String, now: i64) -> Self {
        Note {
            note_id,
            title,
            note_type,
            blob_id: None,
            is_deleted: false,
            version: 1,
            updated_at: now,
            updated_by: None,
            created_at: now,
            conflict_of_note_id: None,
        }
    }

    pub fn meta(&self, sort_order: i64) -> NoteMeta {
        NoteMeta {
            note_id: self.note_id.clone(),
            title: self.title.clone(),
            note_type: self.note_type.clone(),
            is_deleted: self.is_deleted,
            sort_order,
            version: self.version,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NoteMeta {
    pub note_id: String,
    pub title: String,
    pub note_type: String,
    pub is_deleted: bool,
    pub sort_order: i64,
    pub version: i64,
}

pub type TreeNode = NoteMeta;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Branch {
    pub branch_id: String,
    pub parent_note_id: String,
    pub child_note_id: String,
    pub sort_order: i64,
    pub is_deleted: bool,
    pub version: i64,
    pub updated_at: i64,
    pub updated_by: Option<String>,
    pub created_at: i64,
}

impl Branch {
    pub fn new(branch_id: String, parent_note_id: String, child_note_id: String, sort_order: i64, now: i64) -> Self {
        Branch {
            branch_id,
            parent_note_id,
            child_note_id,
            sort_order,
            is_deleted: false,
            version: 1,
            updated_at: now,
            updated_by: None,
            created_at: now,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Attribute {
    pub attribute_id: String,
    pub note_id: String,
    pub attr_type: String,
    pub name: String,
    pub value: Option<String>,
    pub is_inherited: bool,
    pub is_deleted: bool,
    pub version: i64,
    pub updated_at: i64,
    pub updated_by: Option<String>,
    pub created_at: i64,
}

impl Attribute {
    pub fn new(attribute_id: String, note_id: String, attr_type: String, name: String, value: Option<String>, now: i64) -> Self {
        Attribute {
            attribute_id,
            note_id,
            attr_type,
            name,
            value,
            is_inherited: false,
            is_deleted: false,
            version: 1,
            updated_at: now,
            updated_by: None,
            created_at: now,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Blob {
    pub blob_id: String,
    pub size: i64,
    pub mime_type: Option<String>,
    pub storage_type: String,
    pub storage_path: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Tag {
    pub name: String,
    pub note_count: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResult {
    pub note_id: String,
    pub title: String,
    pub snippet: String,
    pub matched_tags: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConflictInfo {
    pub note_id: String,
    pub conflict_of_note_id: String,
    pub title: String,
    pub version: i64,
    pub updated_at: i64,
    pub updated_by: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncStatus {
    pub state: String,
    pub last_sync_at: i64,
    pub pending_count: i64,
    pub failed_count: i64,
}
