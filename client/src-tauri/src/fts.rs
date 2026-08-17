use crate::error::Result;
use crate::models::SearchResult;
use crate::repo;
use crate::util::is_cjk;
use rusqlite::{Connection, OptionalExtension};

/// CJK 字符间插入空格，使 unicode61 将每个汉字拆为独立 token
/// "所有权是Rust" → "所 有 权 是 Rust"
fn cjk_space(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 8);
    let mut prev_cjk = false;
    for c in text.chars() {
        if is_cjk(c) {
            if prev_cjk {
                out.push(' ');
            }
            out.push(c);
            prev_cjk = true;
        } else {
            out.push(c);
            prev_cjk = false;
        }
    }
    out
}

/// FTS5 本地派生索引（title + blob 正文 + 标签名），由变更事务内同步维护
///
/// 行键：note_fts.rowid = notes.rowid。note_id 在 schema 中为 UNINDEXED，
/// 按 note_id 删除/定位会触发 FTS5 全表扫描（O(n)）；改用 notes.rowid 作行键后
/// DELETE/INSERT 均为 O(log n)，消除批量写入路径（seed / 初始 Pull / 每次 save）的 O(n²)。
/// notes 行键稳定性：note_id 为 TEXT PK（隐式 rowid），更新走 UPSERT、删除走 tombstone，
/// 物理删除仅在 trash_empty 发生（此时由 remove_note 同步清理 FTS 行）。
pub fn sync_note(conn: &Connection, note_id: &str) -> Result<()> {
    sync_note_with_content(conn, note_id, None)
}

/// content：调用方已持有正文时直传（save_content 路径），跳过从磁盘重读整个 blob；
/// None 时按 storage_path 回读（pull 应用 / 懒下载补全路径）。
pub fn sync_note_with_content(conn: &Connection, note_id: &str, content: Option<&str>) -> Result<()> {
    let rid: Option<i64> = conn
        .query_row(
            "SELECT rowid FROM notes WHERE note_id = ?1",
            rusqlite::params![note_id],
            |r| r.get(0),
        )
        .optional()?;
    let Some(rid) = rid else {
        // note 不存在：FTS 残行（如有）由 trash_empty 的 remove_note 清理；
        // search 侧 get_note 亦会跳过指向已删除 note 的残行。
        return Ok(());
    };
    conn.execute("DELETE FROM note_fts WHERE rowid = ?1", rusqlite::params![rid])?;
    let Some(note) = repo::get_note(conn, note_id)? else {
        return Ok(());
    };
    if note.is_deleted {
        return Ok(());
    }
    let content = match content {
        Some(c) => c.to_string(),
        None => raw_content(conn, &note),
    };
    let content = cjk_space(&content);
    let tags = repo::list_tag_names(conn, note_id)?;
    conn.execute(
        "INSERT INTO note_fts (rowid, note_id, title, content, tags) VALUES (?1, ?2, ?3, ?4, ?5)",
        rusqlite::params![rid, note.note_id, cjk_space(&note.title), content, tags.join(" ")],
    )?;
    Ok(())
}

/// 物理删除 note（trash_empty）前清理其 FTS 行。按 notes.rowid 定位，O(log n)。
/// 必须在 repo::delete_note_row 之前调用（依赖 notes.rowid 查询）。
pub fn remove_note(conn: &Connection, note_id: &str) -> Result<()> {
    let rid: Option<i64> = conn
        .query_row(
            "SELECT rowid FROM notes WHERE note_id = ?1",
            rusqlite::params![note_id],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(rid) = rid {
        conn.execute("DELETE FROM note_fts WHERE rowid = ?1", rusqlite::params![rid])?;
    }
    Ok(())
}

/// 损坏时重建全部 FTS 索引（派生数据，无需同步）
pub fn rebuild_all(conn: &Connection) -> Result<usize> {
    conn.execute("DELETE FROM note_fts", [])?;
    let mut stmt = conn.prepare("SELECT note_id FROM notes WHERE is_deleted = 0")?;
    let ids: Vec<String> = stmt
        .query_map([], |r| r.get::<_, String>(0))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for id in &ids {
        sync_note(conn, id)?;
    }
    Ok(ids.len())
}

fn sanitize_token(raw: &str) -> String {
    raw.chars()
        .filter(|c| c.is_alphanumeric() || *c == '_' || is_cjk(*c))
        .collect()
}

/// 用户查询 → FTS5 MATCH 表达式
/// CJK：逐字前缀 token（AND）；英文/数字：整词前缀 token
fn build_match_query(raw: &str) -> String {
    let mut tokens: Vec<String> = Vec::new();
    for part in raw.split_whitespace() {
        let sanitized = sanitize_token(part);
        if sanitized.is_empty() {
            continue;
        }
        if sanitized.chars().any(is_cjk) {
            for ch in sanitized.chars() {
                tokens.push(format!("\"{ch}\"*"));
            }
        } else {
            tokens.push(format!("\"{}\"*", sanitized));
        }
    }
    if tokens.is_empty() {
        let single = sanitize_token(raw);
        if !single.is_empty() {
            if single.chars().any(is_cjk) {
                for ch in single.chars() {
                    tokens.push(format!("\"{ch}\"*"));
                }
            } else {
                tokens.push(format!("\"{single}\"*"));
            }
        }
    }
    tokens.join(" ")
}

fn raw_tokens(raw: &str) -> Vec<String> {
    raw.split_whitespace()
        .map(|t| t.to_lowercase())
        .filter(|t| !t.is_empty())
        .collect()
}

fn tag_matches(name: &str, tokens: &[String]) -> bool {
    let lower = name.to_lowercase();
    tokens.iter().any(|t| lower.contains(t.as_str()))
}
fn raw_content(conn: &Connection, note: &crate::models::Note) -> String {
    if matches!(note.note_type.as_str(), "text" | "markdown") {
        note.blob_id
            .as_ref()
            .and_then(|bid| repo::get_blob(conn, bid).ok().flatten())
            .and_then(|b| std::fs::read(&b.storage_path).ok())
            .map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
            .unwrap_or_default()
    } else {
        String::new()
    }
}

pub fn search(conn: &Connection, query: &str, limit: usize) -> Result<Vec<SearchResult>> {
    let match_query = build_match_query(query);
    if match_query.is_empty() {
        return Ok(vec![]);
    }
    // snippet() 直接在 SQLite 内生成命中片段：省去每条结果从磁盘读整个 blob
    // + get_note 的 N+1 查询；JOIN notes 同时过滤掉 FTS 残行指向已物理删除 note 的情况
    let mut stmt = conn.prepare(
        "SELECT f.note_id, n.title, snippet(note_fts, 2, '', '', '…', 14)
         FROM note_fts f JOIN notes n ON n.note_id = f.note_id
         WHERE note_fts MATCH ?1 ORDER BY rank LIMIT ?2",
    )?;
    let rows = stmt
        .query_map(rusqlite::params![match_query, limit as i64], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let tokens = raw_tokens(query);
    let mut results = Vec::with_capacity(rows.len());
    for (note_id, title, snippet) in rows {
        let tags = repo::list_tag_names(conn, &note_id)?;
        let matched_tags: Vec<String> = tags.into_iter().filter(|t| tag_matches(t, &tokens)).collect();
        results.push(SearchResult {
            note_id,
            title,
            snippet,
            matched_tags,
        });
    }
    Ok(results)
}
