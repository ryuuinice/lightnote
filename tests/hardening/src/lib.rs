use lightnote_slice::{Client, Server};
use lightnote_core::util::now_ms;
use lightnote_core::outbox;

pub fn setup(names: &[&str]) -> (Server, Vec<Client>) {
    let server = Server::start();
    let clients: Vec<Client> = names.iter().map(|n| Client::new(&server, n)).collect();
    (server, clients)
}

pub fn create_note(client: &mut Client, title: &str, content: &str) -> String {
    let note = client.core.create_note("root", title, "text").expect("create note");
    client
        .core
        .save_content(&note.note_id, content)
        .expect("save content");
    note.note_id
}

pub fn read_content(client: &Client, note_id: &str) -> String {
    client
        .core
        .get_content(note_id)
        .expect("get content")
        .1
        .unwrap_or_default()
}

pub fn force_sending_stale(client: &Client) {
    let conn = client.core.db().connection();
    conn.execute(
        "UPDATE sync_outbox SET state = 'SENDING', updated_at = ? WHERE state = 'PENDING'",
        [now_ms() - 10 * 60 * 1000],
    )
    .expect("force SENDING stale");
}

pub fn outbox_rows(client: &Client) -> Vec<(String, String, i64, i64)> {
    let conn = client.core.db().connection();
    let mut stmt = conn
        .prepare("SELECT change_id, state, retry_count, next_retry_at FROM sync_outbox ORDER BY created_at")
        .expect("prepare");
    let rows = stmt
        .query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)?,
                r.get::<_, i64>(3)?,
            ))
        })
        .expect("query");
    rows.filter_map(|r| r.ok()).collect()
}

pub fn set_outbox_retry_count(client: &Client, change_id: &str, count: i64) {
    let conn = client.core.db().connection();
    conn.execute(
        "UPDATE sync_outbox SET retry_count = ? WHERE change_id = ?",
        rusqlite::params![count, change_id],
    )
    .expect("set retry count");
}

pub fn reset_retry_timers(client: &Client) {
    let conn = client.core.db().connection();
    conn.execute("UPDATE sync_outbox SET next_retry_at = 0", [])
        .expect("reset retry timers");
}

pub fn recover_stale(client: &Client) -> usize {
    let conn = client.core.db().connection();
    outbox::recover_stale_sending(conn, now_ms()).expect("recover")
}
