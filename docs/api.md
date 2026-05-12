# LightNote API

Base path: `/api`

## Response Envelope

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

## Health

`GET /api/health`

Returns service status. This endpoint does not require authentication.

## Auth

`POST /api/auth/login`

Request:

```json
{
  "username": "admin",
  "password": "your-password"
}
```

Response:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "jwt-token",
    "expireSeconds": 7200
  }
}
```

Use the token on protected endpoints:

```text
Authorization: Bearer jwt-token
```

## Notes

All notes endpoints require JWT authentication.

### List Notes

`GET /api/notes`

Response:

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "noteUuid": "uuid",
      "title": "Linux 常用命令",
      "content": "systemctl status nginx",
      "contentFormat": "MARKDOWN",
      "summary": "systemctl status nginx",
      "categoryName": "Linux",
      "pinned": false,
      "favorite": false,
      "archived": false,
      "deleted": false,
      "objectVersion": 1,
      "serverVersion": 1,
      "createTime": "2026-05-07T20:00:00",
      "updateTime": "2026-05-07T20:00:00",
      "deleteTime": null
    }
  ]
}
```

### Create Note

`POST /api/notes`

Request:

```json
{
  "title": "Linux 常用命令",
  "content": "systemctl status nginx",
  "contentFormat": "MARKDOWN",
  "summary": "systemctl status nginx",
  "categoryName": "Linux",
  "pinned": false,
  "favorite": false,
  "archived": false
}
```

### Update Note

`PUT /api/notes/{noteUuid}`

Request:

```json
{
  "baseObjectVersion": 1,
  "title": "Linux 常用命令",
  "content": "systemctl restart nginx",
  "contentFormat": "MARKDOWN",
  "summary": "systemctl restart nginx",
  "categoryName": "Linux",
  "pinned": true,
  "favorite": false,
  "archived": false
}
```

If `baseObjectVersion` is older than the server note version, the API returns a business conflict error.

### Delete Note

`DELETE /api/notes/{noteUuid}`

Performs a soft delete and writes a sync log entry.

## Sync

All sync endpoints require JWT authentication.

### Push Local Changes

`POST /api/sync/push`

Request:

```json
{
  "lastSyncVersion": 100,
  "notes": [
    {
      "noteUuid": "uuid-1",
      "operation": "UPDATE",
      "baseObjectVersion": 3,
      "title": "MySQL 慢查询排查",
      "content": "Markdown 内容",
      "contentFormat": "MARKDOWN",
      "summary": "Markdown 内容",
      "categoryName": "数据库",
      "pinned": false,
      "favorite": true,
      "archived": false,
      "deleted": false,
      "clientUpdateTime": "2026-05-07T20:30:00"
    }
  ]
}
```

Response:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "serverVersion": 120,
    "successItems": [
      {
        "noteUuid": "uuid-1",
        "objectVersion": 4,
        "serverVersion": 120
      }
    ],
    "conflictItems": []
  }
}
```

Supported operations:

- `CREATE`
- `UPDATE`
- `DELETE`

Conflict rule:

```text
client.baseObjectVersion < server.objectVersion
```

When a conflict occurs, the item is returned in `conflictItems` and the server does not overwrite the existing note.

### Pull Remote Changes

`GET /api/sync/changes?sinceVersion=100&limit=200`

Response:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "serverVersion": 120,
    "hasMore": false,
    "notes": [
      {
        "noteUuid": "uuid-2",
        "operation": "UPDATE",
        "objectVersion": 5,
        "serverVersion": 118,
        "title": "Linux 常用命令",
        "content": "systemctl status nginx",
        "contentFormat": "MARKDOWN",
        "summary": "systemctl status nginx",
        "categoryName": "Linux",
        "pinned": false,
        "favorite": false,
        "archived": false,
        "deleted": false,
        "createTime": "2026-05-07T10:00:00",
        "updateTime": "2026-05-07T16:40:00",
        "deleteTime": null
      }
    ]
  }
}
```

The client should apply returned notes in ascending `serverVersion` order and then store the returned top-level `serverVersion` as `last_sync_version`.
