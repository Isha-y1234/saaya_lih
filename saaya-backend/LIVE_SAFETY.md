# Live safety sessions

This feature uses the existing authenticated app sessions, incident lifecycle and
recipient-code Trusted Circle links. It does not introduce Firebase Auth or
replace SOS calling/FCM. No recording, Egress, cloud account, paid subscription,
deployment or API credentials are provisioned by this change.

## Configuration

Merge the following variable names into the existing ignored saaya-backend/.env;
use the existing approved project's actual values privately. The checked-in
.env.example has blank values, not usable credentials:

```dotenv
LIVEKIT_URL=
LIVEKIT_API_KEY=
LIVEKIT_API_SECRET=
```

LIVEKIT_URL must be the project's secure wss://<project>.livekit.cloud endpoint.
LIVEKIT_API_KEY and LIVEKIT_API_SECRET are server credentials from that same
project. Never put either server credential in Android, local.properties,
google-services.json, source control, screenshots or logs. The Android app gets
only its room-scoped, two-minute participant credential from the authenticated
backend. The join DTO does not generate a token-containing toString().

All three values absent means disabled; partial or unsupported configuration
means invalid_configuration. Both leave authentication, FCM, incident creation,
termination and cellular calling available. /health/live reports configuration
only, with deliveryVerified:false. Configured is not proof of remote credentials,
Cloud revocation behavior, video/audio delivery or network reachability.

LiveKit Cloud is required for strict cached-token revocation. Self-hosted
LiveKit does not revoke existing tokens when a participant is removed. Merely
deleting a room also does not revoke unused join credentials; such credentials
could recreate a room. This implementation intentionally rejects self-hosted
URLs instead of promising immediate revocation there. It uses the current
RoomParticipantIdentity.revoke_token_ts API with an explicit cutoff. No new
Cloud account or paid plan was created; use an existing approved project and
confirm its available quota before live tests.

In Render, open the existing service srv-davdhvnavr4c73bj2p40 > Environment:

1. Privately add LIVEKIT_URL, LIVEKIT_API_KEY and LIVEKIT_API_SECRET as environment
   variables, or verify their existing linked Environment Group values. Do not
   duplicate conflicting values; service-level values override group values.
2. Preserve MONGODB_URI, MONGODB_DATABASE, GOOGLE_WEB_CLIENT_ID, and
   FIREBASE_SERVICE_ACCOUNT_FILE=/etc/secrets/firebase-service-account.json.
   The existing Firebase Secret File remains separate and unchanged.
3. Keep root saaya-backend, build pip install -r requirements.txt, start
   uvicorn app.main:app --host 0.0.0.0 --port $PORT, and health path /health.
4. Save only if preparing settings without deploying. Commit/push/deployment
   require separate authorization; none are performed by this implementation.
5. When the reviewed revision is deployed, check /health, /health/fcm,
   /health/live and /openapi.json. Do not disclose environment values in logs.

## API and authorization

All application endpoints use the existing Bearer session dependency. Clients
cannot choose a room, role, identity, owner or participant permissions.

- GET /live-safety/incidents: active owned incidents plus active incidents whose
  owner currently has an owned contact explicitly linked to the requesting
  recipient account. No device registration or successful FCM send is required.
- GET /incidents/{id}/live: current active flag, configuration readiness and the
  server-assigned publisher/viewer role. Unauthorized/missing incidents return 404.
- POST /incidents/{id}/live/join: no body, or an empty object; other fields are
  rejected. Returns incidentId, url, token, role and expiresIn=120 with no-store
  caching. Ended incidents return 409; optional media failures return 503.
- GET /health/live: disabled, invalid_configuration or configured; no credentials.

SOS create/resume schedules safe room preparation after the response. Token
issuance also ensures the room exists, so a failed background attempt can retry.
FCM dispatch remains first and independent. The existing incident end endpoint
marks the incident ENDED before attempting media cleanup, blocks new credentials,
revokes all issued participant identities (including clients that never connected),
and deletes the room. Repeating end retries cleanup without changing endedAt.

MongoDB livekit_sessions has a unique incidentId index and stores the random room
name, creation time, OPEN/CLOSE_PENDING/CLOSED state, and closure time. The incident
also records livekitRoomName internally. livekit_participants stores opaque
identities and their authenticated user/session bindings, never participant JWTs
or server secrets. There is no caller-controlled room metadata.

Owners may publish only camera/microphone and cannot subscribe. Viewers may only
subscribe; data publication and metadata updates are disabled. A push payload,
phone number or email address alone never grants access. Existing FCM read-only
incident APIs and their authorization rules remain unchanged.

The backend reconciles persisted rooms every ten seconds, retrying failed closure
after process restart and revoking viewers whose links/contacts or login sessions
are removed/expired. Android rechecks authorization/status every five seconds and
disconnects on denial or backend failure. Revocation is not instantaneous after
unlink/logout: expect the reconciliation interval plus network latency. If the
LiveKit control plane is unavailable, remote revocation/room deletion remain
CLOSE_PENDING and retry; no distributed system can guarantee remote disconnection
through a complete control-plane outage. New backend joins are still denied for
ended incidents, and the official app stops on its next status check.

LiveKit may refresh tokens for connected participants. Short initial TTL alone
does not terminate an existing session; explicit participant revocation and room
deletion are therefore required. SDK/network retries retain current-session
authorization until removed, not permanent access to an ended incident.

## Exact two-phone procedure

Use two physical Google Play services phones with different signed-in accounts.
Install the updated debug APK on both, complete onboarding, and keep them online.
Use consenting test-call contacts, not emergency services.

1. Phone B (viewer): Home > Profile > Emergency notifications > Your recipient
   code. Long-press/select and copy the code. Tap Notification permissions to
   allow notifications; return to Saaya online so its FCM token sync can run.
2. Phone A (sender): Home > top-bar Trusted Circle person icon > Add. Fill Name,
   Relation and Phone number for B; tap Add in the dialog. Back > Profile >
   Emergency notifications. Paste B's code into Contact's recipient code and tap
   Link using this code beneath B's contact. Confirm Linked for SOS notifications.
   This enables A -> B, not the reverse.
3. Phone A: Profile > Emergency contact > Emergency contact number > Save SOS
   contact. Use a consenting test number. Home > End SOS if a test is already
   active; then large SOS > Activate SOS? > Activate. Confirm the incident and
   existing cellular call/dialler fallback still work.
4. Finish the test call and return to Saaya on Phone A. Live safety opens after
   returning from the SOS call/dialler. If it does not, Home > Share live safety
   opens the same incident (also used after app/session restoration).
   On Live safety session, tap Allow camera and microphone, granting both.
   The screen automatically joins as publisher and shows the local preview.
   Sharing is explicit and never delays the SOS call for media permissions.
5. Phone B: tap the Saaya SOS notification, then Watch live safety in SOS alert.
   Alternatively, Profile > Emergency notifications > View live safety sessions >
   Refresh > Open live safety beneath the incident. This second path works even
   without FCM delivery. The viewer joins without camera/microphone permission
   and should see/hear A's stream, with the same displayed incident ID.
6. Keep phones apart or mute device output to avoid acoustic feedback. Verify B
   has no publisher controls. A can tap Leave live session to stop sharing without
   ending SOS, then Join / Retry to resume. Going Home, opening another app,
   locking the phone or making a call stops media; returning to the live screen
   resumes if still authorized. Background camera/microphone service, screen-off
   streaming and recording are deliberately not implemented.
7. Briefly switch Wi-Fi/mobile data: verify Reconnecting/Connected. If automatic
   recovery is exhausted, leave and use Join / Retry to fetch a fresh credential.
   Deny A's permissions and verify retry/App permissions recovery. B must never
   be asked for camera/microphone access.
8. A: End SOS in the live screen or Home. B should disconnect and show that the
   incident ended on the next status check. Refresh the active list: it disappears.
   Retry cannot join the ended incident. Start a fresh SOS and verify a new room.
9. Test unlink/contact deletion and recipient logout while viewing; verify access
   ends within the reconciliation interval and cannot be regained without a
   valid current link/session. Use an unlinked account to verify 404. To test
   multiple simultaneous viewers, add a third phone/account with its own code
   and link it as another contact; two phones cannot prove multi-viewer delivery.
10. With all LIVEKIT variables blank in a development environment, repeat SOS:
    FCM and calling still work; live join gives a recoverable unavailable message.
    Repeat with a simulated SDK failure in tests; do not break production credentials.

## Validation commands and limits

Local results: 96 backend tests passed; 57 Android unit tests passed with zero
failures/errors/skips. Kotlin compilation and debug APK assembly succeeded,
including an incremental rebuild after the final SOS navigation/media error
handling changes. pip check and git diff --check passed. Existing FCM/map SDK
deprecation and native-symbol stripping warnings remain non-blocking. Official
Python client construction/cleanup also passed without making network requests.
No actual LiveKit project credentials, rooms or physical-device media were used.

```powershell
# saaya-backend, existing virtual environment
.\.venv\Scripts\python.exe -B -m pytest -q -p no:cacheprovider
.\.venv\Scripts\python.exe -m pip check
# project root, one Gradle invocation at a time
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug --max-workers=2
git diff --check
```

Backend tests fake only the external RoomService transport; token signing and
JWT grant verification use the official SDK. Android tests use a fake media
adapter to exercise permission gates, viewer/publisher roles, authorization loss,
termination, failure/retry and wrong-incident rejection. Neither establishes
physical camera/audio delivery, Cloud IAM/key validity, actual cached-token
revocation, carrier audio interaction or device-specific network recovery.

Official references:
- https://github.com/livekit/client-sdk-android/tree/v2.29.0
- https://docs.livekit.io/reference/python/livekit/api/
- https://docs.livekit.io/reference/other/roomservice-api/
- https://docs.livekit.io/frontends/reference/tokens-grants/

## Changed file manifest

```text
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/project/saaya/data/remote/LiveSafetyApiService.kt
app/src/main/java/com/project/saaya/data/repository/LiveSafetyRepository.kt
app/src/main/java/com/project/saaya/live/LiveKitMediaClient.kt
app/src/main/java/com/project/saaya/navigation/SaayaNavHost.kt
app/src/main/java/com/project/saaya/ui/screens/HomeScreen.kt
app/src/main/java/com/project/saaya/ui/screens/IncidentAlertScreen.kt
app/src/main/java/com/project/saaya/ui/screens/LiveSafetyListScreen.kt
app/src/main/java/com/project/saaya/ui/screens/LiveSafetyScreen.kt
app/src/main/java/com/project/saaya/ui/screens/NotificationSettingsScreen.kt
app/src/main/java/com/project/saaya/ui/sos/IncidentSosHost.kt
app/src/main/java/com/project/saaya/ui/state/LiveSafetyViewModel.kt
app/src/test/java/com/project/saaya/ui/state/LiveSafetyViewModelTest.kt
gradle/libs.versions.toml
saaya-backend/.env.example
saaya-backend/LIVE_SAFETY.md
saaya-backend/app/config.py
saaya-backend/app/live_safety.py
saaya-backend/app/main.py
saaya-backend/app/routes/incidents.py
saaya-backend/app/routes/live_safety.py
saaya-backend/requirements.txt
saaya-backend/tests/test_live_safety.py
settings.gradle.kts
```
