// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/**
 * @title CredentialAnchorRegistry
 * @notice Non-upgradeable Polygon registry for academic-credential commitments.
 *         Never stores names, student numbers, transcripts, IPFS CIDs, or
 *         revocation reasons. The only persistent per-credential fields are
 *         cryptographic commitments, issuer address, timestamp, and status.
 *
 *         digest    = keccak256(UTF-8(SHA-256(compactSignedCredential) hex))
 *         cidDigest = keccak256(UTF-8(IPFS CID))
 *
 *         Credential contents are encrypted off-chain and pinned to the
 *         institution's private/self-hosted IPFS node. The CID is hashed
 *         before anchoring, so the public ledger never reveals it.
 */
contract CredentialAnchorRegistry {
    uint8 public constant STATUS_ACTIVE = 1;
    uint8 public constant STATUS_REVOKED = 2;
    uint8 public constant STATUS_SUPERSEDED = 3;

    struct Anchor {
        bytes32 cidDigest;
        address issuer;
        uint64 issuedAt;
        uint8 status;
    }

    address public owner;
    mapping(address => bool) public issuers;
    mapping(bytes32 => Anchor) public anchors;

    event CredentialAnchored(
        bytes32 indexed digest,
        bytes32 cidDigest,
        address indexed issuer,
        uint64 issuedAt
    );
    event CredentialRevoked(bytes32 indexed digest, uint64 revokedAt);
    event CredentialSuperseded(
        bytes32 indexed oldDigest,
        bytes32 indexed newDigest,
        uint64 supersededAt
    );
    event IssuerUpdated(address indexed issuer, bool allowed);
    event OwnershipTransferred(address indexed previousOwner, address indexed newOwner);

    modifier onlyOwner() {
        require(msg.sender == owner, "not owner");
        _;
    }

    modifier onlyIssuer() {
        require(issuers[msg.sender], "not issuer");
        _;
    }

    constructor() {
        owner = msg.sender;
        issuers[msg.sender] = true;
        emit IssuerUpdated(msg.sender, true);
    }

    function transferOwnership(address newOwner) external onlyOwner {
        require(newOwner != address(0), "zero address");
        emit OwnershipTransferred(owner, newOwner);
        owner = newOwner;
    }

    function setIssuer(address account, bool allowed) external onlyOwner {
        require(account != address(0), "zero address");
        issuers[account] = allowed;
        emit IssuerUpdated(account, allowed);
    }

    /// Anchor a single credential. Digest and CID are commitments only.
    function anchorCredential(bytes32 digest, bytes32 cidDigest) external onlyIssuer {
        require(digest != bytes32(0), "zero digest");
        require(anchors[digest].issuedAt == 0, "already anchored");
        anchors[digest] = Anchor({
            cidDigest: cidDigest,
            issuer: msg.sender,
            issuedAt: uint64(block.timestamp),
            status: STATUS_ACTIVE
        });
        emit CredentialAnchored(digest, cidDigest, msg.sender, uint64(block.timestamp));
    }

    /// Gas-efficient bulk anchoring; revert atomically if any digest is duplicate.
    function anchorBatch(bytes32[] calldata digests, bytes32[] calldata cidDigests)
        external
        onlyIssuer
    {
        require(digests.length == cidDigests.length, "length mismatch");
        require(digests.length > 0 && digests.length <= 100, "batch size 1..100");
        for (uint256 i = 0; i < digests.length; i++) {
            bytes32 d = digests[i];
            require(d != bytes32(0), "zero digest");
            require(anchors[d].issuedAt == 0, "duplicate digest");
            anchors[d] = Anchor({
                cidDigest: cidDigests[i],
                issuer: msg.sender,
                issuedAt: uint64(block.timestamp),
                status: STATUS_ACTIVE
            });
            emit CredentialAnchored(d, cidDigests[i], msg.sender, uint64(block.timestamp));
        }
    }

    /// Status change only; the reason stays in the institution's private audit log.
    function revoke(bytes32 digest) external onlyIssuer {
        Anchor storage a = anchors[digest];
        require(a.issuedAt != 0, "unknown digest");
        require(a.issuer == msg.sender, "not issuing relayer");
        require(a.status == STATUS_ACTIVE, "not active");
        a.status = STATUS_REVOKED;
        emit CredentialRevoked(digest, uint64(block.timestamp));
    }

    /// Mark the old anchor superseded after its replacement has been anchored.
    function markSuperseded(bytes32 oldDigest, bytes32 newDigest) external onlyIssuer {
        Anchor storage a = anchors[oldDigest];
        require(a.issuedAt != 0, "unknown digest");
        require(a.issuer == msg.sender, "not issuing relayer");
        require(a.status == STATUS_ACTIVE, "not active");
        require(anchors[newDigest].issuedAt != 0, "replacement not anchored");
        a.status = STATUS_SUPERSEDED;
        emit CredentialSuperseded(oldDigest, newDigest, uint64(block.timestamp));
    }

    function getAnchor(bytes32 digest)
        external
        view
        returns (bytes32 cidDigest, address issuer, uint64 issuedAt, uint8 status)
    {
        Anchor storage a = anchors[digest];
        return (a.cidDigest, a.issuer, a.issuedAt, a.status);
    }

    function isAnchored(bytes32 digest) external view returns (bool) {
        return anchors[digest].issuedAt != 0;
    }
}
