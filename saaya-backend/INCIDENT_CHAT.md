# Incident Chat

Chat uses existing Saaya bearer sessions and LiveKit's `authorized_incident` helper: the incident owner and current recipient-code links whose Trusted Circle contacts still exist. Phone numbers, FCM delivery, client sender IDs and notification payloads do not grant access. Ending an incident preserves read access for currently authorized accounts.

## Endpoints

- `GET /incidents/{id}/chat/messages?before=<messageId>`: bearer authenticated; latest 100 messages in chronological insertion order. `nextBefore` pages backwards; `readOnly` reports incident status. Responses have `Cache-Control: no-store`.
- `WS /incidents/{id}/chat/ws?after=<messageId>`: existing session in `Authorization: Bearer ...` handshake header, never URL credentials. Optional cursor replays missed messages in batches of 100. Without cursor, returns latest 100; HTTP history loads older messages.
- Client frame: `{"text":"On my way"}` (trimmed, 1-2000 characters; additional fields rejected).
- Server frames: `history` / `messages` with `messages` and `readOnly`; `sent` after MongoDB persistence; `error` with safe `detail` and optional `readOnly`.
- Message fields: `id`, `incidentId`, `senderId`, `text`, server `createdAt`.
- Close codes: 4401 invalid/expired session; 4404 unavailable/unauthorized incident; 1011 database unavailable; 1009 oversized frame; 1003 unsupported binary input. No private incident details in denial responses.

Each read/poll and send resolves the session and current Trusted Circle authorization again. A conditional ACTIVE incident write is the message acceptance point against concurrent incident ending; an accepted write can finish persisting while the end request completes. New sends after the end transition are rejected. MongoDB errors fail closed. The Android client clears history on authorization denial, closes sockets outside the foreground, reconnects after three seconds, and resumes using its last message cursor. No automatic resend: if an acknowledgement is lost, the client asks users to inspect recovered history before retrying.

## Render deployment

No new environment variables, Firebase setup or LiveKit configuration. Retain all existing variables. Redeploy with `pip install -r requirements.txt`; Uvicorn now requests its `standard` extra to guarantee a WebSocket protocol implementation. Existing Uvicorn start command and Render HTTPS/WSS routing can remain. Startup creates `incident_messages` index `(incidentId, _id)`; existing MongoDB credentials must permit creating indexes and reading/writing that collection. No migration or backfill.

Use one backend worker for this milestone. Delivery polls MongoDB every 500 ms per foreground chat, with session/contact checks before delivery; it does not use a broker. ObjectId replay cursors assume insertion order and are not a strict cross-worker ordering protocol. High-scale/multi-worker operation needs a durable ordered stream/cursor design. Messages persist without a TTL; use the application's existing database backup/access controls. No chat push notifications or local offline history cache are added.

## Two-phone acceptance test

1. Deploy backend and install `app/build/outputs/apk/debug/app-debug.apk` on phones A and B. Sign in to different Google accounts. On B open notification settings and get its recipient code. On A add B to Trusted Circle and link that code. Keep the saved SOS call number configured separately; do not replace it with the circle.
2. On A trigger SOS, confirm the existing cellular call fallback and incident creation still work. On B verify the existing FCM alert. Open A's incident chat from Home or the live safety screen. On B open the alert and choose Open incident chat; alternatively open the active live safety session then its chat.
3. Send distinct messages from both phones. Confirm both see each message once, in order; the sender sees You and the other account sees Trusted participant. Blank messages must be disabled. Test a long message and verify the 2000-character input limit.
4. Disable B's network. Send several messages on A. Restore B's network. Confirm reconnect status and recovered messages. Repeat with B backgrounded, then foregrounded. For pagination, exchange over 100 messages and use Load older messages. For longer reconnect testing send over 100 while B is disconnected; all should arrive in batches on resume.
5. With B's chat open, unlink B's recipient code on A (or delete its Trusted Circle contact). B should lose access, clear visible history and stop reconnecting; B must not send or reopen history. Re-link B and reopen chat to restore access.
6. End SOS on A while both chats are open. Both should display read-only and hide sending controls. Reopen A's latest chat from Home and B's original incident alert: history should remain readable while the link exists. Existing LiveKit should still end normally.
7. Sign B out and sign in as a third, unlinked account. Open the original alert or incident route: no history or sending access. Sign back into B to verify account isolation. Repeat logout while chat is open; old messages must not appear in the new account.
8. Start another SOS on A: the new incident must have an empty chat. Confirm the original ended chat retains only its own messages. Check Trusted Circle, saved SOS number, FCM and live camera/audio continue working.

## Verification

Backend tests exercise persistence, current circle authorization, strangers/missing/expired sessions, revocation on an open socket, ended read-only access, field validation, bounded pagination, two simultaneous connections, and reconnect replay beyond one page. Android JVM tests exercise authenticated history, cursor/read-only parsing, HTTP access denial, WebSocket handshake denial, session switching, history merging/incident isolation and disconnected sends. Physical two-phone, production MongoDB and Render WSS checks remain manual.
