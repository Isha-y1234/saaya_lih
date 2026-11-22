# SAAYA Evidence Intelligence

## Activity architecture

Activity saves the original and its journal on the phone. The user opens **Analyse with AI**, selects a synthesis provider, and consents. Android derives bounded JPEG frames and WAV audio using the existing preprocessing. FastAPI authenticates the request and sends the derived media to the existing Cloudflare Worker. A separate request synthesizes the resulting text and source offsets. The Activity journal stores the report, and the Android PDF includes the original description, capture timestamp, optional GPS, uncertainty, and source findings. GPS coordinates stay local in provider-specific analysis.

Cloudflare is the default text provider on Android and FastAPI. The optional hosted pretrained provider requires `AI_SYNTHESIS_PROVIDER=adaption` and an Adaption key. The trained checkpoint requires `AI_SYNTHESIS_PROVIDER=checkpoint`, the authenticated private HTTPS endpoint settings below and `ACTIVITY_INFERENCE_VERIFIED=true` after verifying the persistent host. Colab is for offline benchmarking. Changing the provider clears Android consent. FastAPI checks consent for the exact selected provider and rejects unavailable choices.

The Activity journal stores the originals locally. On consent, Android sends derived frames or WAV and makes a separate report request. FastAPI validates the report context, sends explicit text source IDs to the Worker, and checks the report arrays and uncertainty before returning them to Android. References outside that source list are removed. The PDF uses the locally retained description, optional GPS and original capture timestamp. The Cloudflare report is still unverified AI output and needs human review. Activity calls do not create incidents or trigger SOS.

After checkpoint activation, a completed capture can generate another report from saved findings with fresh provider consent. The previous PDF stays available if synthesis fails. Activity displays the failed provider or stage, preserves retryable failures and avoids replaying unconfirmed upstream outcomes. The private checkpoint client uses a 35-second read timeout and checks the returned adapter alias before strict report validation. Verify the authenticated host and the exact Together base snapshot before enabling it. Deployment settings and a private vLLM serving example are below.

The completed Colab T4 NF4 artifact supplied 12 real generations on matched prompts with public Qwen. Strict grounding was 4/6 for the base and 6/6 for the adapter. Both scored 3/6 on abstention and 5/6 on the injection check. This is an experimental public-base comparison and does not verify the original training base or a persistent inference endpoint.

The new provider path assigns `note` and `source-01` style references, strips URLs, and accepts text only. Statements and direct observations are exact source extracts. Interpretations must cite an exact source quote and reuse its offset. The response validator checks categories, chronology, references, dates and times in interpretations, and abstention. The normalized report fits Android's existing report shape. MongoDB caches a provider-specific result for seven days for idempotency. The cache stores the validated result, which can contain evidence excerpts, and keeps its ownership scope. Originals remain in the local journal. An indeterminate provider outcome is saved to prevent immediate repeat billing.

Incident evidence, B2 confirmation, the existing incident Worker path, account authorization and incident job contracts continue through their existing routes. The older Activity cloud route keeps Cloudflare text synthesis for compatibility. The local-first Activity route requires no incident ID or B2 upload.

## Configuration and use

1. Install backend requirements. The documented package is `pip install adaption`. This revision pins the tested SDK to `0.19.0`.
2. Keep `AI_SYNTHESIS_PROVIDER=cloudflare` for the default. For optional hosted synthesis set it to `adaption`, provide `ADAPTION_API_KEY` via an ignored local `.env` or the deployment's secret mechanism, and set `ADAPTION_MODEL` if needed. The default is the documented hosted model `gemma-4-26b-a4b-it`.
3. For a trained checkpoint, set `AI_SYNTHESIS_PROVIDER=checkpoint` and configure `ACTIVITY_INFERENCE_URL`, `ACTIVITY_INFERENCE_TOKEN`, and `ACTIVITY_INFERENCE_CHECKPOINT`. The URL must be a trusted private HTTPS Chat Completions endpoint ending in `/v1/chat/completions`. The returned model ID must match the configured alias.
4. Save an Activity capture. Select **Analyse with AI**, select **Use SAAYA Evidence Intelligence** if offered, confirm the provider-specific consent, and start. Retry a paused analysis after restoring connectivity. The local capture and completed media stages remain available.

The Worker and Render must both include the preceding local-first Activity implementation. Keep the existing exact-model Whisper verification gate. The Adaption request uses the official asynchronous Responses client with no tools, no retained response, no agentic memory and no automatic retries. Service-level data processing terms still need review before sending real evidence.

Keep Render's existing authentication, MongoDB and incident settings. Cloudflare media processing requires `AI_WORKER_URL`, `AI_WORKER_SECRET`, `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN`, and `AI_FREE_PLAN_CONFIRMED=true` on Render. The Worker needs the matching `AI_WORKER_SECRET` and `FREE_PLAN_CONFIRMED=true`. Enable `AI_WHISPER_WAV_VERIFIED=true` on Render and `WHISPER_WAV_VERIFIED=true` on the Worker only after the real exact-model test. Keep `B2_HOST` on the Worker for incident media and retain the existing B2 credentials on Render.

## Dataset and training CLI

Run commands in `saaya-backend` with its Python environment.

```powershell
python saaya_ml.py prepare --input ml/fixtures.jsonl --output .local-adaption/fixture
python saaya_ml.py invent --rows 1000
python saaya_ml.py invent --rows 1000 --online
python saaya_ml.py account --online
```

The first command writes deterministic train, validation, held-out JSONL, a `train-sft.jsonl` export, and a manifest with counts, rejection reasons and hashes. The fixtures are fictional. Each input includes `id`, `group`, `language`, provenance and typed evidence sources. Targets include categorical claims with exact source quotes and offsets, uncertainty, and an abstention flag. The CLI accepts canonical JSONL and documented prompt/completion exports with an explicit source dataset ID. It rejects malformed or ungrounded rows, excludes duplicates, keeps groups together, and checks lexical overlap across splits. Review near-duplicates across paraphrases and languages manually. The size check uses a conservative UTF-8 byte bound against the documented context limit, with room for formatting. A tokenizer check on the actual checkpoint remains part of final training acceptance.

Freeze validation and held-out data before augmenting or adapting the training split. The authored `ml/heldout.jsonl` includes Hindi/Hinglish, prompt injection, disputed accounts, offsets, benign observations, and abstention. For the training commands, combine it with the preparation manifest's validation and held-out exports to create `frozen-holdouts.jsonl`. Keep real user cases, real identities and production evidence out of all these files.

```powershell
python saaya_ml.py invent --rows 1000 --online --approve-spend --max-credits APPROVED_LIMIT
python saaya_ml.py dataset-status --dataset INVENTED_ID --online
python saaya_ml.py download --dataset INVENTED_ID --output .local-adaption/invented.jsonl --online
python saaya_ml.py prepare --input .local-adaption/invented.jsonl --dataset synthetic:INVENTED_ID --holdouts .local-adaption/frozen-holdouts.jsonl --output .local-adaption/training
python saaya_ml.py upload --input .local-adaption/training/train-sft.jsonl --dataset synthetic:INVENTED_ID --approve-spend
python saaya_ml.py adapt --dataset TRAIN_ID --rows VALIDATED_COUNT --online
python saaya_ml.py adapt --dataset TRAIN_ID --rows VALIDATED_COUNT --online --approve-spend --max-credits APPROVED_LIMIT
python saaya_ml.py dataset-status --dataset TRAIN_ID --online
python saaya_ml.py download --dataset TRAIN_ID --output .local-adaption/adapted.jsonl --online
```

The `invent` and `adapt` online commands call the documented estimate-only modes by default. Explicit approval and an acceptable estimate are required before billable generation or adaptation. Upload only the prepared training split. Adaptive Data maps prompt and completion, enables deduplication, and disables prompt rephrasing and generated reasoning traces. Generated rows are rejected if their prompt or target cannot be validated. Inspect downloaded formatting before training, and correct or re-upload an export if adaptation changes required fields.

```powershell
python saaya_ml.py train-plan --dataset TRAIN_ID --rows VALIDATED_COUNT
python saaya_ml.py train-plan --dataset TRAIN_ID --rows VALIDATED_COUNT --online
python saaya_ml.py train --dataset TRAIN_ID --rows VALIDATED_COUNT --input .local-adaption/adapted.jsonl --holdouts .local-adaption/frozen-holdouts.jsonl --approve-spend --max-credits DASHBOARD_QUOTE
python saaya_ml.py train-status --run RUN_ID --online
python saaya_ml.py checkpoint --run RUN_ID --output .local-adaption/saaya-checkpoint.tgz --online
```

The training candidate is `Qwen/Qwen3.5-4B` in text SFT LoRA mode, with a documented 1,000 effective-row minimum. The account catalog includes this model. The offline plan validates the row floor. The online plan checks the account-visible model catalog, dataset status and hyperparameter recommendation. The SDK create API has no estimate-only switch. The operator must verify the dashboard quote and permissions before approving training. `--max-credits` records that operator decision and cannot enforce a platform training charge. Use the returned run ID for polling. If a launch response is lost, inspect the dashboard before trying again, because the SDK's training idempotency key only applies while a run is active.

The checkpoint command streams the documented archive. It checks archive member paths, LoRA config, base model ID, safetensors filename and SHA-256. It never extracts code or loads weights. AutoScientist exports weights. The hosted Responses model list documents Adaption's serving catalog and gives no documented checkpoint registration operation for this workflow. Use a compatible private serving runtime, pin the exact base revision and adapter, verify the alias and structured output with held-out smoke tests, then configure FastAPI.

## Evaluation

```powershell
python saaya_ml.py smoke --provider adaption --approve-spend
python saaya_ml.py evaluate --input .local-adaption/frozen-holdouts.jsonl --provider cloudflare --output .local-adaption/eval-cloudflare.jsonl --approve-spend
python saaya_ml.py evaluate --input .local-adaption/frozen-holdouts.jsonl --provider adaption --output .local-adaption/eval-adaption.jsonl --approve-spend
python saaya_ml.py evaluate --input .local-adaption/frozen-holdouts.jsonl --provider checkpoint --output .local-adaption/eval-checkpoint.jsonl --approve-spend
```

The CLI produces per-case results, failure reasons, counts by language, and a deterministic unsupported-claim proxy. The existing Cloudflare schema does not expose typed claim offsets or an abstention flag. These metrics are recorded as unavailable for that baseline. Exact reference and text checks can penalize valid paraphrases, so use blinded human review of factuality, legal safety, chronology, contradictions, benign cases, and Hindi/Hinglish before declaring an improvement. Use held-out sets with adequate language and scenario representation. No real model comparison or training outcome is implied by fixture tests.

## Verification, rollout and rollback

- Backend: `python -B -m pytest -q -p no:cacheprovider` from `saaya-backend`.
- Worker: `node --test cloudflare-analysis/worker.test.mjs` from the repo root.
- Android: `.\gradlew.bat testDebugUnitTest assembleDebug` from the repo root.
- Before rollout, verify Render and Worker versions, environment gates, approved provider data handling, Mongo index permissions, and a consented synthetic smoke test. Leave the default provider set to Cloudflare until verified.
- Roll back provider selection by setting `AI_SYNTHESIS_PROVIDER=cloudflare`. Already saved Android reports remain accessible. Provider result cache entries expire after seven days.
- Automatic tests mock Adaption and Cloudflare. A trained model, actual quality improvement, serving infrastructure and live rollout require separate approval and verification.
- Live non-billable checks returned an estimate of 100 credits for 1,000 invented rows and confirmed the training catalog exposes the selected model.

## Live synthetic run

### Primary dataset audit

#### Trained checkpoint inference preflight

Run `e25840e1-6a32-4055-821a-daf3f670edd1` exported a verified Zstandard-compressed tar at `saaya-backend/.local-adaption/autoscientist-completed/e25840e1-6a32-4055-821a-daf3f670edd1-best.tar.zst`. Its SHA-256 is `9e6a5a3f6153d22f251c11e1575b5734e2f12ebccbd66b5d9c2457cf511356df`. The adapter metadata specifies `togethercomputer/Qwen3.5-4B`, architecture `Qwen3_5ForConditionalGeneration`, rank 48, and alpha 96. It does not contain a base revision. The reported repository returned HTTP 401 without registry access, so obtain the pinned base snapshot from Adaption. The public Qwen model revision has no verified equivalence to the checkpoint's base.

The machine exposes Intel HD Graphics 520 with 1 GiB reported graphics memory. No compatible configured Activity endpoint or matching hosted Adaption model was found. The lowest incremental-cost route is an existing private Linux GPU host with access to the pinned snapshot. For the six-case test, serve both aliases from one vLLM instance using the same tokenizer and chat template. Mount the reviewed checkpoint and exact base snapshot on the host and bind the inference server privately.

```bash
vllm serve /models/pinned-qwen-base --served-model-name saaya-base \
  --host 127.0.0.1 --enable-lora --max-lora-rank 64 \
  --lora-modules saaya-adapter=/models/saaya-adapter \
  --tokenizer /models/saaya-adapter --chat-template /models/saaya-adapter/chat_template.jinja \
  --max-model-len 4096 --generation-config vllm
```

Expose the test host to FastAPI through an authenticated private HTTPS route ending in `/v1/chat/completions`. Configure `ACTIVITY_INFERENCE_URL` and `ACTIVITY_INFERENCE_TOKEN` in the local environment or ignored backend `.env`. From `saaya-backend`, run the paired benchmark once the host is available:

```powershell
.\.venv\Scripts\python.exe -B -m ml.evaluate_checkpoint_pair --base saaya-base --adapter saaya-adapter --output .local-adaption/checkpoint-paired-eval
```

This command checks the frozen-fixture SHA-256, runs both aliases through the existing optional checkpoint provider with identical requests and temperature zero, and writes per-case results and a summary. Review exact grounding, source IDs, timestamps, uncertainty and the Hindi/Hinglish cases. Schema and uncertainty-presence checks do not replace review of whether the uncertainty is substantively correct. A mocked end-to-end test completed all 12 requests and verified matching settings. No real inference scores exist yet. Cloudflare remains the default, and no paid host was provisioned. [vLLM LoRA serving](https://docs.vllm.ai/en/latest/features/lora/) · [vLLM model support](https://docs.vllm.ai/en/latest/models/supported_models/)

#### Raw AutoScientist import preflight

**Live result.** The validated SHA-256 file was uploaded once with raw processing as dataset `e36d2d36-6eea-4bd8-be5c-644717653891`. Adaption reported `succeeded` with exactly 1,034 rows. The exported `original_prompt` and `original_completion` pairs matched the local file exactly as a multiset and passed strict validation. The enhanced columns were empty, as expected for this import. The balance was 845 credits before and after. The ignored merge directory contains `raw-import-receipt.json` and `training-readiness.json`. Reuse this dataset ID for future training preparation.

The live recommendation for `Qwen/Qwen3.5-4B` with zero augmentation returned LoRA, one epoch, rank 16, alpha 32, all-linear targets, maximum supported batch, a 0.00005 learning rate, and a cosine schedule. The proposed run has one iteration and zero domain or general augmentation. Raw training can use the canonical mapping established by raw import, as in the official raw workflow. The recommendation returned neither price nor duration, and the public create API has no estimate mode. Adaption must provide a pre-launch quote for this exact dataset and recipe before training. No run, checkpoint, or trained-model endpoint exists for this dataset. The existing Cloudflare default and optional private checkpoint integration remain in place.

Run these commands from `saaya-backend`. Preflight verifies the pinned SHA-256, all 1,034 unique prompt/completion pairs, strict source grounding, and separation from the six frozen examples. It makes no network calls.

```powershell
.\.venv\Scripts\python.exe -B -m ml.raw_training_import
```

For an approved first-time upload of an equivalent pinned artifact, the commands are below. For the current artifact, skip the upload and use the recorded ID.

```powershell
.\.venv\Scripts\python.exe -B -m ml.raw_training_import --upload
.\.venv\Scripts\python.exe -B saaya_ml.py dataset-status --dataset <DATASET_ID> --online
.\.venv\Scripts\python.exe -B saaya_ml.py train-plan --dataset <DATASET_ID> --rows 1034 --online
```

The raw import maps `prompt` and `completion` to identically named source columns. It records the upload response's `dataset_id` and `status` in the ignored `raw-import-receipt.json`. An attempted receipt without a dataset ID requires reconciling the account's dataset list before retrying. A successful upload receipt can still be pending. Wait for `succeeded` and verify the remote count is 1,034 before training. The plan uses `Qwen/Qwen3.5-4B`, instruction SFT, LoRA, one iteration and no augmentation. The plan endpoint does not return a training quote. [Raw processing](https://docs.adaptionlabs.ai/autoscientist/run-on-non-adapted-data/)

After an approved run succeeds, stream and validate the best checkpoint with `python saaya_ml.py checkpoint --run <RUN_ID> --online --output .local-adaption/saaya-best-checkpoint.tgz`. To audit the same archive offline, use `python saaya_ml.py checkpoint --input .local-adaption/saaya-best-checkpoint.tgz`. The checks cover archive paths, the base model, a safetensors header and SHA-256. Before serving, verify the exact base revision and load the adapter in a compatible private runtime. Test the model against the frozen reports. Keep Cloudflare for media extraction and as the default synthesis provider. A future opt-in Activity rollout can use the existing consent-gated checkpoint adapter via `ACTIVITY_INFERENCE_URL`, `ACTIVITY_INFERENCE_TOKEN` and `ACTIVITY_INFERENCE_CHECKPOINT`; Incident analysis does not need to change. [Checkpoint archive](https://docs.adaptionlabs.ai/autoscientist/download-the-model/)

#### Completed current-account export and merged candidate

Adaption confirmed `succeeded` for dataset `5a997683-e5b6-4512-b04e-440cab666c83` and reported 1,250 rows. All 1,250 downloaded records have the `enhanced_prompt` and `enhanced_completion` columns. The unchanged JSONL SHA-256 is `a5e109144c1051a9c66993e2397dfd386a5e81ae0e11aa440c5365e34d6f4d8e`. This ID is distinct from the 800-row job.

The export supplied **338 accepted unique additions**, yielding **1,034 training rows**. There were 856 strict schema or grounding failures, 55 rows held for non-verbatim interpretation review, and one duplicate. No automated frozen scenario-group or lexical overlap was found. Legacy language labels were retained. For 278 rows with distinct evidence but reused administrative IDs, a stable replacement was derived from the dataset ID and export line, with both IDs logged. Evidence, quotes and source IDs were unchanged. The added rows cover 98 video, 108 audio, 116 note and 16 photo. Four have mixed Hindi/English labels and none contain Devanagari in their source text. The lexical check does not establish semantic independence, and the interpretation screen is conservative.

```powershell
python -m ml.validate_grounded_batch 5a997683-e5b6-4512-b04e-440cab666c83 .local-adaption/current-5a997683/generated.jsonl .local-adaption/current-5a997683/merged --expected-rows 1250 --legacy-labels --extractive-interpretations
```

Use `.local-adaption/current-5a997683/merged/prepared/train-sft.jsonl` from `saaya-backend`. The unchanged 696-row baseline and six frozen examples are verified by the SHA-256 values in `merged/validation-report.json`. The merged candidate clears the published row minimum. Upload and training remain pending approval.

#### Final original-only candidate

The follow-up export check on 2026-11-22 at 04:13 UTC found that both Adaption dataset endpoints still reported `running` for `baea35b3-2c70-479e-8541-02eb82b85fe3`. Neither reported a row count or error, and the export-link endpoint returned no links. The completed-export validation remains pending, with 696 verified training rows.

Once the provider reports `succeeded`, keep the downloaded original JSONL at `.local-adaption/grounded-v2-800/generated.jsonl` and run the existing merge command from `saaya-backend`. It now defaults to the 696-row `train-sft.jsonl` baseline and records the export, baseline and frozen-set SHA-256 hashes in the validation report. Use the provider-confirmed export row count for `--expected-rows` if it differs from 800. The new training file is written under the separate validation output directory.

The final pass reviewed all 555 previously invalid rows and recovered six with cosmetic differences between claim text and the exact cited quote. The combined dataset has **696** training rows and is **304 short** of 1,000. The audit contains a decision and failure categories for each of the 555 rows. The 549 remaining failures include 322 non-extractive claims, 35 source/quote/offset mismatches, 92 JSON failures, and 100 rows with field/schema errors. No conflicting offset, source ID or quote was replaced.

The candidate covers 415 recorded scenario groups and 476 abstentions. Evidence kinds are 227 video, 219 audio, 207 note, and 43 photo. Four rows are labeled mixed Hindi/English and no source contains Devanagari. The scenario separation checks catch group matches and lexical overlap. They cannot establish semantic independence. The original export, 690-row baseline and six frozen examples retained their hashes, and the final candidate passed strict validation.

```powershell
python -m ml.finalize_previous_export "$env:TEMP\saaya-previous-account-6efd95bd.jsonl" .local-adaption/primary-6efd95bd-final
```

The new artifact is `saaya-backend/.local-adaption/primary-6efd95bd-final/prepared/train-sft.jsonl`, alongside `summary.json` and `recovery-audit.jsonl` in the audit directory. The already submitted 800-row job `baea35b3-2c70-479e-8541-02eb82b85fe3` separately reported `running` with no row count or error. It was left alone and excluded. The estimate-only balance check returned 845 credits.

The current account catalog returned 16 SFT-capable models, including both `Qwen/Qwen3.5-2B` and `Qwen/Qwen3.5-4B` with LoRA and full tuning. Prefer 4B LoRA as the first structured-report benchmark after the row minimum is met. Use 2B LoRA as the smaller serving comparison and select between them using grounding and bilingual evaluation. No exact cost applies to the under-minimum candidate. AutoScientist create has no estimate-only mode. Review the dashboard quote for an eligible dataset before requesting approval. [AutoScientist model requirements](https://docs.adaptionlabs.ai/autoscientist/supported-models/) · [Adaption estimate availability](https://docs.adaptionlabs.ai/tools/mcp/)

#### Original-export re-audit

Use the original prior-account export for local training preparation. The separate 800-row job had already been submitted and debited 80 credits before the stop request. Its status, output and credits were not used for this re-audit.

The second pass re-read all 1,249 original rows and their prior outcomes. The separate candidate has **690 rows**, including the original 383, 225 previously schema-valid rows excluded for reused administrative IDs, and 82 formerly rejected rows. Four rows were excluded as duplicate normalized evidence and 555 still fail validation. The original export, baseline training files, prior decisions and six frozen evaluation examples retained their hashes.

The re-audit separated schema annotations from intact reports and sorted known claims by their existing source offsets, with unknown-offset slots fixed. Where distinct evidence reused an administrative row ID, the new ID was derived from the original export line. The lineage log records the old and new IDs. Evidence source IDs, quotes and timestamps were never inferred or changed. Existing strict validators passed on the final candidate. They test source and timestamp equality, attribution and known ordering. The frozen-set check covers shared group IDs and word overlap. It cannot rule out semantic overlap, and schema checks cannot establish that every interpretation is substantively correct. Only four candidate rows have mixed Hindi/English labels.

```powershell
python -m ml.reaudit_previous_export "$env:TEMP\saaya-previous-account-6efd95bd.jsonl" .local-adaption/primary-6efd95bd-reaudit
```

The output contains `prepared/train-sft.jsonl`, `summary.json` and a `row-audit.jsonl` entry for every original row. A read-only current-account catalog check returned 16 SFT-capable models, including Qwen 3.5 2B and 4B. Adaption's documented floor is 1,000 effective instruction rows across model sizes. The 690-row candidate is 310 short. Text augmentation can count toward that floor, but would add rows outside the requested original-only workflow. There is no applicable training quote for this under-minimum candidate. Keep Cloudflare as the current synthesis provider and use the existing source-linked validation. Adaption pretrained inference is a separately configurable comparison that requires neither this dataset size nor an AutoScientist run. [Supported models](https://docs.adaptionlabs.ai/autoscientist/supported-models/)

#### Proposed 800-row follow-up

The 383 accepted rows span 255 scenario groups: 126 video, 123 note, 105 audio, and 29 photo. There are 268 abstaining reports and only two rows labeled mixed Hindi/English. Among the 638 remaining rejections, original diagnostics include 319 invalid completion JSONs, 82 invalid prompt JSONs, 51 schema documents emitted as answers, 132 non-extractive reported or observed claims, 21 statements mislabeled as observations, 12 offset mismatches, eight missing quotes, and one unknown source. Categories can overlap. See the per-row audit for the remaining schema and chronology errors.

The proposed [Invent prompt](saaya-backend/ml/invent-grounded-v2.txt) replaces embedded schema definitions with explicit field contracts and a fictional example. It specifies exact quote, source and offset copying, distinct row IDs, shared scenario family IDs, and balanced language and modality targets. The runtime Evidence and Report schemas remain the validation authority. The provider accepts these requirements through the dataset prompt, so export validation is still required.

The SDK quoted 80 credits for the exact 800-row request using estimate-only mode, with 925 credits available. The local key was verified by reading the current-account dataset `5a997683-e5b6-4512-b04e-440cab666c83`. Use that account for local generation and training. Keep Render's synthesis provider on Cloudflare unless Adaption runtime use is enabled. If enabled, configure a dedicated key for the same current account through Render secrets. No secret was changed.

After export, validate both JSON objects and reapply strict schema, quote, source, timestamp, chronology, provenance and contact validators. Deduplicate against the existing 383 rows and check scenario families and lexical similarity against frozen evaluation. Review potential semantic overlaps, the actual Hindi/Hinglish content, category coverage, unsupported interpretations and responses to embedded instructions. Write new artifacts separately. At least 617 of the 800 generated rows (77.1%) must survive to reach 1,000. The previous batch yielded 383 of 1,249 (30.7%) after repair and deduplication, so the follow-up cannot guarantee the minimum.

Adaption accepted the approved 800-row request at an 80-credit quote and 925-credit starting balance. The new dataset ID is `baea35b3-2c70-479e-8541-02eb82b85fe3`. The receipt and latest provider status are stored locally under `saaya-backend/.local-adaption/grounded-v2-800/`. Once the provider reports success, download the JSONL to that directory and run the following command from `saaya-backend`.

```powershell
python -m ml.validate_grounded_batch baea35b3-2c70-479e-8541-02eb82b85fe3 .local-adaption/grounded-v2-800/generated.jsonl .local-adaption/grounded-v2-800/validation
```

The primary source is the completed previous-account Invent dataset `6efd95bd-33a0-41b9-8f3e-71dfbebdda32`. Its original JSONL is `%TEMP%\saaya-previous-account-6efd95bd.jsonl`. Adaption reported 1,249 rows and the export contains 1,249. The original was read without modification, and its SHA-256 before and after preparation was `7bebb565202c52c2bc9406449c891344bbe1f29049c8ef062416f9e7ed97eacb`. Older recovery notes below describe the job before Adaption finished it.

| Check | Rows |
|---|---:|
| Strict initial pass | 191 |
| First-pass rejection | 1,058 |
| Recovered and revalidated | 420 |
| Rejected after safe repairs | 638 |
| Duplicate candidates removed | 228 |
| Usable training rows | **383** |

First-pass rejection categories were 601 JSON decoding errors, 134 schema errors, and 323 other validator errors. Diagnostics can overlap. They include 579 invalid completion fields, 82 invalid prompt fields, 51 responses carrying schema-definition fields, 166 transcript or note claims presented as observations, 132 extract mismatches, 12 source timestamp mismatches, eight missing source quotes, one unknown source reference, and seven chronology or abstention failures. The audit also records extra fields, invalid evidence/source types and malformed identifiers. Rows that fail decoding cannot be audited for deeper missing fields.

Repairs only unwrap a single JSON fence, remove prompt-level embedded output/schema fields, normalize whitespace when quote text is otherwise identical, or reclassify an exact note/transcript extract as reported. Original evidence, source identifiers and times are preserved. Malformed structures, unverifiable paraphrases, mismatched offsets and unknown references remain rejected. The strict and repaired candidates were checked again, with the original 191 rows prioritized during deduplication. Of 228 exclusions at deduplication, 226 reused a row ID and two repeated normalized evidence. Reused IDs can also represent conflicting variants, so these were excluded without assigning new IDs.

The usable labels were 216 `English`, 165 `en`, and two `en-Hi`. Labels do not establish actual language content. This is weak evidence of Hindi/Hinglish coverage. The six frozen cases in `saaya-backend/ml/heldout.jsonl` were kept separate. No overlap was detected by the existing group, exact-token and lexical near-duplicate checks. Semantic leakage across paraphrases or translations and factual entailment of interpretations still require human review.

The 383 usable rows fall **617 rows short** of AutoScientist's 1,000-row effective minimum for the selected workflow. These local artifacts are the primary candidate pool for future preparation. No adaptation, augmentation or training was submitted.

```powershell
# Run from saaya-backend
python -B -m ml.audit_previous_export "$env:TEMP\saaya-previous-account-6efd95bd.jsonl" .local-adaption/primary-6efd95bd
```

The ignored output directory contains `strict-initial.jsonl`, `row-decisions.jsonl`, `audit-summary.json`, and `prepared/train-sft.jsonl`. The frozen benchmark stays in its existing file. No new evaluation examples are drawn from the primary training pool.

The current account's 1,250-row job is `5a997683-e5b6-4512-b04e-440cab666c83`. Use the following bounded recovery command from `saaya-backend`. It only reads the existing job. On success it downloads UTF-8 JSONL and validates the row count before creating local splits.

```powershell
python saaya_ml.py recover --dataset 5a997683-e5b6-4512-b04e-440cab666c83 --expected-rows 1250 --holdouts ml/heldout.jsonl --output "$env:TEMP\saaya-adaption-new-account-1250" --online --wait-seconds 150
python saaya_ml.py adapt --dataset TRAIN_DATASET_ID --rows VALIDATED_TRAIN_ROWS
python saaya_ml.py train-plan --dataset ADAPTED_DATASET_ID --rows VALIDATED_TRAIN_ROWS
```

The last two commands are local configuration templates. Replace the placeholders after validation and omit online and spend flags to print the templates. AutoScientist requires at least 1,000 validated training rows. The split manifest reports language counts as labeled in the export, duplicate and schema rejections, and potential contact patterns. Synthetic provenance and pattern checks support review. They cannot establish that every generated person or place is fictional. Existing evaluation fixtures are checked for overlaps and remain separate.

Adaption accepted a 1,250-row Invent request at a 125-credit estimate against a balance of about 1,050 credits. Dataset ID `6efd95bd-33a0-41b9-8f3e-71dfbebdda32`. The balance read after submission and inference was about 925 credits. The provider last reported `running`, with no row count, progress or error, after two timed polling windows. Check its latest state with `python saaya_ml.py dataset-status --dataset 6efd95bd-33a0-41b9-8f3e-71dfbebdda32 --online` before downloading or preparing any training data. Adaptive Data and AutoScientist have no submitted run for this dataset.

On recovery, the same credentials could read the job, but the documented download returned HTTP 422 with “No run has started on this dataset yet.” No published export links existed. The status still reported `running`; the documented Invent flow downloads after generation, so the download error's generic adaptation suggestion should not be used to start a new job on this record. Ask Adaption support to reconcile the status if it persists. The local validator accepts Adaption's documented `enhanced_prompt` and `enhanced_completion` export columns.

The initial `gemma-4-26b-a4b-it` transcript response included Markdown fencing and treated a transcript tag as a direct observation. After tightening the instructions and accepting a single JSON fence, a live smoke test returned a valid report with a reported Hinglish quotation, `source-01`, a null offset, explicit uncertainties and abstention.

The six-case live Adaption benchmark returned valid schemas, source references, timestamp checks, and nonempty uncertainty in all six cases. The grounding proxy flagged 0 of 7 claims. Abstention matched 3 of 6 case labels because the model abstained in every case. The prompt-injection case reproduced a forbidden marker. One of two Hinglish cases and two of six overall passed all checks. These are small-sample results. The benchmark reused the smoke case, and the grounding proxy checks exact quotation and source category rather than semantic entailment. Cloudflare and trained-model scores require a reachable configured Cloudflare endpoint and an actual trained checkpoint, respectively.

vLLM documents Qwen 3.5 model-family and LoRA serving support. A compatible private vLLM host is a candidate for the eventual exported artifact. Confirm the exact architecture, adapter, base revision and configured alias before enabling the checkpoint provider. No hosting was provisioned. [vLLM supported models](https://docs.vllm.ai/en/latest/models/supported_models/)

## Integration files

- Backend runtime: `saaya-backend/app/evidence_intelligence.py`, `saaya-backend/app/activity_adaption.py`, `saaya-backend/app/routes/activity.py`, `saaya-backend/requirements.txt`, `saaya-backend/.env.example`.
- ML and tests: `saaya-backend/saaya_ml.py`, `saaya-backend/ml/fixtures.jsonl`, `saaya-backend/ml/heldout.jsonl`, `saaya-backend/tests/test_evidence_intelligence.py`, `saaya-backend/tests/test_activity.py`.
- Android Activity: `app/src/main/java/com/project/saaya/data/remote/ActivityEvidenceApi.kt`, `app/src/main/java/com/project/saaya/data/repository/ActivityEvidenceRepository.kt`, `app/src/main/java/com/project/saaya/data/repository/ActivityAiReport.kt`, `app/src/main/java/com/project/saaya/ui/screens/ActivityScreen.kt`.

Official references: [Invent](https://docs.adaptionlabs.ai/adaptive-data/invent-a-dataset/) · [Adaptive Data](https://docs.adaptionlabs.ai/adaptive-data-quickstart/) · [AutoScientist](https://docs.adaptionlabs.ai/autoscientist-quickstart/) · [Training models](https://docs.adaptionlabs.ai/autoscientist/supported-models/) · [Python SDK](https://docs.adaptionlabs.ai/api/python/) · [Responses API](https://docs.adaptionlabs.ai/api/resources/responses/methods/create) · [Hosted models](https://docs.adaptionlabs.ai/inference-stacks/models/) · [Checkpoint export](https://docs.adaptionlabs.ai/autoscientist/download-the-model)
