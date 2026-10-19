# CredentialAnchorRegistry (Polygon / EVM)

Immutable anchor registry for academic credentials. Stores **only** cryptographic
commitments — never plaintext student data:

| Stored on-chain | Meaning |
|---|---|
| `digest` (bytes32, indexed) | `keccak256(sha256hex(compactSignedJwt))` — the credential anchor key |
| `cidDigest` (bytes32) | `keccak256(ipfsCidString)` — commitment to the IPFS metadata envelope |
| `issuedAt` (uint64) | issuance unix timestamp |
| `issuer` (address) | university/registrar signer |
| `status` (uint8) | 1 active · 2 revoked · 3 superseded |

## Compile + deploy

```bash
# Compile (solc 0.8.24+)
solc --optimize --bin --abi contracts/CredentialAnchorRegistry.sol -o contracts/build

# Or use Remix (browser) → Deploy to Polygon Amoy testnet.
# After compiling, deploy with the Kotlin server:
SCC_POLYGON_RPC_URL=https://rpc-amoy.polygon.technology
SCC_ANCHOR_PRIVATE_KEY=0x<deployer-private-key>   # must hold MATIC for gas
./gradlew :server:run --args='deploy-contract'
# → prints the contract address; set it:
SCC_ANCHOR_CONTRACT_ADDRESS=0x...
```

Deploy the **owner** key (which registers university issuer addresses with
`setIssuer`). Each university's signing key in SCC is an EVM address derived
from its Ed25519 key pair only if you register an explicit EVM key; by default
the registrar uses the shared deployer/issuer wallet unless per-university
issuer addresses are registered with `setIssuer`.

## Gas expectations (Polygon)

- `anchorCredential` ≈ 45k gas (~0.0005–0.002 MATIC)
- `anchorBatch` ≈ 35k gas per credential (use for cohort imports)
- `revoke` / `markSuperseded` ≈ 22k gas

## Verification without the app

```bash
# is the digest anchored?
cast call $CONTRACT "isAnchored(bytes32)" 0x$DIGEST --rpc-url $RPC
# full record (cidDigest, issuer, issuedAt, status)
cast call $CONTRACT "getAnchor(bytes32)" 0x$DIGEST --rpc-url $RPC
```

Off-chain, the credential payload + transcript metadata live on a
self-hosted IPFS node; the CID is committed on-chain as `keccak256(cid)`.
