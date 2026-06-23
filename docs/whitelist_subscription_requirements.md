# Whitelist Subscription Requirements

## Goal

Allow parents to manage website whitelist entries on a PC, publish a subscription file on a server, and let the Android client import those websites into the existing kiosk home screen.

The client must keep the current local whitelist workflow. Subscribed websites are an additional source, not a replacement for manually maintained websites.

## Scope

- Add one whitelist subscription URL in the admin console.
- Support manual refresh from the admin console.
- Support automatic refresh with a configurable interval.
- Render subscribed websites through the existing `web_apps` Room table and the existing home screen grid.
- Preserve manually added websites and built-in preset websites when refreshing a subscription.
- Record the last refresh time, imported item count, and last error for parent-visible feedback.

Not in scope for this phase:

- Account login or a hosted management service.
- Multiple subscription sources.
- Per-device merge conflict UI.
- Background refresh while the Android app process is not running.

## Subscription URL Rules

- The subscription URL must use HTTPS.
- The client sends a normal GET request with a child-kiosk user agent.
- Redirects may be followed by the platform HTTP stack, but the final content must be JSON.
- The file size limit is 512 KB for this phase.
- If download or parsing fails, the previous subscribed websites remain available.

## Subscription File Format

The subscription file is UTF-8 JSON:

```json
{
  "version": 1,
  "title": "家庭白名单",
  "updatedAt": "2026-06-23T10:00:00+08:00",
  "apps": [
    {
      "id": "scratch",
      "title": "Scratch",
      "url": "https://scratch.mit.edu/",
      "category": "GAME",
      "icon": "https://scratch.mit.edu/favicon.png",
      "enabled": true
    }
  ]
}
```

Required fields:

- `version`: currently must be `1`.
- `apps`: array of website entries.
- `apps[].title`: display name.
- `apps[].url`: `http://` or `https://` website URL.

Optional fields:

- `title`: human-readable subscription name.
- `updatedAt`: server-side update time for display/debugging.
- `apps[].id`: stable item ID. If absent, the client uses the normalized URL as the stable key.
- `apps[].category`: one of `GAME`, `VIDEO`, `BOOK`, `STUDY`, `TOOL`, `OTHER`; invalid or missing values become `OTHER`.
- `apps[].icon`: built-in icon key or HTTP/HTTPS icon URL. Missing values fall back to a built-in icon.
- `apps[].enabled`: initial enabled state. Default is `true`.

Compatibility rules:

- Unknown top-level fields and unknown app fields are ignored.
- Invalid app entries are skipped; one invalid entry must not reject the whole subscription.
- Duplicate entries inside the subscription are de-duplicated by stable item ID, then normalized URL.
- If a subscribed URL already exists as a local or preset website, the local/preset website wins and the subscribed duplicate is skipped.

## Refresh Behavior

Manual refresh:

- Runs immediately when the parent taps refresh.
- Shows success or error feedback.
- On success, replaces all previously subscribed website rows with the latest parsed subscription rows.

Automatic refresh:

- Can be enabled or disabled.
- Interval is configured in hours.
- Supported interval range is 1 to 168 hours.
- The app checks whether refresh is due while the main app process is running, including app startup/admin entry.
- Automatic refresh never blocks the home screen or web browsing.

Failure behavior:

- Download, HTTP, JSON, or validation failure records `lastError`.
- The previous successfully imported subscribed websites stay in the database.
- The UI shows the last error and keeps the manual refresh button available.

## Data Ownership

Subscribed rows in `web_apps` must be marked with source metadata:

- `source_type = SUBSCRIPTION`
- `source_id = WHITELIST_SUBSCRIPTION`
- `source_item_id = apps[].id` or normalized URL

Manual rows are not deleted by subscription refresh. Built-in preset rows are not deleted by subscription refresh.

If the parent toggles a subscribed website on/off locally, that local enabled state is preserved across later refreshes for the same `source_item_id` or URL. The server-provided `enabled` value is used only when the item is first imported.
