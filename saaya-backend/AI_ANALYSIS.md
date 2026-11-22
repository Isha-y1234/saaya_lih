# Cloud-hosted incident analysis (review before deployment)

No deployment or push has been performed. No original B2 objects or bucket settings are changed.

## Components and operation

- Android's Evidence screen offers explicit consent, notes and analysis of one selected original at a time. CameraX currently captures SD recordings limited to 30 seconds/45 MiB. Android uses MediaMetadataRetriever for up to 4 sequential JPEG samples (default 3, 640px longest edge) and MediaExtractor/MediaCodec for audio. A PCM16 mono 16kHz WAV writer bounds audio to 30 seconds/960,044 bytes. It does not pass AAC/M4A directly to Whisper. Native decoding/resampling can lose detail; transcription is never guaranteed.
- Existing originals remain unchanged in `saaya-evidence`. A selected original may be downloaded onto the phone for preprocessing, with a declared-size cap. No original or derived media download, codec, AI model or FFmpeg runs on Render. Private no-backup phone directories hold retry assets; insufficient space, memory or decoder availability leaves original B2 evidence intact. Unavailable audio permits a visual-only report.
- Derived assets use a separate `analysis/` object prefix in the same bucket, the existing presigned PUT mechanism, HEAD-only confirmation and pinned B2 version IDs. Their SHA-256 digest is checked in Cloudflare before inference. The Worker accepts only signed HTTPS URLs on the explicitly configured B2 host and analysis prefix. Signed URLs expire using the existing 30–300 second setting (default 120 seconds). Render generates a fresh URL per attempted stage. No public URLs, B2 ACL or bucket changes are introduced.
- Render creates consented MongoDB jobs, freezes bounded chat/notes, reserves quota and advances one cloud stage per authenticated request. Use **Continue analysis** until complete, or return and refresh after interruption. There is no always-on scheduler: closing the app pauses progress between stages. Any currently authorized participant can continue a saved job. Nothing requires the developer's PC.
- Jobs are unique by incident + payload fingerprint, and by incident + request ID. Stages checkpoint `PENDING -> INFLIGHT -> COMPLETE/UNAVAILABLE`. A 90-second lease prevents concurrent execution; an interrupted INFLIGHT stage becomes unavailable rather than being billed again. Completed stages never run again. Unknown network outcomes are never automatically retried. Explicit provider capacity rejection may retry after a cooldown; daily-limit rejection pauses account usage until UTC reset. Mongo failure during an in-flight request retains its reservation and ambiguous checkpoint. Partial reports disclose missing coverage.
- Every API request reuses existing authentication and `authorized_incident`, which checks current Trusted Circle links. Uploads require incident ownership, including for ended incidents' existing evidence. Reports have no-store responses. Consent applies to the selected evidence, frozen recent chat (up to 30 messages) and notes. This implementation does not create a separate incident-notes data store.
- Reports distinguish AI visual observations/transcripts, participant statements and interpretations. Device capture times plus per-recording offsets order the timeline. Native frame offsets are requested sampling positions, not guaranteed exact presentation timestamps. Notes are untimed participant statements. Speech word offsets, when returned, remain relative to the audio chunk. AI confidence is labelled unverified; numeric probabilities are not invented. Model JSON is constrained to observations, interpretations, uncertainties and references; invalid JSON yields unavailable output. Prompts prohibit identification, accusations, legal certainty and SOS actions. Model instructions cannot guarantee factual correctness: reports require human review and are never used to trigger product actions.

## Verified official documentation, 2026-11-05

| Model | Published input contract | Published Neuron pricing |
| --- | --- | --- |
| `@cf/meta/llama-3.2-11b-vision-instruct` | `prompt` + image byte array; `max_tokens`; 128k context; Meta agreement required | 4,410 / million input tokens; 61,493 / million output tokens |
| `@cf/openai/whisper` | Binary audio or `{audio: number[]}` with unsigned-byte values; multilingual transcription | 41.14 / audio minute |
| `@cf/meta/llama-3.2-3b-instruct` | Messages/prompt, bounded generated tokens; 80k context | 4,625 / million input tokens; 30,475 / million output tokens |

Sources: [vision](https://developers.cloudflare.com/workers-ai/models/llama-3.2-11b-vision-instruct/), [Whisper](https://developers.cloudflare.com/workers-ai/models/whisper/), [text](https://developers.cloudflare.com/workers-ai/models/llama-3.2-3b-instruct/), [pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/), [limits](https://developers.cloudflare.com/workers-ai/platform/limits/), [errors](https://developers.cloudflare.com/workers-ai/platform/errors/).

Workers Free provides 10,000 Neurons/day, reset at 00:00 UTC. Published task limits are 300 requests/minute for text generation and 720/minute for speech recognition. Error 3036 is daily allowance exhaustion; 3040 is temporary capacity rejection; 5035 means a paid plan is required, which this integration refuses. Selected model IDs are fixed in both services; there is no paid provider or fallback model.

**Verification limitation:** The exact requested Whisper model's current published schema does not specify an exhaustive codec/MIME matrix, sample-rate limits, file-size maximum or wall timeout. Cloudflare's official provider WAV example targets `whisper-large-v3-turbo`, which is a different model: it is not evidence that WAV works on `@cf/openai/whisper`. Accordingly, audio extraction/inference is OFF unless both explicit verification gates below are enabled after a non-sensitive test on the exact model. No assumption of AAC/M4A/MP3/WAV compatibility is made in the enabled default flow. Image model documentation likewise does not specify a maximum image dimension or per-model request-byte/time ceiling. The application applies its own smaller bounds; these are not claimed as provider limits. Before enabling production, verify current account model schemas and run representative non-sensitive 640px JPEG and PCM WAV fixtures. No live inference was run as part of the local tests.

Application limits: 1,000,000 bytes/asset (WAV specifically 46–960,044 bytes), 4 frames + 1 audio chunk/job, 24KB Worker task body, 16,000-character report context, 256 vision output tokens, 1,024 report output tokens, 15-second media fetch timeout, 45-second AI REST timeout, 65-second Render HTTP read timeout. Whisper uses raw encoded WAV bytes and `Content-Type: audio/wav`; it does not JSON-serialize bytes or convert them to text. Actual Cloudflare acceptance of this exact MIME/codec/request combination still requires the real verification below. Vision uses an image byte array. These are bounded Cloudflare requests, never Render media payloads. These timeouts represent uncertain outcomes rather than proof that the provider cancelled inference.

## Quotas and usage

Mongo `analysis_usage` is keyed by Cloudflare account + UTC date. A single-document compare-and-swap reserves each job-stage once per day under both account and incident-owner ceilings. Default account cap is 8,000 Neurons, clamped to at most 9,000; default per-owner cap is 3,500. Conservative reservations: 700/frame, 100/audio chunk, 1,200/report. At the bounded inputs these intentionally exceed normal token/minute estimates. Reservations are never refunded after an uncertain call. Provider token usage is retained on stage records when returned; it is not mislabeled as actual billed Neurons. Account dashboard usage is authoritative; API schemas do not promise actual Neurons in each inference response. The free account provider cap is the ultimate billing guard, including usage by other applications. Keep other applications off this account or budget for their usage separately. A configured budget is a conservative local ledger, not an assertion of exact image tokenization or account-wide usage visibility.

Analysis mutations have a persisted 12 requests/user/minute limit. Original SOS/chat/evidence endpoints retain existing behavior. No application code can subscribe to Workers Paid, purchase credits, or enable unified billing.

## Manual setup after code review

1. Rotate the token shared in chat. Use an account-scoped Workers AI token with minimal inference permission. Never put it in Gradle, Android, source, deployment command arguments, responses, HTTP debug logs or screenshots.
2. Verify the account is **Workers Free**, with no Workers Paid subscription, unified billing, prepaid credits or paid fallback. Keep it Free. Do not enable this feature on a paid account: environment confirmations are operator attestations, not an API proof of billing status.
3. Review and accept Meta's license/acceptable-use terms through Cloudflare's documented initial `{ "prompt": "agree" }` request. Do not automate agreement without reviewing the terms.
4. Review `cloudflare-analysis/worker.mjs` and its Wrangler configuration. A future approved deployment uses a small Workers Free service, not a Render service or local worker. Set Worker secret `AI_WORKER_SECRET` to a cryptographically random value >=32 characters. Set `B2_HOST` to the hostname of the existing B2 regional endpoint (e.g. `s3.<region>.backblazeb2.com`), and `FREE_PLAN_CONFIRMED=true` only after step 2. Do not enable invocation logs, body/header tracing or AI Gateway prompt logging. Wrangler observability is disabled. Do not alter B2 configuration.
5. Set Render server-only environment values: `CLOUDFLARE_ACCOUNT_ID`, rotated `CLOUDFLARE_API_TOKEN`, `AI_WORKER_URL` (HTTPS workers.dev endpoint), matching `AI_WORKER_SECRET`, and `AI_FREE_PLAN_CONFIRMED=true`. Preserve all existing authentication, MongoDB, B2, LiveKit and Firebase settings. No new Render service, compute tier or media dependency is required.
6. Optional Render limits: `AI_DAILY_NEURONS=8000`, `AI_USER_DAILY_NEURONS=3500`, `AI_MAX_FRAMES=3`. Keep a margin for other account usage. Mongo indexes initialize on application startup; use existing database permissions. Retain daily usage ledgers for audit; rate/usage records are metadata and no media are stored in Mongo.
7. Follow the explicit verification-only procedure below using a short, non-sensitive **mono PCM16 16kHz RIFF WAV** fixture against `@cf/openai/whisper`, sending complete binary WAV with `Content-Type: audio/wav`. Validate returned text against its known speech; HTTP success alone is insufficient. Verify Android-produced WAV on representative devices too. Do not change production verification flags until these real tests pass and activation is reviewed.
8. After deployment is separately approved, smoke-test consent, revoked contact access, partial/no-audio reporting, replayed requests, exhausted quotas and resumable jobs. The APK uses the existing Render API URL, so new analysis endpoints will remain unavailable until backend and Worker deployment is reviewed and completed. Local build/tests do not establish live provider or physical-device media compatibility.

Do not deploy or push until reviewed. No secrets have been added to the repository.

## Audio + Reporting & Legal Resources milestone, 2026-11-06

All work in this milestone is local. No live environment variables or verification gates were changed. No live Cloudflare or physical-device tests were performed by this implementation task. Original Whisper remains the sole audio model; no Turbo switch or paid fallback is added.

### Exact server configuration

| Render variable | Secret? | Initial setting / purpose |
| --- | --- | --- |
| `CLOUDFLARE_ACCOUNT_ID` | No | Intended Cloudflare account ID; server-side configuration |
| `CLOUDFLARE_API_TOKEN` | **Yes** | Rotated account-scoped Workers AI inference token (Account → Workers AI → Read); never paste into chat |
| `AI_WORKER_URL` | No | Exact HTTPS `workers.dev` URL of approved Worker |
| `AI_WORKER_SECRET` | **Yes** | Cryptographically random >=32-character secret matching Worker |
| `AI_FREE_PLAN_CONFIRMED` | No | **`false`** until actual Workers Free plan is confirmed and activation approved |
| `AI_DAILY_NEURONS` | No | `8000` recommended; code clamps maximum to `9000` |
| `AI_USER_DAILY_NEURONS` | No | `3500` recommended; incident-owner budget |
| `AI_MAX_FRAMES` | No | `3` recommended (1–4 allowed) |
| `AI_WHISPER_WAV_VERIFIED` | No | **`false`** until exact-model real verification passes |
| `AI_ALLOW_AUDIO_VERIFICATION` | No | **`false`**; permits only isolated owner-consented test jobs when explicitly enabled for approved testing |

| Worker setting | Type | Initial setting |
| --- | --- | --- |
| `AI_WORKER_SECRET` | **Secret** | Same random secret as Render |
| `B2_HOST` | Plain variable | Existing B2 endpoint hostname only: `s3.<existing-region>.backblazeb2.com` |
| `FREE_PLAN_CONFIRMED` | Plain variable | **`false`** initially |
| `WHISPER_WAV_VERIFIED` | Plain variable | **`false`** until real verification passes |
| `ALLOW_AUDIO_VERIFICATION` | Plain variable | **`false`** initially; only explicit `verificationOnly` tasks can use this test exception |

The Worker reads Cloudflare credentials from Render's server-to-server `X-Cf-Token` and `X-Cf-Account` headers, not Worker environment secrets. It authenticates Render using the shared secret. Do not add Cloudflare keys to Android. Do not enable body/header tracing, Worker invocation logs, AI Gateway logging or automatic account upgrades. No new reporting/resource server keys are needed.

Preserve `MONGODB_URI`, `MONGODB_DATABASE`, `GOOGLE_WEB_CLIENT_ID`, `FIREBASE_SERVICE_ACCOUNT_FILE` and its secret file, `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, and all existing B2 values. Mongo initializes a new incident-report index automatically; the existing Mongo user needs create-index/read/write permission for the new metadata collections. No media or PDFs are stored in Mongo.

### B2 requirements — no bucket changes

Keep `B2_BUCKET=saaya-evidence`, `B2_ENDPOINT_URL`, `B2_REGION`, `B2_APPLICATION_KEY_ID`, `B2_APPLICATION_KEY`, `EVIDENCE_URL_TTL_SECONDS`, `EVIDENCE_PHOTO_MAX_BYTES` and `EVIDENCE_VIDEO_MAX_BYTES` unchanged. The two application key values are secrets. Existing credentials must permit PUT, HEAD and version-specific GET for `analysis/` as well as the existing `evidence/` prefix. A prefix-restricted key may need separately reviewed credential scope; do not change bucket publicity, ACLs, lifecycle or storage provider. No browser CORS change is needed for native Android/Worker transfers.

Audio confirmation uses HEAD metadata and pins the returned non-null `VersionId`. Worker checks that `versionId` in the signed URL matches the metadata **and** that B2's `x-amz-version-id` response matches, then verifies SHA-256 over the fetched bytes. Digest or version mismatches cannot invoke inference. Signed URLs remain short-lived (default 120 seconds, existing allowed range 30–300). The original WAV sample is preserved on the phone and its pinned uploaded version is not overwritten by retries.

Official B2 response-header reference: https://www.backblaze.com/apidocs/s3-get-object

### Exact future deployment/configuration order

These commands are documentation only. Running `deploy` or `secret put` changes a live Worker; do not run them until deployment is separately approved. Render configuration saves may restart/redeploy the service; do not change live settings during review.

1. Rotate the previously exposed token securely. Confirm Workers **Free**, no Paid subscription, prepaid credits, unified billing or paid fallback. Use separate deployment authentication; do not broaden the inference token. Review Meta's license before its documented agreement request.
2. After approval, authenticate Wrangler to the intended account and create an inert Worker, replacing the hostname placeholder with the existing B2 hostname:

```powershell
Set-Location 'C:\Users\Charan\AndroidStudioProjects\NariKavach\cloudflare-analysis'
npx wrangler@4 login
npx wrangler@4 whoami
npx wrangler@4 deploy --config wrangler.jsonc --var "B2_HOST:s3.<existing-region>.backblazeb2.com" --var "FREE_PLAN_CONFIRMED:false" --var "WHISPER_WAV_VERIFIED:false" --var "ALLOW_AUDIO_VERIFICATION:false"
npx wrangler@4 secret put AI_WORKER_SECRET --config wrangler.jsonc
```

Enter the secret only at the interactive prompt. `secret put` itself creates a deployment. Never put secret values in command arguments, console output, source or chat.

3. Prepare the Render values above, keeping all three Render feature/verification flags false initially. Deploy only reviewed backend code to the **existing** Render service after separate approval. No FFmpeg, media processing, extra compute tier, additional Render service, R2 or Firebase Storage is required.
4. For an approved isolated real-device verification window, keep production audio false and explicitly permit verification-only samples:

```powershell
npx wrangler@4 deploy --config wrangler.jsonc --var "B2_HOST:s3.<existing-region>.backblazeb2.com" --var "FREE_PLAN_CONFIRMED:true" --var "WHISPER_WAV_VERIFIED:false" --var "ALLOW_AUDIO_VERIFICATION:true"
```

Corresponding Render values: `AI_FREE_PLAN_CONFIRMED=true`, `AI_ALLOW_AUDIO_VERIFICATION=true`, **`AI_WHISPER_WAV_VERIFIED=false`**. Only a single standalone verification audio asset can form a verification job; an incident frame/video asset cannot masquerade as one. Verification jobs contain no incident chat, use the same quota and checkpoint rules, and are excluded from Incident Reports. These settings do not enable production incident audio.
5. Complete the procedure below and review real results. If unsuccessful, keep production audio false and close the verification window by setting both verification-allow flags false. Never set verification-pass flags just to get a test to run.
6. Only after successful exact-model tests and explicit activation approval, enable production audio and close the test exception:

```powershell
npx wrangler@4 deploy --config wrangler.jsonc --var "B2_HOST:s3.<existing-region>.backblazeb2.com" --var "FREE_PLAN_CONFIRMED:true" --var "WHISPER_WAV_VERIFIED:true" --var "ALLOW_AUDIO_VERIFICATION:false"
```

Then set Render `AI_ALLOW_AUDIO_VERIFICATION=false` and `AI_WHISPER_WAV_VERIFIED=true` last. These are **future manual steps**, not changes made by this task. Repeat non-secret variables on subsequent Worker deployments, because checked-in defaults are disabled. No Render deployment command is executed or authorized by this document.

### Android-native WAV verification procedure

No new Android credential configuration is needed. Existing `RECORD_AUDIO` permission is requested only when Record is selected, and recording requires explicit consent. Android 24+ is supported. Keep existing Google OAuth/Firebase/Maps configuration intact. The debug APK uses the existing Render API base URL, so new cloud endpoints cannot work until reviewed deployment occurs.

1. On a real Android phone, sign into the incident owner account and open an active incident's Evidence screen → **Whisper audio verification**. Do not use a real emergency or sensitive conversation.
2. Read the consent statement and check it. Tap **Record local WAV sample**; grant microphone permission only then. Speak clearly for about 3–5 seconds. English sample: “This is a Saaya audio test. The sky is blue.” Hindi sample: “यह साया का ऑडियो परीक्षण है। आज मौसम अच्छा है।” Record separate samples.
3. Tap Stop, then **Play / review**. Let playback finish; submission becomes available only after local review. Confirm the voice and language match. Nothing uploads automatically. Recorder stops on leaving/backgrounding the screen; no foreground/background recording service is introduced. At most five local samples are retained per incident; app-private originals remain on the phone.
4. Generated files are canonical 44-byte-header RIFF/WAVE, PCM format 1, mono, 16kHz, 16 bits, block alignment 2, byte rate 32000. Validate RIFF/data length against actual file size, even sample alignment, nonempty data and <=960,000 PCM bytes/30 seconds. Header-only, malformed, oversized or digital/near-zero silent samples are rejected before submission/inference. Silence checks do not establish speech presence and are not confidence scores.
5. During the approved verification window only, tap **Submit selected sample for verification**. The unchanged WAV uploads to private B2, is confirmed/pinned, and creates an idempotent verification job. Refresh analyses and tap Continue analysis. Worker retrieves that version, verifies version/digest/WAV format, then forwards exact raw bytes with `Content-Type: audio/wav` to the original model endpoint. There is no JSON audio wrapping, string conversion, Turbo switch or alternate paid API.
6. Real success requires a successful Cloudflare response **and nonempty, meaningful text matching what was actually spoken**, for both English and Hindi. Compare the actual on-screen transcript with the known recording. A completed mocked test or HTTP 200 is not proof. Missing or nonsense speech output is a failed compatibility result, not success.
7. If `words` are returned, verify each finite start/end offset is within recording duration and corresponds to playback. Only valid provider offsets are retained and sorted. No words/times/language/confidence are synthesized. Language is displayed only when actually returned; confidence remains unverified. No-speech/empty output becomes unavailable. Empty/noise recordings must not be accepted as verified speech.
8. Repeat submission/network retries and ensure the same upload/job IDs are reused, completed stages are not called again, and uncertain interruptions are disclosed rather than replayed. Test expired URLs, incorrect digest/version, denied microphone permission, leaving during recording and insufficient resources without losing originals.
9. Inspect quota metadata/dashboard without printing any transcript, signed URL, header or secret in logs. Keep a non-sensitive verification record of device/API level, model ID, MIME/codec, duration/size, request encoding, result assessment and whether returned offsets were validated. Do not claim completion until the actual device/cloud path passed.

### Quota qualifications

Keep the account on Workers Free: 10,000 Neurons/day resets at 00:00 UTC (05:30 IST). Local 8,000-account/3,500-owner defaults leave margin. Three frames + audio + report reserve 3,400; four frames + audio + report reserve 4,100 and exceed the default owner allowance. Verification jobs reserve 100 and do not invoke text report generation. Reports/PDFs/legal resources themselves never invoke AI.

Mongo single-document compare-and-swap makes concurrent reservations bounded; unknown outcomes retain reservations and cannot silently retry a completed/in-flight call. Provider error 3036 pauses the account; capacity rejection may retry after cooldown. The ledger tracks conservative estimates and provider token usage when present, not exact billed Neurons or unrelated applications' usage. Dedicated account usage and the provider's actual **Free** cap remain necessary; environment confirmations do not prove billing status. Direct compatibility tests outside the orchestrator must also be budgeted; the in-app test route already reserves quota.

### Incident Reports and PDF verification

Reports are available from Home's latest incident, FCM incident alerts, Incident Chat and Evidence. Backend `/incidents/{id}/reports` creates/lists snapshots and `/{reportId}` retrieves them with current owner/Trusted Circle checks on every request, including ended incidents. The same incident context remains reachable from LiveKit via its existing chat/evidence actions. Snapshot creation does not change SOS state or modify ended incident evidence/chat.

The report uses bounded authorized metadata: up to 100 confirmed evidence records, optional latest 100 chat messages, latest 20 analysis jobs and 500 timeline entries. Truncation/missing data are disclosed. Participant notes/chat are explicitly unverified; AI observations/transcripts are labelled. Verification samples never appear. Incident audio requires the production gate, an opted-in report and a consented analysis job; disabling the gate also withholds audio on later retrieval. Report metadata is persisted in Mongo; PDFs are generated only on Android using native PdfDocument/StaticLayout, with A4 pagination, page numbers and system text shaping. Source timestamps retain UTC offsets, word offsets remain recording-relative and are correlated only when a real capture anchor is available. Missing times remain unavailable.

New CameraX captures preserve the device's capture-start clock in a separate private timestamp marker, avoiding use of recording-end file modification time for new uploads. If that marker is unavailable (including legacy captures), the existing file timestamp remains an approximate source; it must not be treated as forensic timing. Extracted video audio retains the first decoded presentation-time offset when available rather than assuming audio starts at video offset zero. Original media bytes are unchanged. These native timing behaviors still require physical-device verification.

Test after approved deployment:

1. Generate a report with chat/audio excluded; verify notes, incident ID/status/start/end, generation time, missing media disclosures and disclaimers. Then explicitly include chat; verify excerpts and chronological order. Test an ended incident without changing its state.
2. Use a long non-sensitive report and Hindi text. Preview every PDF page; check wrapping, page breaks, page numbers, glyph rendering and references. Verify there are no B2 URLs, credentials or internal storage paths.
3. Check review acknowledgement, then Export via Android's document picker or Share. Reauthorization is required before preview retrieval, share and export; the document-picker callback rechecks too. `cache/report-pdfs/` is the only new FileProvider path; existing evidence sharing remains unchanged. No general files/root directory is exposed. Denied/revoked access clears the preview and blocks new export/share.
4. Revoke a Trusted Circle link and try again; sign into another account and attempt cross-incident/report access. Previously exported/shared external copies cannot be revoked by Saaya. Granting a user access to a report is not consent to auto-submit anything to authorities.
5. PDF rendering/FileProvider instrumentation checks are in `app/src/androidTest/.../reports/IncidentPdfTest.kt`; they require an actual device/emulator. Compiling them does not execute them. No such device test was run in this task.

### Offline legal/emergency resources

`SupportResources.kt` is the maintainable bundled source, verified 2026-11-06. Welcome and Home provide access independent of AI and without requiring sign-in for the welcome route. Details work in airplane mode; external calls/sites require their respective service. Actions use `ACTION_DIAL` (not auto-call) and an external browser with no incident data or prefilled submission.

Verified official sources:

- 112 ERSS: https://112.gov.in/ and https://www.mha.gov.in/emergency-response-support-system-erss
- 181 Women Helpline: https://wcd.gov.in/public/women/help
- 15100 NALSA: https://nalsa.gov.in/womens-assistance/
- **14490** NCW (current contact page; older sources may show an older number): https://ncwapps.nic.in/frmContactUS.aspx
- NCW complaint form: https://ncwapps.nic.in/onlinecomplaintsv2/frmPubRegistration.aspx ; official guidance: https://ncwapps.nic.in/onlinecomplaintsv2/frmFAQs.aspx
- NALSA application linked from its official women's-assistance page: https://scourtapp.nic.in/lsams/nologin/applicationFiling.action?requestLocale=en
- One Stop Centre information: https://www.wcd.gov.in/offerings/one-stop-centre-scheme

Confirm the four numbers, source URLs and dates offline; buttons should open the dialler without placing a call, or the official browser page without sending report data. **Do not place test emergency calls.** Check missing browser/dialler handling. Content separates emergency assistance, support/counselling/referrals and legal aid; it provides general guidance, not legal advice or representation. No state-specific legal claims or unverified numbers are bundled.

### Local validation commands

```powershell
Set-Location 'C:\Users\Charan\AndroidStudioProjects\NariKavach\saaya-backend'
.\.venv\Scripts\python.exe -m pytest tests -q
Set-Location 'C:\Users\Charan\AndroidStudioProjects\NariKavach'
node --test cloudflare-analysis/worker.test.mjs
.\gradlew.bat testDebugUnitTest compileDebugKotlin assembleDebug compileDebugAndroidTestKotlin
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Local mocked tests validate contracts, safety gates and error handling; they are not proof of live B2/Cloudflare compatibility, phone microphone performance, PDF rendering/export or telephone/browser behavior. Audio remains disabled by default until real end-to-end verification passes. All live setup/deployment is pending separate review.

When a test device/emulator is explicitly available, the two new native PDF/provider tests can be run separately (not run during this task):

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.project.saaya.reports.IncidentPdfTest
```

### Files changed in this milestone

Existing local work from the earlier AI milestone is retained. Paths below identify this milestone's additions/updates; unrelated changes already in the working tree are not replaced.

- Backend: `app/analysis.py`, `app/routes/analysis.py`, `app/routes/reports.py`, `app/main.py`, `tests/test_analysis.py`, `tests/test_reports.py`, `.env.example`, `AI_ANALYSIS.md` (relative to `saaya-backend/`).
- Worker: `cloudflare-analysis/worker.mjs`, `cloudflare-analysis/worker.test.mjs`, `cloudflare-analysis/wrangler.jsonc`.
- Android API/repositories (under `app/src/main/java/com/project/saaya/`): `data/remote/EvidenceApiService.kt`, `data/remote/ReportsApiService.kt`, `data/repository/EvidenceRepository.kt`, `data/repository/AnalysisPreprocessor.kt`, `data/repository/WavFormat.kt`, `data/repository/NativeWavRecorder.kt`, `data/repository/ReportContent.kt`, `data/repository/IncidentPdf.kt`, `data/repository/SupportResources.kt`.
- Android screens/navigation (same package root): `navigation/SaayaNavHost.kt`, `ui/screens/AnalysisPanel.kt`, `ui/screens/AudioVerificationPanel.kt`, `ui/screens/IncidentReportsScreen.kt`, `ui/screens/SupportResourcesScreen.kt`, `ui/screens/EvidenceScreen.kt`, `ui/screens/HomeScreen.kt`, `ui/screens/WelcomeScreen.kt`, `ui/screens/IncidentAlertScreen.kt`, `ui/screens/IncidentChatScreen.kt`.
- FileProvider: `app/src/main/res/xml/evidence_paths.xml`. Existing manifest permissions are reused; no broad provider path or new permission is added.
- Android tests: `app/src/test/java/com/project/saaya/data/repository/EvidenceRepositoryTest.kt`, `WavFormatTest.kt`, `ReportContentTest.kt`, `SupportResourcesTest.kt` (same test package), and `app/src/androidTest/java/com/project/saaya/reports/IncidentPdfTest.kt`.
