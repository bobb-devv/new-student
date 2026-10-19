# SCC Anchored — Kotlin academic credentials & student workspace

A Kotlin-first pilot application for university-issued academic credentials, student academic-file containers, and controlled employer verification.

## What is in this repository

| Component | Implementation |
|---|---|
| Student mobile app | Native Android app written in Kotlin + Jetpack Compose. Sign-in, student dashboard, academic-year/semester setup, semester/event file folders, Android document picker, local app-private copy, upload/retry queue, credential wallet and signed VC-JWT display. |
| Server/API | Kotlin 2.0 + Ktor 2.3, Exposed and SQLite. Owns accounts/roles, university directory, imported rosters, signing keys, certificates, container storage, verification requests, audit events and the web pages. |
| Institution web | Server-rendered HTML from Ktor: system-admin dashboard, university/school setup, registrar dashboard, CSV roster import, credential issue/revoke/reissue, and employer-release review. |
| Employer portal | Open `/verify` page. Employer submits contact details, the student certificate number/digest/registration number, and a university. A key/university mismatch is rejected before the registrar queue. Registrar approval releases only selected fields to a private request link (30-day expiry). |
| Credential format | Per-university Ed25519 key; W3C VC Data Model 2.0-shaped object carried in a compact EdDSA JWT. Private key and signed credential payload are AES-256-GCM encrypted in the database. |
| Decentralized storage | Self-hosted Kubo/IPFS. Certificate envelope, student files and container manifests are encrypted before pinning. IPFS CIDs stay off the public chain. |
| Blockchain | Non-upgradeable Solidity registry for Polygon/EVM. Stores a hashed credential digest, hashed CID, issuer/relayer address, issuance timestamp and lifecycle status—no plaintext student data or transcript, no IPFS CID, and no revocation reason. |

The system has local-only operation when Polygon or Kubo is not configured. The UI/API shows `local_only`, `failed`, or `not pinned` instead of claiming an external anchor exists.

## Architecture / privacy boundaries

```text
Android Compose app ── HTTPS/LAN ──> Kotlin Ktor API ──> SQLite + encrypted server files
          │                                  │
          └─ app-private imported copy       ├──> private Kubo/IPFS API (encrypted bytes only)
                                             └──> Polygon relayer ──> CredentialAnchorRegistry

Browser ──> Ktor-rendered /admin dashboards and open /verify employer portal
```

- Student account resources and file reads are owner-scoped.
- Imported roster data is institution-scoped. It does not create student login accounts. Wallet matching uses the imported email (with registration number as a fallback).
- The employer portal does **not** disclose records before registrar approval. The unguessable tracking code is a bearer link; anyone holding it can see the approved fields until its 30-day expiry. Treat it as confidential.
- Employer result pages omit IPFS CIDs and encrypted blobs. The registrar selects identity, programme, credential and chain fields at approval.
- IPFS is content-addressed and may become public if the node is peered or the CID leaks. This implementation encrypts content before adding it. Keep the Kubo API private, back up the master key, and restrict access to the server.
- A public chain still exposes commitment hashes, issuer/relayer address, status changes and timestamps. Do not submit personal data or transcript text as a transaction/event string.

## Local requirements

- JDK 17 or newer (JDK 17 is the minimum configured bytecode target)
- Gradle Wrapper is included (`./gradlew` on macOS/Linux, `gradlew.bat` on Windows); it downloads Gradle 8.10.2 on first use
- Android Studio with Android SDK Platform 35 for the mobile app (emulator or device)
- Optional: Docker Compose for local Kubo/IPFS
- Optional: Solidity 0.8.24+ toolchain (Foundry or Remix) and Polygon Amoy test MATIC

**Build verification performed 2026-10-19:** `:server:test`, `:server:installDist`, and `:composeApp:compileDebugKotlin` completed successfully on JDK 17 / Gradle 8.10.2. An HTTP smoke test covered admin and registrar setup, CSV import, credential issue/status/wallet, semester-container file upload/download, key-to-university mismatch rejection, employer request/registrar approval, reissue and revocation. Kubo/IPFS pinning, Polygon RPC transactions, emulator/device installation, Solidity deployment/audit, and production operation were not tested here. No mainnet deployment is assumed.

## Run the Kotlin server (local development)

From the repository root on macOS/Linux (server-only mode skips Android SDK configuration):

```bash
cp .env.example .env
# Edit .env. Generate two different values once, then paste them in:
openssl rand -hex 16  # use as SCC_ADMIN_PASSWORD
openssl rand -hex 32  # use as SCC_CREDENTIAL_MASTER_KEY

set -a
. ./.env
set +a
SCC_SERVER_ONLY=true ./gradlew :server:run
```

On Windows PowerShell, set the corresponding `$env:NAME = 'value'` values from `.env` (especially the admin password and master key), then run `$env:SCC_SERVER_ONLY = 'true'; .\gradlew.bat :server:run`.

The master key is required for encrypted credential/file operations. Keep the same value whenever reopening a database; losing or changing it prevents decryption of existing data. Do not generate a new master key on each launch. For a local pilot without Kubo, leave IPFS unstarted: issuance/file storage remains local and the UI reports missing pins/local-only anchors. To enable Kubo locally, run `docker compose up -d ipfs` in another terminal before starting the server.

- API health: `http://localhost:4180/api/health`
- API route summary: `http://localhost:4180/api/v1`
- Institution dashboard: `http://localhost:4180/admin`
- Employer verifier: `http://localhost:4180/verify`
- SQLite: `data/scc-anchored.db`
- Server file storage: `storage/` (uploaded bytes are AES-GCM encrypted)

The system-admin account is created from `SCC_ADMIN_EMAIL` and `SCC_ADMIN_PASSWORD` only if there is no system-admin row yet. `SCC_CREDENTIAL_MASTER_KEY` is required for credential issuance and encrypted file operations; in production the server also fails fast unless the key is 64 hexadecimal characters and the admin password is at least 12 characters. Use a strong development password too, and do not expose the development server publicly. TLS termination, firewalling and production hosting still need to be configured separately.

### Run backend + Kubo with Docker Compose

```bash
cp .env.example .env
# Edit .env: choose a private admin password and set SCC_CREDENTIAL_MASTER_KEY
# to the output of `openssl rand -hex 32` (exactly 64 hex characters).
docker compose up -d --build
docker compose logs -f server
```

The server is at `http://localhost:4180`; the browser dashboards and employer portal use the same paths above. Compose keeps the Kubo API on host loopback (`127.0.0.1:5001`) and the server reaches it over the private Compose network. `docker compose down` stops the services; the host `data/`, `storage/`, and named IPFS volumes retain data.

### IPFS: self-hosted Kubo

Start Kubo locally with `docker compose up -d ipfs` or install Kubo directly. The app calls the node's HTTP API at port 5001; do not expose that API to the public internet. Kubo's gateway is normally port 8080.

To test without Kubo, leave the default API address and run the backend; certificate issuance is recorded locally even if the IPFS node is down, with the CID/anchor state shown as missing or local-only. For a physical Android device, configure a reachable Ktor URL and a reachable gateway URL; the emulator's `10.0.2.2` only addresses the host computer from the standard Android emulator.

### Native Android app

Open the **repository root** in Android Studio, set up Android SDK Platform 35 if prompted, wait for Gradle sync, select the `composeApp` run configuration and run on an emulator/device. Start the Ktor server first. The sign-in screen's **Server address** field lets you change the API URL.

To build and install a debug APK from a terminal:

```bash
./gradlew :composeApp:assembleDebug
# Optional, with an emulator/device attached:
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

- Emulator default API: `http://10.0.2.2:4180`
- Physical phone: set the server computer's reachable LAN URL such as `http://192.168.1.20:4180`, or an HTTPS host, on the sign-in screen / Settings.
- The token is kept in Android Keystore-backed encrypted preferences; selected files are copied to app-private storage before upload.
- Upload size limit is 50 MiB. Supported file suffixes: PDF, PPT/PPTX, DOC/DOCX, XLS/XLSX, CSV, TXT, JPG/JPEG, PNG and Markdown (`.md`). Files are uploaded encrypted to the server; optional IPFS pinning is configurable per import.
- Cleartext HTTP is enabled in the development manifest for LAN/emulator testing. Use HTTPS and disable cleartext in release builds.

## First setup / functional test path

### 1. Create a university and school

1. Sign into `/admin` with the configured system-admin account.
2. Open **Universities** and add a university.
3. Open the university dashboard; add a school and a registration prefix.
4. Create a registrar account for that university. Registrar accounts are institution-scoped.

### 2. Import roster and issue a credential

1. Sign in as the registrar at `/admin/login`.
2. Open the university dashboard and paste/import a CSV such as:

```csv
full_name,email,registration_number,programme,school_name
Amina Njeri,amina@example.edu,CS/2026/001,BSc Computer Science,School of Computing
```

Required headers: `full_name` (or `name`) and `email`. Optional: `external_id`, `registration_number`/`registration`/`student_id`, `programme`/`program`, `school_name`/`school`. Import is additive; duplicate email, registration or external ID rows are skipped. CSV maximum: 2 MB / 2,000 data rows. The web form supports preview and commit.
3. The registrar selects the imported student and issues a credential. The wallet is linked primarily by email, so sign the student up using the exact imported address.
4. The service creates a per-university Ed25519 key (master-key encrypted), signs a compact VC-JWT, stores the encrypted payload and pins an encrypted envelope to Kubo/IPFS when available.
5. If Polygon configuration is active, the backend anchors `keccak256(SHA-256(signed VC-JWT hex))` and `keccak256(IPFS CID string)` to the registry. Otherwise the UI displays `local_only`.

### 3. Student folders and wallet

1. Register/sign in from the Android app using the roster email.
2. Create an academic year and semester if desired.
3. Open **Folders**, create a semester or event container, select it, and import a file using Android's document picker.
4. A private app copy is saved first. Uploads are encrypted on the server and before any IPFS pin; if the network/server is offline, the app records a queued upload and offers retry.
5. Open **Wallet** to refresh credentials. Reveal/copy the active VC-JWT only when sharing with a trusted verifier. Wallet retrieval is online.

### 4. Employer request → registrar release

1. Employer opens `/verify`, provides their name, organisation, email, certificate number/digest/registration key and the matching university.
2. Exact certificate ID or 64-character SHA-256 digest is accepted. Registration number is resolved inside the selected university.
3. If the key is not for the selected university, the request is rejected. If it matches, a pending release request appears on that registrar's `/admin/registrar` dashboard.
4. Registrar approves or rejects, selecting identity/programme/credential/chain fields. The employer's original result link updates with only approved data. The portal does not email the link; the employer receives it immediately after submitting the request.

## Polygon / Solidity contract

Source: `contracts/CredentialAnchorRegistry.sol` (Solidity 0.8.24+). It is non-upgradeable; lifecycle status is mutable through issuer-authorized transactions. `setIssuer` is owner-gated. The Ktor signing identity (Ed25519) is separate from the EVM relayer key (secp256k1): the deployer/contract owner must authorize the relayer address once via `setIssuer(relayer, true)`.

Compile/deploy to **Polygon Amoy first** with Foundry or Remix. Do not use mainnet until an institution has reviewed privacy, keys, gas, backups and release procedures.

Example Foundry workflow:

```bash
forge build --contracts contracts
# Deploy with a funded Amoy deployer through your own secure script or Remix.
# Record the contract address, and call setIssuer(<Ktor relayer address>, true).
```

Then start the server with these server-only environment variables:

```bash
export SCC_POLYGON_RPC_URL='https://rpc-amoy.polygon.technology'
export SCC_ANCHOR_PRIVATE_KEY='0x<server-side-authorized-relayer-private-key>'
export SCC_ANCHOR_CONTRACT_ADDRESS='0x<deployed-registry-address>'
export SCC_CHAIN_NETWORK='Polygon Amoy'
```

Never put the RPC signing key in the Android app, browser, source tree, student CSV, or public web settings. The contract ABI is in `contracts/CredentialAnchorRegistry.abi.json`.

### Contract commitment format

- `digest`: `keccak256(UTF-8(lower-case SHA-256 hex digest of compact signed JWT))`
- `cidDigest`: `keccak256(UTF-8(IPFS CID))`; CID is not stored in public contract storage or events
- Status: `1 active`, `2 revoked`, `3 superseded`
- `anchorBatch` supports 1–100 items; use for cohort issuance to amortize transaction overhead
- Revocation reasons remain in the private institutional DB/audit log, not the chain

## API map (v1)

- Auth: `POST /api/v1/auth/register`, `/auth/login`, `/auth/logout`; `GET/PATCH /me`
- Wallet: `GET /api/v1/me/credentials`
- Academic structure: `GET/POST /academic-years`; `POST /academic-years/{yearId}/semesters`; `POST /semesters/{id}/current`; `GET /units`; `POST /semesters/{id}/units`
- Containers: `GET/POST /containers`; `GET/PATCH/DELETE /containers/{id}`; `GET /containers/{id}/files`; multipart `POST /containers/{id}/files`; download/delete subroutes; `POST /containers/{id}/anchor`
- Credential status: public `GET /api/v1/public/credentials/{credentialId}/status`
- Employer: public `POST /api/v1/public/verification-requests`; `GET /api/v1/public/verification-requests/{trackingCode}`
- Institution/admin: `/api/v1/admin/universities`, school/registrar creation, CSV import, credential issue/revoke/reissue/history, verification queue/decision, account status, audit and metadata-only upload view
- API summary: `GET /api/v1/openapi`

Private JSON endpoints use `Authorization: Bearer <token>`. Tokens last seven days. Browser-admin sessions use an HttpOnly SameSite=Lax cookie and a synchronizer-style CSRF token for state-changing forms.

## Verification checklist

On a JDK 17+ machine (server-only mode does not need the Android SDK):

```bash
SCC_SERVER_ONLY=true ./gradlew :server:test :server:installDist
curl -i http://localhost:4180/api/health
```

For the mobile module, install Android SDK Platform 35 and run `./gradlew :composeApp:compileDebugKotlin` or `./gradlew :composeApp:assembleDebug`; alternatively open the root project in Android Studio and run `composeApp` on an emulator/device.

Expected core test coverage: CSV quote/blank-line parsing, SHA-256 shape/determinism, Ed25519 sign/verify, 32-byte contract-key generation and CID commitment.

Manual smoke path: seed admin → create university/school/registrar → import CSV preview then commit → issue → inspect wallet on student account with imported email → create semester/event folder → upload/download file and compare SHA-256 → verify the encrypted IPFS CID → employer request with correct university → see pending request in registrar dashboard → approve selected fields → refresh employer link → reject a mismatched university/key pair.

## Known limitations / not production-certified

- The checked build and server smoke flow passed, but this is not production-certified. Still required before real records: Kubo pin/retrieval tests, Polygon Amoy transaction tests, Solidity compilation/security review, Android emulator/device acceptance tests, operational hardening, and backup/restore exercises.
- W3C-compatible data structures are used, but the custom status extension, wallet presentation flow, issuer DID publication and independent VC profile/interoperability have not been externally certified. Treat this as a pilot VC-JWT implementation, not a formal W3C conformance claim.
- There is no direct SIS connector, automatic roster sync, email/SMS notification, mobile push notification, password reset, malware scanning, institutional staff approval workflow, or production hosting/monitoring configured.
- The current server implements the core year/semester/unit structure, file containers, credential wallet and dashboards. It does not yet port every optional module documented in the earlier SCC reference edition (for example friend sharing, task/exam management, timetable and performance forms).
- Current local SQLite schema creation is fresh-install oriented; production database migrations and backup/restore automation are not included. Encrypted credential keys/payloads and encrypted files depend on the master key. Losing it prevents decryption; back it up separately from the DB.
- A public chain cannot erase commitments. Hashes and timestamps can still be correlated. Do not put transcript bytes or sensitive claims on-chain.
- The employer tracking link is a bearer secret and only expires after 30 days. There is no email ownership challenge or employer identity verification service in this pilot; registrars should independently review the employer details before approving.
- File type checks are suffix-based and do not scan for malware. Restrict uploads, use HTTPS, consider content scanning and institution retention requirements before real records.
- Android local file copies live in app-private storage (backup is disabled), while the server and IPFS data use the server master-key encryption. Device compromise and server compromise remain risks.

## Environment variables

| Variable | Purpose |
|---|---|
| `PORT` | Ktor port; default `4180` |
| `SCC_ENV` | `development` or `production`; production enforces a non-default admin password and valid master key at startup |
| `SCC_ADMIN_EMAIL` / `SCC_ADMIN_PASSWORD` | Initial system-admin account on an empty database |
| `SCC_DATA_DIR` / `SCC_STORAGE_DIR` | SQLite and server file roots |
| `SCC_CREDENTIAL_MASTER_KEY` | 64 hex chars / 32 random bytes for AES-256-GCM, credential signing-key encryption and student file encryption |
| `SCC_IPFS_API_URL` | Private Kubo API; default `http://127.0.0.1:5001` |
| `SCC_IPFS_GATEWAY_URL` | Gateway used to construct user-facing URLs; default `http://127.0.0.1:8080` |
| `SCC_POLYGON_RPC_URL` | Polygon RPC endpoint; absent means no chain write |
| `SCC_ANCHOR_PRIVATE_KEY` | Server-side EVM relayer private key; must be an authorized issuer in the contract |
| `SCC_ANCHOR_CONTRACT_ADDRESS` | Deployed registry address |
| `SCC_CHAIN_NETWORK` | Display label, e.g. `Polygon Amoy` |
| `SCC_ISSUER_DID_HOST` | Public host used to form university `did:web` identifiers |
| `SCC_BASE_URL` | Public HTTPS origin used in credential status URLs |
| `SCC_MAX_UPLOAD_BYTES` | Server file limit; defaults to 50 MiB, bounded in code |
