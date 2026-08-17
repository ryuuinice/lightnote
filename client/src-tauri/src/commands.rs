use crate::blob::{BlobManager, BlobTransport, DownloadQueue, DownloadReport};
use crate::change;
use crate::db::Db;
use crate::engine::{SyncEngine, SyncReport};
use crate::error::{Error, Result};
use crate::fts;
use crate::models::{
    Attribute, Blob, Branch, ConflictInfo, EntityType, Note, NoteMeta, Operation, SearchResult, SyncStatus,
    Tag,
};
use crate::outbox;
use crate::repo;
use crate::util::{blob_id_of, now_ms, uuid_v7};
use std::path::Path;

pub struct Core {
    db: Db,
    client_id: String,
    origin_device_id: String,
    last_sync_at: std::sync::Mutex<i64>,
    blob_manager: BlobManager,
    download_queue: DownloadQueue,
}

impl Core {
    pub fn open(
        db_path: impl AsRef<Path>,
        blob_dir: impl AsRef<Path>,
        client_id: impl Into<String>,
        origin_device_id: impl Into<String>,
    ) -> Result<Self> {
        let db = Db::open(db_path, blob_dir)?;
        Ok(Core::with_db(db, client_id, origin_device_id))
    }

    pub fn open_in_memory(
        blob_dir: impl AsRef<Path>,
        client_id: impl Into<String>,
        origin_device_id: impl Into<String>,
    ) -> Result<Self> {
        let db = Db::open_in_memory(blob_dir)?;
        Ok(Core::with_db(db, client_id, origin_device_id))
    }

    fn with_db(db: Db, client_id: impl Into<String>, origin_device_id: impl Into<String>) -> Self {
        let blob_dir = db.blob_dir().to_path_buf();
        Core {
            db,
            client_id: client_id.into(),
            origin_device_id: origin_device_id.into(),
            last_sync_at: std::sync::Mutex::new(0),
            blob_manager: BlobManager::new(&blob_dir),
            download_queue: DownloadQueue::new(blob_dir),
        }
    }

    pub fn client_id(&self) -> &str {
        &self.client_id
    }

    pub fn origin_device_id(&self) -> &str {
        &self.origin_device_id
    }

    pub fn db(&self) -> &Db {
        &self.db
    }

    pub fn db_mut(&mut self) -> &mut Db {
        &mut self.db
    }

    pub fn list_notes(&self, parent_note_id: Option<&str>, include_deleted: bool) -> Result<Vec<NoteMeta>> {
        repo::list_notes(self.db.connection(), parent_note_id, include_deleted)
    }

    pub fn get_note(&self, note_id: &str) -> Result<Note> {
        repo::get_note_required(self.db.connection(), note_id)
    }

    pub fn create_note(&mut self, parent_note_id: &str, title: &str, note_type: &str) -> Result<NoteMeta> {
        let now = now_ms();
        // 空 parent 归一化为 root：根列表只识别 parent='root' 或无 branch 旧数据
        let parent_note_id = if parent_note_id.is_empty() { "root" } else { parent_note_id };
        let tx = self.db.tx()?;
        let note = Note::new(uuid_v7(), title.to_string(), note_type.to_string(), now);
        repo::insert_note(&tx, &note)?;
        // 根级笔记也建 parent='root' 的 branch：树/移动/排序统一走 branch 语义，
        // move 到根不再因「无 branch」而使笔记从 UI 消失（root 分支在 list_notes 由
        // parent='root' 分支查询 + 旧数据 NOT EXISTS 兜底共同覆盖）
        let branch = Branch::new(uuid_v7(), parent_note_id.to_string(), note.note_id.clone(), 0, now);
        repo::insert_branch(&tx, &branch)?;
        let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Branch,
        entity_id: &branch.branch_id,
        operation: Operation::Create,
        base_version: 0,
        version: 1,
        payload: &change::branch_payload(&branch),
    },
)?;
        outbox::enqueue(&tx, &c.change_id, now)?;
        let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Note,
        entity_id: &note.note_id,
        operation: Operation::Create,
        base_version: 0,
        version: 1,
        payload: &change::note_payload(&note),
    },
)?;
        outbox::enqueue(&tx, &c.change_id, now)?;
        fts::sync_note(&tx, &note.note_id)?;
        tx.commit()?;
        Ok(note.meta(0))
    }

    pub fn update_note(&mut self, note_id: &str, title: &str) -> Result<NoteMeta> {
        let now = now_ms();
        let note = repo::get_note_required(self.db.connection(), note_id)?;
        let new_version = note.version + 1;
        let tx = self.db.tx()?;
        repo::update_note_title(&tx, note_id, title, new_version, now, Some(&self.origin_device_id))?;
        let mut updated = note.clone();
        updated.title = title.to_string();
        updated.version = new_version;
        updated.updated_at = now;
        updated.updated_by = Some(self.origin_device_id.clone());
        let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Note,
        entity_id: note_id,
        operation: Operation::Update,
        base_version: note.version,
        version: new_version,
        payload: &change::note_payload(&updated),
    },
)?;
        outbox::enqueue(&tx, &c.change_id, now)?;
        fts::sync_note(&tx, note_id)?;
        tx.commit()?;
        Ok(updated.meta(0))
    }

    /// 删除笔记（tombstone + DELETE Change）。
    /// 目录（note_type=folder）级联软删全部子孙：每子孙各自产生 Delete Change，
    /// payload 必须是该子孙自己的完整快照（标题/类型/blob 各归各），
    /// 否则跨设备 Pull 会把子笔记覆盖成目录的字段。
    pub fn delete_note(&mut self, note_id: &str) -> Result<()> {
        let now = now_ms();
        let note = repo::get_note_required(self.db.connection(), note_id)?;
        let descendants = if note.note_type == "folder" {
            repo::list_descendant_note_ids(self.db.connection(), note_id)?
        } else {
            Vec::new()
        };
        // 事务外预加载每个目标的完整 Note（payload 数据源）
        let mut targets: Vec<Note> = Vec::with_capacity(descendants.len() + 1);
        targets.push(note.clone());
        for id in &descendants {
            if id == note_id {
                continue; // CTE 防御：起点不重复处理
            }
            targets.push(repo::get_note_required(self.db.connection(), id)?);
        }
        let tx = self.db.tx()?;
        for target in &targets {
            let new_version = target.version + 1;
            repo::set_note_deleted(&tx, &target.note_id, true, new_version, now, Some(&self.origin_device_id))?;
            let mut updated = target.clone();
            updated.is_deleted = true;
            updated.version = new_version;
            updated.updated_at = now;
            updated.updated_by = Some(self.origin_device_id.clone());
            let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Note,
        entity_id: &target.note_id,
        operation: Operation::Delete,
        base_version: target.version,
        version: new_version,
        payload: &change::note_payload(&updated),
    },
)?;
            outbox::enqueue(&tx, &c.change_id, now)?;
            fts::sync_note(&tx, &target.note_id)?;
        }
        tx.commit()?;
        Ok(())
    }

    /// 恢复笔记。若其祖先链上存在仍处于已删除状态的目录（被级联删除），
    /// 一并恢复祖先链，避免恢复出一个「树中不可达」的幽灵笔记。
    /// 祖先的 Update payload 同样使用祖先自己的完整快照。
    pub fn restore_note(&mut self, note_id: &str) -> Result<NoteMeta> {
        let now = now_ms();
        let note = repo::get_note_required(self.db.connection(), note_id)?;
        let ancestors = repo::list_deleted_ancestors(self.db.connection(), note_id)?;
        let mut ancestor_notes: Vec<Note> = Vec::with_capacity(ancestors.len());
        for (target_id, _) in &ancestors {
            ancestor_notes.push(repo::get_note_required(self.db.connection(), target_id)?);
        }
        let tx = self.db.tx()?;
        let mut restored = note.clone();
        for target in &ancestor_notes {
            let new_version = target.version + 1;
            repo::set_note_deleted(&tx, &target.note_id, false, new_version, now, Some(&self.origin_device_id))?;
            let mut updated = target.clone();
            updated.is_deleted = false;
            updated.version = new_version;
            updated.updated_at = now;
            updated.updated_by = Some(self.origin_device_id.clone());
            let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Note,
        entity_id: &target.note_id,
        operation: Operation::Update,
        base_version: target.version,
        version: new_version,
        payload: &change::note_payload(&updated),
    },
)?;
            outbox::enqueue(&tx, &c.change_id, now)?;
            fts::sync_note(&tx, &target.note_id)?;
        }
        let new_version = note.version + 1;
        repo::set_note_deleted(&tx, note_id, false, new_version, now, Some(&self.origin_device_id))?;
        restored.is_deleted = false;
        restored.version = new_version;
        restored.updated_at = now;
        restored.updated_by = Some(self.origin_device_id.clone());
        let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Note,
        entity_id: note_id,
        operation: Operation::Update,
        base_version: note.version,
        version: new_version,
        payload: &change::note_payload(&restored),
    },
)?;
        outbox::enqueue(&tx, &c.change_id, now)?;
        fts::sync_note(&tx, note_id)?;
        tx.commit()?;
        Ok(restored.meta(0))
    }

    /// 写入新 blob（内容寻址），更新 note.blob_id，同事务产生 Change + Outbox
    pub fn save_content(&mut self, note_id: &str, content: &str) -> Result<String> {
        let now = now_ms();
        let content_bytes = content.as_bytes();
        let blob_id = blob_id_of(content_bytes);
        self.blob_manager.write_local_atomic(&blob_id, content_bytes)?;
        let note = repo::get_note_required(self.db.connection(), note_id)?;
        let tx = self.db.tx()?;
        let is_new = !repo::blob_exists(&tx, &blob_id)?;
        let blob = crate::models::Blob {
            blob_id: blob_id.clone(),
            size: content_bytes.len() as i64,
            mime_type: Some("text/markdown".to_string()),
            storage_type: "file".to_string(),
            storage_path: self.blob_manager.local_path(&blob_id).to_string_lossy().into_owned(),
            created_at: now,
        };
        repo::upsert_blob(&tx, &blob)?;
        if is_new {
            let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Blob,
        entity_id: &blob_id,
        operation: Operation::Create,
        base_version: 0,
        version: 1,
        payload: &change::blob_payload(&blob),
    },
)?;
            outbox::enqueue(&tx, &c.change_id, now)?;
        }
        repo::set_note_blob(&tx, note_id, &blob_id, note.version + 1, now, Some(&self.origin_device_id))?;
        let mut updated = note.clone();
        updated.blob_id = Some(blob_id.clone());
        updated.version += 1;
        updated.updated_at = now;
        updated.updated_by = Some(self.origin_device_id.clone());
        let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Note,
        entity_id: note_id,
        operation: Operation::Update,
        base_version: note.version,
        version: updated.version,
        payload: &change::note_payload(&updated),
    },
)?;
        outbox::enqueue(&tx, &c.change_id, now)?;
        fts::sync_note_with_content(&tx, note_id, Some(content))?;
        tx.commit()?;
        Ok(blob_id)
    }

    /// 正文读取：blob 本地缺失时 content 为 None（Lazy Download 语义）
    pub fn get_content(&self, note_id: &str) -> Result<(Option<String>, Option<String>)> {
        let note = repo::get_note_required(self.db.connection(), note_id)?;
        let Some(blob_id) = note.blob_id else {
            return Ok((None, None));
        };
        let path = self.db.blob_path(&blob_id);
        if !path.exists() {
            return Ok((Some(blob_id), None));
        }
        let content = std::fs::read(&path)
            .map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
            .unwrap_or_default();
        Ok((Some(blob_id), Some(content)))
    }

    pub fn tree_children(&self, parent_note_id: &str) -> Result<Vec<NoteMeta>> {
        repo::list_notes(self.db.connection(), Some(parent_note_id), false)
    }

    pub fn tree_move(&mut self, branch_id: &str, new_parent_note_id: &str, new_sort_order: Option<i64>) -> Result<()> {
        let now = now_ms();
        let branch = repo::get_branch_required(self.db.connection(), branch_id)?;
        // 环防护：目标父节点不得是本节点的子孙（含自身）——否则树成环，递归查询失控
        if branch.child_note_id == new_parent_note_id {
            return Err(Error::InvalidArgument("cannot move a note into itself".into()));
        }
        if repo::list_descendant_note_ids(self.db.connection(), &branch.child_note_id)?
            .iter()
            .any(|id| id == new_parent_note_id)
        {
            return Err(Error::InvalidArgument(
                "cannot move a folder into its own descendant".into(),
            ));
        }
        let new_version = branch.version + 1;
        let sort_order = new_sort_order.unwrap_or(branch.sort_order);
        let tx = self.db.tx()?;
        repo::move_branch(
            &tx,
            branch_id,
            new_parent_note_id,
            sort_order,
            new_version,
            now,
            Some(&self.origin_device_id),
        )?;
        let mut updated = branch.clone();
        updated.parent_note_id = new_parent_note_id.to_string();
        updated.sort_order = sort_order;
        updated.version = new_version;
        updated.updated_at = now;
        updated.updated_by = Some(self.origin_device_id.clone());
        let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Branch,
        entity_id: branch_id,
        operation: Operation::Update,
        base_version: branch.version,
        version: new_version,
        payload: &change::branch_payload(&updated),
    },
)?;
        outbox::enqueue(&tx, &c.change_id, now)?;
        tx.commit()?;
        Ok(())
    }

    pub fn move_note_to(&mut self, note_id: &str, new_parent_note_id: &str, new_sort_order: Option<i64>) -> Result<()> {
        match repo::find_branch_for_note(self.db.connection(), note_id)? {
            Some(branch) => self.tree_move(&branch.branch_id, new_parent_note_id, new_sort_order),
            // 历史数据：根级笔记可能无 branch（v1.1.0 前创建）。移动时补建 branch，
            // 而非报 BranchNotFound 让笔记卡死在根级。
            None => {
                let now = now_ms();
                let tx = self.db.tx()?;
                let branch = Branch::new(
                    uuid_v7(),
                    new_parent_note_id.to_string(),
                    note_id.to_string(),
                    new_sort_order.unwrap_or(0),
                    now,
                );
                repo::insert_branch(&tx, &branch)?;
                let c = change::record_change(
                    &tx,
                    &change::NewChange {
                        origin_device_id: &self.origin_device_id,
                        entity_type: EntityType::Branch,
                        entity_id: &branch.branch_id,
                        operation: Operation::Create,
                        base_version: 0,
                        version: 1,
                        payload: &change::branch_payload(&branch),
                    },
                )?;
                outbox::enqueue(&tx, &c.change_id, now)?;
                tx.commit()?;
                Ok(())
            }
        }
    }

    pub fn attach_bytes(
        &mut self,
        parent_note_id: &str,
        title: &str,
        mime_type: &str,
        data: &[u8],
    ) -> Result<NoteMeta> {
        let now = now_ms();
        let blob_id = blob_id_of(data);
        self.blob_manager.write_local_atomic(&blob_id, data)?;
        let tx = self.db.tx()?;
        let blob = Blob {
            blob_id: blob_id.clone(),
            size: data.len() as i64,
            mime_type: Some(mime_type.to_string()),
            storage_type: "file".to_string(),
            storage_path: blob_id.clone(),
            created_at: now,
        };
        repo::insert_blob(&tx, &blob)?;
        let mut note = Note::new(uuid_v7(), title.to_string(), "attachment".to_string(), now);
        note.blob_id = Some(blob_id);
        repo::insert_note(&tx, &note)?;
        if !parent_note_id.is_empty() && parent_note_id != "root" {
            let branch = Branch::new(uuid_v7(), parent_note_id.to_string(), note.note_id.clone(), 0, now);
            repo::insert_branch(&tx, &branch)?;
            let c = change::record_change(
                &tx,
                &change::NewChange {
                    origin_device_id: &self.origin_device_id,
                    entity_type: EntityType::Branch,
                    entity_id: &branch.branch_id,
                    operation: Operation::Create,
                    base_version: 0,
                    version: 1,
                    payload: &change::branch_payload(&branch),
                },
            )?;
            outbox::enqueue(&tx, &c.change_id, now)?;
        }
        let c = change::record_change(
            &tx,
            &change::NewChange {
                origin_device_id: &self.origin_device_id,
                entity_type: EntityType::Blob,
                entity_id: &blob.blob_id,
                operation: Operation::Create,
                base_version: 0,
                version: 1,
                payload: &change::blob_payload(&blob),
            },
        )?;
        outbox::enqueue(&tx, &c.change_id, now)?;
        let c = change::record_change(
            &tx,
            &change::NewChange {
                origin_device_id: &self.origin_device_id,
                entity_type: EntityType::Note,
                entity_id: &note.note_id,
                operation: Operation::Create,
                base_version: 0,
                version: 1,
                payload: &change::note_payload(&note),
            },
        )?;
        outbox::enqueue(&tx, &c.change_id, now)?;
        fts::sync_note(&tx, &note.note_id)?;
        tx.commit()?;
        let note_loaded = repo::get_note(self.db.connection(), &note.note_id)?
            .ok_or_else(|| Error::NoteNotFound(note.note_id.clone()))?;
        let meta = note_loaded.meta(0);
        Ok(meta)
    }

    pub fn search(&self, query: &str, limit: usize) -> Result<Vec<SearchResult>> {
        fts::search(self.db.connection(), query, limit)
    }

    pub fn tags_list(&self, note_id: Option<&str>) -> Result<Vec<Tag>> {
        repo::list_tags(self.db.connection(), note_id)
    }

    pub fn tags_add(&mut self, note_id: &str, name: &str, value: Option<&str>) -> Result<Attribute> {
        let now = now_ms();
        repo::get_note_required(self.db.connection(), note_id)?;
        let tx = self.db.tx()?;
        let attribute = Attribute::new(
            uuid_v7(),
            note_id.to_string(),
            "label".to_string(),
            name.to_string(),
            value.map(str::to_string),
            now,
        );
        repo::insert_attribute(&tx, &attribute)?;
        let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Attribute,
        entity_id: &attribute.attribute_id,
        operation: Operation::Create,
        base_version: 0,
        version: 1,
        payload: &change::attribute_payload(&attribute),
    },
)?;
        outbox::enqueue(&tx, &c.change_id, now)?;
        fts::sync_note(&tx, note_id)?;
        tx.commit()?;
        Ok(attribute)
    }

    pub fn tags_remove(&mut self, attribute_id: &str) -> Result<()> {
        let now = now_ms();
        let attribute = repo::get_attribute_required(self.db.connection(), attribute_id)?;
        let new_version = attribute.version + 1;
        let tx = self.db.tx()?;
        repo::set_attribute_deleted(&tx, attribute_id, new_version, now, Some(&self.origin_device_id))?;
        let mut updated = attribute.clone();
        updated.is_deleted = true;
        updated.version = new_version;
        updated.updated_at = now;
        updated.updated_by = Some(self.origin_device_id.clone());
        let c = change::record_change(
    &tx,
    &change::NewChange {
        origin_device_id: &self.origin_device_id,
        entity_type: EntityType::Attribute,
        entity_id: attribute_id,
        operation: Operation::Delete,
        base_version: attribute.version,
        version: new_version,
        payload: &change::attribute_payload(&updated),
    },
)?;
        outbox::enqueue(&tx, &c.change_id, now)?;
        fts::sync_note(&tx, &attribute.note_id)?;
        tx.commit()?;
        Ok(())
    }

    pub fn trash_list(&self) -> Result<Vec<NoteMeta>> {
        repo::list_trashed(self.db.connection())
    }

    pub fn trash_empty(&mut self) -> Result<i64> {
        let trashed = repo::list_trashed(self.db.connection())?;
        let tx = self.db.tx()?;
        let mut deleted = 0;
        for note in &trashed {
            fts::remove_note(&tx, &note.note_id)?;
            repo::delete_note_row(&tx, &note.note_id)?;
            deleted += 1;
        }
        tx.commit()?;
        let _ = self.blob_gc()?;
        Ok(deleted)
    }

    /// 本地 blob 垃圾回收：删除不再被任何笔记（含 tombstone——恢复时需要）引用、
    /// 无待推送 blob Change（尚未同步到服务端，删了即永久丢失）、不在懒下载队列的
    /// blob 文件与表行。已推送过的 blob 服务端仍有副本，将来重新引用时经懒下载取回。
    /// 文件删除在表行事务提交之后进行，失败仅留孤儿文件，不破坏一致性。
    pub fn blob_gc(&mut self) -> Result<usize> {
        let ids: Vec<String> = {
            let conn = self.db.connection();
            let mut stmt = conn.prepare(
                "SELECT b.blob_id FROM blobs b
                 WHERE NOT EXISTS (SELECT 1 FROM notes n WHERE n.blob_id = b.blob_id)
                   AND NOT EXISTS (
                       SELECT 1 FROM sync_outbox o
                       JOIN entity_changes c ON c.change_id = o.change_id
                       WHERE c.entity_type = 'blob' AND c.entity_id = b.blob_id)
                   AND NOT EXISTS (SELECT 1 FROM blob_download_queue q WHERE q.blob_id = b.blob_id)",
            )?;
            let rows = stmt
                .query_map([], |r| r.get::<_, String>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            rows
        };
        if ids.is_empty() {
            return Ok(0);
        }
        let placeholders = ids.iter().map(|_| "?").collect::<Vec<_>>().join(",");
        let sql = format!("DELETE FROM blobs WHERE blob_id IN ({placeholders})");
        let params: Vec<&dyn rusqlite::ToSql> = ids.iter().map(|s| s as &dyn rusqlite::ToSql).collect();
        let tx = self.db.tx()?;
        tx.execute(&sql, params.as_slice())?;
        tx.commit()?;
        let mut removed = 0;
        for id in &ids {
            if self.blob_manager.delete_local(id) {
                removed += 1;
            }
        }
        Ok(removed)
    }

    pub fn blobs_get(&self, blob_id: &str) -> Result<Vec<u8>> {
        if !crate::util::valid_blob_id(blob_id) {
            return Err(Error::BlobMissing(blob_id.to_string()));
        }
        let path = self.db.blob_path(blob_id);
        std::fs::read(&path).map_err(|_| Error::BlobMissing(blob_id.to_string()))
    }

    pub fn blobs_exists(&self, blob_id: &str) -> Result<bool> {
        if !crate::util::valid_blob_id(blob_id) {
            return Ok(false);
        }
        Ok(self.db.blob_path(blob_id).exists())
    }

    pub fn blobs(&self) -> &BlobManager {
        &self.blob_manager
    }

    pub fn blob_upload(&self, transport: &dyn BlobTransport, blob_id: &str, mime_type: Option<&str>) -> Result<bool> {
        self.blob_manager.upload(transport, blob_id, mime_type)
    }

    pub fn blob_download(&self, transport: &dyn BlobTransport, blob_id: &str) -> Result<()> {
        self.blob_manager.download(transport, blob_id)?;
        crate::blob::download_queue::backfill_after_download(
            self.db.connection(),
            &self.blob_manager,
            blob_id,
        )
    }

    pub fn blob_queue_enqueue_missing(&self) -> Result<usize> {
        self.download_queue.enqueue_pending(self.db.connection(), now_ms())
    }

    pub fn blob_queue_run(&self, transport: &dyn BlobTransport) -> Result<DownloadReport> {
        self.download_queue.run(self.db.connection(), transport, now_ms())
    }

    pub fn blob_queue_stats(&self) -> Result<(i64, i64)> {
        Ok((
            crate::blob::download_queue::pending_count(self.db.connection())?,
            crate::blob::download_queue::failed_count(self.db.connection())?,
        ))
    }

    /// 补传缺失 blob。返回 (成功数, 失败数)——失败不得吞掉，须浮出到同步报告。
    pub fn blob_upload_missing(&self, transport: &dyn BlobTransport) -> Result<(usize, usize)> {
        let conn = self.db.connection();
        let mut stmt = conn.prepare(
            "SELECT DISTINCT n.blob_id FROM notes n
             WHERE n.blob_id IS NOT NULL AND n.is_deleted = 0",
        )?;
        let ids: Vec<String> = stmt
            .query_map([], |r| r.get::<_, String>(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let mut uploaded = 0;
        let mut failed = 0;
        for blob_id in ids {
            match self.blob_manager.upload(transport, &blob_id, None) {
                Ok(true) => uploaded += 1,
                Ok(false) => {} // 服务端已有，跳过
                Err(_) => failed += 1,
            }
        }
        Ok((uploaded, failed))
    }

    pub fn conflicts_list(&self) -> Result<Vec<ConflictInfo>> {
        repo::list_conflicts(self.db.connection())
    }

    /// 冲突副本解决：
    /// - keep_conflict：副本标题/正文覆盖原笔记（产生 Update Change 同步），副本转入回收站
    ///   正文取副本 blob；若本地缺失（Lazy Download 未拉取），先经 transport 下载，
    ///   下载失败则报错中止 —— 绝不用空内容覆盖原笔记。
    /// - discard_conflict：副本直接转入回收站（Tombstone + DELETE Change）
    pub fn conflicts_resolve(&mut self, conflict_note_id: &str, keep_conflict: bool) -> Result<()> {
        let note = repo::get_note_required(self.db.connection(), conflict_note_id)?;
        let Some(orig_id) = note.conflict_of_note_id.clone() else {
            return Err(Error::InvalidArgument(format!(
                "note {conflict_note_id} is not a conflict copy"
            )));
        };
        if keep_conflict {
            if let Some(blob_id) = note.blob_id.as_deref() {
                if !self.blob_manager.has_local(blob_id) {
                    return Err(Error::InvalidArgument(format!(
                        "conflict blob {blob_id} not downloaded yet; run sync first"
                    )));
                }
                let content = self.get_content(conflict_note_id)?.1.unwrap_or_default();
                self.update_note(&orig_id, &note.title)?;
                self.save_content(&orig_id, &content)?;
            } else {
                self.update_note(&orig_id, &note.title)?;
            }
        }
        self.delete_note(conflict_note_id)
    }

    /// FTS 损坏时重建
    pub fn fts_rebuild(&self) -> Result<usize> {
        fts::rebuild_all(self.db.connection())
    }

    pub fn sync_status(&self) -> Result<SyncStatus> {
        let pending_count = outbox::pending_count(self.db.connection())?;
        let failed_count = outbox::failed_count(self.db.connection())?;
        let last_sync_at = *self.last_sync_at.lock().expect("last_sync_at poisoned");
        Ok(SyncStatus {
            state: "idle".to_string(),
            last_sync_at,
            pending_count,
            failed_count,
        })
    }

    pub fn sync_trigger(&mut self, engine: &SyncEngine) -> Result<SyncReport> {
        let report = engine.sync_once(&mut self.db)?;
        if let Ok(mut last) = self.last_sync_at.lock() {
            *last = now_ms();
        }
        Ok(report)
    }

    pub fn sync_trigger_with_blob(
        &mut self,
        engine: &SyncEngine,
        blob_transport: &dyn BlobTransport,
    ) -> Result<SyncReport> {
        let report = engine.sync_once(&mut self.db)?;
        let (uploaded, upload_failed) = self.blob_upload_missing(blob_transport)?;
        let queued = self.blob_queue_enqueue_missing()?;
        let download_failed = match self.blob_queue_run(blob_transport) {
            Ok(dl) => dl.failed,
            Err(_) => {
                // 队列级故障（DB/传输层初始化失败）计为全部待下载数失败
                queued
            }
        };
        let report = SyncReport {
            blob_queued: queued,
            blob_upload_failed: upload_failed,
            blob_download_failed: download_failed,
            ..report
        };
        let _ = uploaded;
        if let Ok(mut last) = self.last_sync_at.lock() {
            *last = now_ms();
        }
        Ok(report)
    }
}
