# Rivet for many users, safely

How Rivet goes from one owner to 10, 100, 1,000, 100,000 and 1,000,000 people without breaches or slowdowns. Written
2026-10-29 from a code audit (commit `5463b42`), measurements with the brain's test fakes and provider pages read that
day; every external fact carries its URL, **unverified** marks what no source confirmed, **derived** marks arithmetic or
inference. Nothing here is done yet. This file describes open exposures (§2): keep it off public branches until §7
step 0 is done.

## 1. Bottom line

No design can promise zero breaches. This one keeps a breach small, seen and stopped: every user has their own sign-in,
rows and key, so a bug or stolen credential exposes at most one user (or one cell), per-user audit logs show it, and
revoking that user or cell contains it. Today whoever holds the APK is the owner (§2); fix that before anyone else joins
(§7), then grow in cells. Providers set the speed limit more than our code: Perplexity allows 33 requests/s per
organization at its top tier (https://docs.perplexity.ai/docs/admin/rate-limits-usage-tiers), ElevenLabs 4-40 voice
calls at once by plan (https://elevenlabs.io/pricing/agents); contracts must grow with users.

## 2. Today: why Rivet serves one person

Brain modules are in `brain/src/rivet_brain/`, app files in `android/app/src/main/java/com/hiron/rivet/`.

- **No user identity.** One shared bearer, `RIVET_DEVICE_TOKEN`, guards the socket, ElevenLabs credentials and search
  (`gateway.py:530-553`, `:573`, `:605`), and the phone picks the session id (`gateway.py:130`, `:554-560`). Any token
  holder can open any session and read, approve and act in it.
- **Memory already crosses sessions:** `FencedStorage` has no `namespace()` (`storage/fenced.py:100-113`), so all
  sessions share one memory prefix (`factory.py:215`, `:246`). With fakes, a fact saved by `alice` reached `bob`'s model.
- **The owner's accounts serve every session:** one mailbox (`email_tools.py:122-148`); one Telnyx account through one
  Horizon key (`auth.py:15-41`, `config.py:66`), the MCP's single-tenant mode, where "Everyone holding the access token
  acts as that Telnyx account" (`OubliTelnyxMCP/CLAUDE.md:43`); one call-answering assistant found by name
  (`calls.py:182-219`); live-call lookups across the account (`live_calls.py:134-148`); the owner's ElevenLabs agents
  (`gateway.py:114-129`); one set of Brave, Perplexity and Groq keys.
- **No per-user limits:** nothing is rate limited or budgeted per user, and a `task` message sets its own attempts and
  timeout (`gateway.py:276-288`). The call-webhook token also reaches the access log (`gateway.py:696-713`).
- **One process on one machine:** sessions stay in memory until shutdown (`gateway.py:466-490`), each holds a file lock
  on the local volume (`supervisor.py:1098-1108`), and offline events live only in memory (`gateway.py:107`, `:188`).
  A Fly volume attaches to one Machine and is not replicated (https://fly.io/docs/volumes/overview/).
- **Measured with fakes** (4 vCPU, no TLS; unverified on production's 512 MB `shared-cpu-1x`, `brain/fly.toml:12-13`):
  an idle session costs about 1.9 MB, 1 thread and 5 file descriptors, plus 2.2-2.7 MB after 5-20 short turns; a text
  turn costs 60-66 ms CPU; one process tops out near 7.5 turns/s. Production holds about 74-180 sessions (derived).

### Dangers that exist right now (fixes: §7 step 0)

1. **Whoever holds the APK is the owner.** Release builds bake in the brain URL, device token, session id and answering
   number as plain strings (`android/app/build.gradle.kts:19-27`, `core/Settings.kt:21-32`), found in the 0.4.0 and
   0.3.0 APKs; Keystore encryption covers only a token typed in Settings. A second install auto-connects
   (`Settings.kt:38-40`) to the owner's conversation, memory, approvals, email, callers' messages and phone tools,
   mints ElevenLabs credentials (`gateway.py:571-597`) and can compute any webhook token (`calls.py:88-91`).
2. **Any code on the VM can act as its service account,** the Compute Engine default one with full Cloud API access.
   Any process there, even as `nobody`, can get its token from the metadata server
   (https://docs.cloud.google.com/iam/docs/best-practices-service-accounts), past any VPC firewall rule
   (https://docs.cloud.google.com/firewall/docs/firewalls). The default account "might automatically be granted the
   Editor role" (https://docs.cloud.google.com/compute/docs/access/service-accounts), which can "read and modify all
   resources" and create keys that "anyone who possesses" can use (best-practices page). Whether it has Editor here is
   unverified; if it does, code run there could take over the project.
3. **The ElevenLabs agents are open** (read-only check, 2026-10-29): `enable_auth: false`, no allowlist, ids in this
   public repository (`gateway.py:114-129`), and a public agent connects by id alone
   (https://elevenlabs.io/docs/eleven-agents/libraries/web-sockets). Their webhook tools use the owner's key:
   `list_conversations` reads the account's conversation summaries (https://api.elevenlabs.io/openapi.json), others
   create agents and voices, and six Google Calendar tools, delete included, act on one Google account. Clients may
   override the voice agent's LLM and tools; both agents keep transcripts and audio with no time limit
   (https://elevenlabs.io/docs/eleven-agents/customization/privacy/retention). Not tried live, as a test would bill.

## 3. The rules that make many users safe

1. **One identity per request; no shared secret in the app.** The phone signs in with Google through Credential Manager
   (https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation). The brain checks the ID
   token's signature, `aud`, `iss` and `exp`, keys everything by `sub` (https://developers.google.com/identity/gsi/web/guides/verify-google-id-token),
   and issues a short-lived credential bound to an Android Keystore device key
   (https://developer.android.com/privacy-and-security/keystore). The brain picks session ids and webhook tokens.
2. **The storage layer enforces isolation.** Every row carries its user id under PostgreSQL row-level security, which
   denies by default but is bypassed by superusers, `BYPASSRLS` roles and, unless forced, table owners
   (https://www.postgresql.org/docs/current/ddl-rowsecurity.html). So: `FORCE ROW LEVEL SECURITY`; a runtime role that
   owns no tables and is not in `cloudsqlsuperuser` (whether that bypasses RLS is unverified); the user set with
   `SET LOCAL`, which ends with the transaction (https://www.postgresql.org/docs/current/sql-set.html); no model-written
   SQL. Files sit under per-user prefixes behind downscoped tokens (https://docs.cloud.google.com/iam/docs/downscoping-short-lived-credentials).
3. **Each user has their own key; secrets stay on servers.** "Do not use the same DEK to encrypt data from two
   different users" (https://docs.cloud.google.com/kms/docs/envelope-encryption). Design (derived): a key per user,
   stored only wrapped by the cell's KMS key, wraps that user's data keys, since KMS creates 60 keys a minute per
   project (https://docs.cloud.google.com/kms/quotas). Platform keys go in Secret Manager, granted per secret
   (https://docs.cloud.google.com/secret-manager/docs/best-practices); nothing secret goes in the APK, a workspace or VM
   metadata, which any process on the VM can read (https://docs.cloud.google.com/compute/docs/metadata/overview).
4. **The agent's power is bounded per user.** Approvals, taint and standing rules stay deterministic (`approvals.py`,
   `rules.py`), keyed by user; tools reach only the user's own number, assistant and mailbox. A signed ElevenLabs URL
   "only verifies that the request came from an authorized source"
   (https://elevenlabs.io/docs/eleven-agents/customization/authentication), so the brain signs the user in first;
   client overrides stay off, their default "for security reasons"
   (https://elevenlabs.io/docs/eleven-agents/customization/personalization/overrides). Workspaces are sandboxed (§5).
5. **Each user has rate limits and a spend cap** per minute and per day, on turns, tasks, searches, voice minutes and
   tokens, enforced in the brain: Cloud Armor counts HTTP requests (https://docs.cloud.google.com/armor/docs/rate-limiting-overview),
   and a WebSocket is one (derived). Brave forbids extra accounts to get past rate limits
   (https://api-dashboard.search.brave.com/documentation/resources/terms-of-service) and Telnyx forbids spreading
   traffic across keys to bypass a limit
   (https://developers.telnyx.com/docs/development/api-fundamentals/reliability/rate-limiting.md), so capacity is bought.
6. **Every action is audited by user:** an append-only record of user, device, tool, approval and outcome; no secrets
   or message text in logs; Data Access audit logs, off by default (https://docs.cloud.google.com/logging/docs/audit),
   on for KMS, Secret Manager and Cloud SQL; alerts on denied cross-user access, spend spikes and new devices.
7. **Cells cap the blast radius.** A cell is a full copy of the service that shares no state with other cells, chosen
   by a partition key such as the user id through a thin router
   (https://docs.aws.amazon.com/wellarchitected/latest/reducing-scope-of-impact-with-cell-based-architecture/what-is-a-cell-based-architecture.html).
   A Rivet cell has its own database, KMS key, brain pool and workspace hosts, and a tested maximum size. CI and every
   infrastructure change try to reach user B's rows, files, memory, sessions and workspace as user A; all must fail.

## 4. What each step adds

| Area | 10 users | 100 | 1,000 | 100,000 | 1,000,000+ |
|---|---|---|---|---|---|
| Sign-in | Sign in with Google; a brain credential bound to a Keystore device key; nothing baked in the APK | Identity Platform (decision 1) | Revocation checked at every connect | Higher sign-up limits from Google | Higher token-exchange quota |
| Storage | Per-user prefixes and memory in today's store, then Postgres with RLS and per-user encryption keys | Cloud SQL HA; KMS; Secret Manager; offline events in the database | Connection pooler; workspace archives under per-user Cloud Storage prefixes | A database primary and KMS key per cell | Cells in several regions |
| Brain workers, session placement | One process; idle sessions unloaded | A few processes; each user pinned to one; a shared event bus | Autoscaled pool behind a thin router keyed by user | Cells of a fixed, tested size | More cells, in several regions |
| Gateway | Per-user checks and limits on every message | Load balancer with WebSockets; Cloud Armor by IP; jittered reconnects | Global load balancer; clean reconnects | Cell router | Routing by region |
| Workspaces | This VM: one gVisor container per user, capped by measurement | 2-3 VMs as cells, each with its own hostnames | GKE Agent Sandbox: warm pools, a volume per active user | Several clusters; idle homes archived | Clusters in several regions |
| Providers and contracts | Agents locked down; Groq off; Perplexity Tier 1+ | ElevenLabs Business; Perplexity Tier 3+; Telnyx commit plan | Perplexity Tier 4-5; Enterprise talks with ElevenLabs and Brave | Custom limits from every provider | Contracts per region |
| Compliance | Privacy policy and terms; delete one user end to end | Google verification and CASA (if Gmail OAuth); retention set at each provider | Zero-retention agreements where offered | VPC Service Controls | Data residency by region |
| Operations | Alerts, backups, a restore drill, cross-user tests in CI | Staged deploys; a per-user cost view; on-call | A load test every release; runbooks; 24/7 on-call | Canary cell; quotas raised months ahead | Follow-the-sun on-call |
| Move on when | T1 | T2 | T3 | T4 | — |

Any one of these limits moves Rivet to the next column:

- **T1, past 10:** the 512 MB brain machine holds about 74-180 sessions (§2); this VM runs 1-7 full workspaces (§5.3);
  ElevenLabs runs 4, 6, 10, 20, 30 or 40 voice calls at once per workspace, Free to Business
  (https://elevenlabs.io/pricing/agents; the chat-mode page says 30 for Business, and which is current is unverified,
  https://elevenlabs.io/docs/eleven-agents/guides/chat-mode); an unverified Google OAuth app stops at 100 new users
  (https://support.google.com/cloud/answer/7454865). Groq stays off throughout: its model is a Preview one, "intended
  for evaluation purposes only" (https://console.groq.com/docs/models.md).
- **T2, past 100:** one brain process carries about 7.5 turns/s (§2); Perplexity gives the organization 1, 3, 8, 17 and
  33 requests/s at Tiers 0-4, shared by every key and model (page in §1); Brave Answers allows 2 requests/s
  (https://api-dashboard.search.brave.com/documentation/services/answers/index.html.md); ElevenLabs burst stops at 3×
  the plan or 300, whichever is lower, at double price (https://elevenlabs.io/docs/eleven-agents/guides/burst-pricing).
- **T3, past 1,000:** 33 requests/s is about 28 per user per day at 100,000 users (derived), and Brave Search's 50/s
  (https://brave.com/search/api/) about 43 (derived); an ElevenLabs agent takes 100,000 conversations a day by default,
  then rejects calls (https://api.elevenlabs.io/openapi.json, https://elevenlabs.io/docs/eleven-agents/guides/call-queueing);
  Telnyx allows 1,000 managed accounts per manager (https://support.telnyx.com/en/articles/4951492-managed-accounts);
  Identity Platform allows 100 new accounts an hour per IP (https://docs.cloud.google.com/identity-platform/quotas);
  1% of 100,000 users in workspaces at once needs about 143-200 machines like this VM (derived).
- **T4, past 100,000:** Identity Platform allows 18,000 token exchanges a minute per project (quotas page) and ID tokens
  last an hour (https://firebase.google.com/docs/auth/admin/manage-sessions), so a million hourly refreshes take nearly
  all of it (derived); Cloud KMS allows 60,000 cryptographic requests a minute (https://docs.cloud.google.com/kms/quotas), and a
  million hourly reconnects need about 16,700 unwraps a minute (derived); GKE allows 200,000 Pods per cluster and 100
  regional clusters per region (https://docs.cloud.google.com/kubernetes-engine/quotas).

## 5. This VM

### 5.1 Harden it first

The owner runs these in order, with gcloud set to the VM's project; this session has no access. `PROJECT_ID`,
`OWNER_EMAIL` and `ORGANIZATION_ID` are placeholders (only the project number, 964661841583, is known).

```bash
# 1. Keep the IP; a stop releases an ephemeral one (https://docs.cloud.google.com/vpc/docs/reserve-static-external-ip-address).
gcloud compute addresses create rivet-workspace-ip --addresses=136.113.136.145 --region=us-central1
# 2. Check grants; make an account with no roles (https://docs.cloud.google.com/iam/docs/service-accounts-create). "If the
#    service account has no IAM roles, then no resources can be accessed" (https://docs.cloud.google.com/compute/docs/access/service-accounts).
#    If the Ops Agent stays, give that account only roles/logging.logWriter and roles/monitoring.metricWriter.
gcloud projects get-iam-policy PROJECT_ID
gcloud iam service-accounts create rivet-workspace-vm --description="Rivet workspace VM, no roles" --display-name="rivet-workspace-vm"
# 3. One stop: swap the account, turn on Secure Boot (https://docs.cloud.google.com/compute/docs/instances/change-service-account,
#    https://docs.cloud.google.com/compute/shielded-vm/docs/modifying-shielded-vm). Whether OS Config, which is on, then
#    needs roles on the new account is unverified.
gcloud compute instances stop instance-20261029-122759 --zone=us-central1-a
gcloud compute instances set-service-account instance-20261029-122759 --zone=us-central1-a --service-account=rivet-workspace-vm@PROJECT_ID.iam.gserviceaccount.com --scopes=cloud-platform
gcloud compute instances update instance-20261029-122759 --zone=us-central1-a --shielded-secure-boot
gcloud compute instances start instance-20261029-122759 --zone=us-central1-a
# 4. Admin access only by OS Login with 2FA over IAP (https://docs.cloud.google.com/compute/docs/oslogin/set-up-oslogin,
#    https://docs.cloud.google.com/compute/docs/connect/restrict-ssh-keys, https://docs.cloud.google.com/iap/docs/using-tcp-forwarding).
#    Also grant OWNER_EMAIL roles/iam.serviceAccountUser on rivet-workspace-vm. Test SSH over IAP before deleting the
#    open rules, and delete only the default rules the list shows.
gcloud compute instances add-metadata instance-20261029-122759 --zone=us-central1-a --metadata enable-oslogin=TRUE
gcloud compute instances add-metadata instance-20261029-122759 --zone=us-central1-a --metadata enable-oslogin-2fa=TRUE
gcloud compute instances add-metadata instance-20261029-122759 --zone=us-central1-a --metadata block-project-ssh-keys=TRUE
gcloud compute firewall-rules create allow-ssh-ingress-from-iap --direction=INGRESS --action=allow --rules=tcp:22 --source-ranges=35.235.240.0/20
gcloud projects add-iam-policy-binding PROJECT_ID --member=user:OWNER_EMAIL --role=roles/iap.tunnelResourceAccessor
gcloud projects add-iam-policy-binding PROJECT_ID --member=user:OWNER_EMAIL --role=roles/compute.osAdminLogin
gcloud compute ssh instance-20261029-122759 --zone=us-central1-a --tunnel-through-iap
gcloud compute firewall-rules list --filter network=default --sort-by priority
gcloud compute firewall-rules delete default-allow-ssh
gcloud compute firewall-rules delete default-allow-rdp
# 5. Close the web ports until the proxy is ready (https://docs.cloud.google.com/vpc/docs/add-remove-network-tags).
gcloud compute instances remove-tags instance-20261029-122759 --zone=us-central1-a --tags http-server,https-server
# 6. A data disk that outlives the VM (200GB is a placeholder), snapshots, deletion protection
#    (https://docs.cloud.google.com/compute/docs/disks/add-hyperdisk, https://docs.cloud.google.com/compute/docs/disks/scheduled-snapshots,
#    https://docs.cloud.google.com/compute/docs/instances/preventing-accidental-vm-deletion).
gcloud compute disks create rivet-data --zone=us-central1-a --size=200GB --type=hyperdisk-balanced
gcloud compute instances attach-disk instance-20261029-122759 --zone=us-central1-a --disk rivet-data --device-name=rivet-data --mode rw
gcloud compute instances set-disk-auto-delete instance-20261029-122759 --zone=us-central1-a --no-auto-delete --disk=rivet-data
gcloud compute resource-policies create snapshot-schedule rivet-data-daily --max-retention-days 14 --start-time 10:00 --daily-schedule --region us-central1 --on-source-disk-delete keep-auto-snapshots
gcloud compute disks add-resource-policies rivet-data --resource-policies rivet-data-daily --zone us-central1-a
gcloud compute instances update instance-20261029-122759 --zone=us-central1-a --deletion-protection
# 7. After a Policy Simulator check, take Editor from the default account; block future grants (needs an organization)
#    (https://docs.cloud.google.com/iam/docs/granting-changing-revoking-access, https://docs.cloud.google.com/organization-policy/restrict-service-accounts).
gcloud projects remove-iam-policy-binding PROJECT_ID --member=serviceAccount:964661841583-compute@developer.gserviceaccount.com --role=roles/editor
gcloud resource-manager org-policies enable-enforce --organization 'ORGANIZATION_ID' iam.automaticIamGrantsForDefaultServiceAccounts
```

### 5.2 Then build the workspace host

- **One gVisor container per user** (https://gvisor.dev/docs/user_guide/install/): its own bridge, no published ports,
  hard limits (https://docs.docker.com/engine/containers/resource_constraints/), a home under its own XFS quota
  (https://man7.org/linux/man-pages/man8/xfs_quota.8.html); never `--privileged`, the Docker socket or host networking.
  In `DOCKER-USER`, which Docker checks first (https://docs.docker.com/engine/network/firewall-iptables/), drop the
  metadata server, private ranges and host ports; the first rule, a construction to test, is
  `iptables -I DOCKER-USER -d 169.254.169.254 -p tcp -m multiport --dports 80,443 -j DROP`.
- **The editor and desktop have no authentication by default** (https://github.com/gitpod-io/openvscode-server,
  https://docs.linuxserver.io/selkies/user-guide/security/), so they sit behind Caddy's `forward_auth`
  (https://caddyserver.com/docs/caddyfile/directives/forward_auth) with one wildcard certificate per VM (Let's Encrypt
  issues 50 per registered domain every 7 days, https://letsencrypt.org/docs/rate-limits/). The phone opens a 60-second,
  single-use ticket from the brain in a Custom Tab; each user has their own hostnames. The brain drives the VM's
  workspace manager, the only Docker client, over mutual TLS. Prefer code-server (image of 2026-10-29) to OpenVSCode
  (newest release 1.105.1, 2025-10-23): https://hub.docker.com/v2/repositories/codercom/code-server/tags,
  https://hub.docker.com/v2/repositories/gitpod/openvscode-server/tags.
- **Before any user:** from A's workspace, the metadata token, 10.128.0.3, the host's ports and B's container all fail
  while DNS and HTTPS work. Measure 1, 2, 4, 6, 8 … scripted workspaces; the highest count with no OOM kills and steady
  editor latency, minus one, is the cap (derived).

### 5.3 Its role and limits

- **Role:** the workspace host (task 2) for the owner now and the first ~10 users once sign-in exists (§7 step 2). It
  holds workspace homes only: no brain, no user database, no platform keys.
- **Capacity** (derived; unverified under gVisor): an editor needs about 2 GB and 2 cores
  (https://code.visualstudio.com/docs/remote/ssh) and a desktop 2.7 GB and 2 cores
  (https://docs.kasm.com/docs/explanations/system-requirements), so 1-7 full workspaces fit, by CPU oversubscription
  (https://docs.kasm.com/docs/explanations/sizing-operations), or 3-14 editors alone. 200 GB of homes at 10 GB is 20 users.
- **One machine in one zone,** so no failover; latency from the brain (sjc) to it is unmeasured.
- **No Confidential VM:** Intel TDX "isn't available on Emerald Rapids processors"
  (https://docs.cloud.google.com/compute/docs/general-purpose-machines), and Debian 13 lists no TDX support
  (https://docs.cloud.google.com/compute/docs/images/os-details).
- **gVisor:** no KVM inside, so no emulators or nested VMs, and "The only real way to know if it will work is to try"
  (https://gvisor.dev/docs/user_guide/compatibility/).

## 6. Decisions for the owner

1. **Sign-in provider.** *Recommend:* Sign in with Google through Credential Manager, on Firebase Authentication now and
   Identity Platform before 100 users. *Why:* the server verifies ID tokens itself
   (https://firebase.google.com/docs/auth/admin/verify-id-tokens); Identity Platform adds MFA, blocking functions and a
   99.95% SLA with no app change (https://docs.cloud.google.com/identity-platform/docs/product-comparison); same cloud as the VM.
2. **Where user data lives** (reopens task 6). *Recommend:* Cloud SQL for PostgreSQL with row-level security and
   per-user encryption keys; no user data in Telnyx KV. *Why:* KV has "No transactions or compare-and-swap" and last-write-wins
   writers (https://developers.telnyx.com/public/llms/storage/kv-full.txt), while Rivet's fence works only inside one
   process (`storage/fenced.py:33-55`); through Horizon every call's arguments and results are logged by default and
   kept indefinitely (https://docs.horizon.prefect.io/security/data-handling.md); a shared namespace isolates users
   only in code, and the SDK's memory search reads every key (strands-harness-sdk-python,
   `strands-py/src/strands/storage/search/keyword.py:82-83`).
3. **Numbers and agents: ours or bring-your-own.** *Recommend:* Rivet provides both. One locked-down pair of agents
   serves everyone, personalized per conversation "without creating multiple agents"
   (https://elevenlabs.io/docs/eleven-agents/customization/personalization/dynamic-variables). For the first 10,
   numbers on the owner's Telnyx account, one assistant per user, every lookup scoped to the user's number (isolation in
   code only, acceptable for 10 known users); before 100, one Telnyx managed account per user, with its own
   organization, balance and API keys, on a commit plan from $1,000 a month
   (https://support.telnyx.com/en/articles/4951492-managed-accounts). *Why not BYO:* each user would need a
   Telnyx account, and Telnyx allowed 5 token checks per 60 s per client in a live test
   (`OubliTelnyxMCP/docs/architecture/DECISIONS_2026-10-08_live_oauth.md`; per client or per token is unverified).
4. **Email.** *Recommend:* the Gmail API over OAuth for everyone but the owner; start Google's verification now. *Why:*
   app passwords "aren't recommended", need 2-Step Verification and are revoked when the password changes
   (https://support.google.com/accounts/answer/185833), and each opens a whole account (derived). OAuth IMAP and SMTP
   need the restricted `https://mail.google.com/` (https://support.google.com/cloud/answer/13464325); the Gmail API has
   narrower scopes (https://developers.google.com/workspace/gmail/api/auth/scopes). Restricted scopes need a yearly CASA
   assessment (https://support.google.com/cloud/answer/13465431), and verification "might require several months"
   (https://support.google.com/cloud/answer/7454865). `email_tools.py` (IMAP and SMTP today) needs a rewrite.
5. **ElevenLabs plan.** *Recommend:* voice concurrency that covers the users talking at once (Creator's 10 covers 10
   users, T1); Business before 100; Enterprise talks before 1,000. *Why:* concurrency is per workspace, shared by all
   agents (https://elevenlabs.io/docs/eleven-agents/guides/call-queueing); Zero Retention Mode is for Enterprise
   customers (https://elevenlabs.io/docs/eleven-api/resources/zero-retention-mode), as is data residency
   (https://elevenlabs.io/docs/overview/administration/data-residency). The current plan is unverified. Make the text
   agent text-only: chat-only sessions get a separate pool 25× larger (https://elevenlabs.io/docs/eleven-agents/guides/chat-mode).
6. **One cloud or keep Fly.** *Recommend:* move the brain to Google Cloud in us-central1 when Postgres lands (§7 step
   7), on its own small VM with a least-privilege service account; then retire both Fly apps. *Why:* sign-in, data,
   keys, secrets, audit logs and the workspace VM all live there; on Fly the brain would need workload identity
   federation (Fly support unverified) or a service-account key, which "anyone who possesses" can use
   (https://docs.cloud.google.com/iam/docs/best-practices-service-accounts).

## 7. Order of work

### Up to 10 users, each step small and testable

0. **The owner, this week, no code:**
   - Hand the APK to no one; rotate `RIVET_DEVICE_TOKEN` with `fly secrets set` and rebuild only your own APK. Check:
     the old token gets 401.
   - Lock the agents in ElevenLabs: `enable_auth` on; detach every tool Rivet doesn't use (first `list_conversations`,
     `get_agent_config`, the calendar, create and edit tools); overrides off; `retention_days` set; a non-Anthropic voice
     LLM. The app already uses brain-minted credentials (`gateway.py:571-597`); test one voice and one text session.
   - Harden the VM (§5.1).
   - Turn off Horizon's request and response payload logging for the Telnyx MCP server; both are on by default and the
     switch affects only later requests (https://docs.horizon.prefect.io/security/data-handling.md).
   - Make the six decisions in §6.
1. **No secrets in the APK** (`android/app/build.gradle.kts:19-27`); the app signs in instead. Test: a scan of the
   release dex finds none of the four values.
2. **Sign-in and per-user sessions** (rule 1). Test: user B can't open, resume or answer user A's session, and the old
   shared token is refused.
3. **Per-user storage prefixes and memory namespaces.** Test: the alice and bob memory case shows nothing crossing.
4. **Webhooks and logs:** tokens from a server-only key, none in access logs, bodies capped while read
   (`gateway.py:640-642`). Test: the access-log check finds no token; a token built from the device token fails.
5. **Limits** (rule 5), server caps on task attempts and timeouts, idle sessions unloaded, jittered reconnects. Test:
   over-limit requests get a plain refusal, and memory stays flat across 1,000 idle sessions.
6. **Per-user accounts:** ElevenLabs credentials only for signed-in users, each `conversation_id` recorded against
   them; email, call answering and Telnyx per user (decisions 3, 4); no Brave results in snapshots without storage
   rights: "It is prohibited to retain any and all data received through the Brave Search API"
   (https://api-dashboard.search.brave.com/documentation/resources/help-feedback/index.html.md). Test: cross-user tests.
7. **Postgres with RLS and per-user encryption keys** (decision 2), then the brain on Google Cloud (decision 6). Test: cross-user
   tests under RLS, and a restore drill.
8. **Audit log** by user, and Data Access logs on (rule 6). Test: each approval and tool call appears once, with its user.
9. **Workspaces on the VM** (§5.2): manager, auth service, `workspace_tools.py` (reads `read`, changes `write`, deletes
   `destructive`, commands `unknown` so each asks) and the app's Workspace plate. Test: §5.2's checks, then the cap.
10. **Invite users 2-10** once someone other than the author has reviewed steps 1-9.

### After 10 users

Work down the table one column at a time, and move when a §4 trigger is near:

- **To 100:** Identity Platform; a few pinned brain processes with a shared event bus; a load balancer with Cloud
  Armor; 2-3 workspace VMs as cells; the 100-user contracts.
- **To 1,000:** an autoscaled brain pool behind a thin router; GKE Agent Sandbox workspaces
  (https://docs.cloud.google.com/kubernetes-engine/docs/concepts/machine-learning/agent-sandbox); Enterprise talks.
- **Past 100,000:** cells, a canary cell first, quotas raised months ahead, then regions.

Start now on what takes months: Google's verification (decision 4); user terms carrying Brave's duties, which require
"a written agreement" with each end user (https://api-dashboard.search.brave.com/documentation/resources/terms-of-service);
and an organization for the project, since VPC Service Controls are managed at the organization level by default
(https://docs.cloud.google.com/vpc-service-controls/docs/overview).

Not researched: costs, and network latency between users, the brain and the VM.
