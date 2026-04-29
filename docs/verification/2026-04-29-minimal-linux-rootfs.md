# Minimal Linux Rootfs Verification - 2026-04-29

## Scope

This record verifies the first-stage Alpine minimal Linux userspace on the current development host.

It does not verify Android APK packaging or QEMU VM boot. Those are later stages.

## Build

Command:

```sh
bash scripts/build-alpine-rootfs.sh --arch x86_64 --force
```

Result:

| Item | Value |
|---|---|
| Status | OK |
| Architecture | `x86_64` |
| Alpine branch | `v3.23` |
| Alpine version | `3.23.4` |
| Rootfs output | `artifacts/rootfs/alpine-x86_64` |
| Tarball output | `artifacts/tarballs/alpine-x86_64-ai-linux-rootfs.tar.gz` |
| Installed size | `128M` |
| Compressed tarball size | `48M` |

Packages installed from `rootfs/alpine/packages.txt`:

```text
ca-certificates
curl
python3
git
nodejs
openssh-client
```

## Verification

Command:

```sh
bash scripts/verify-alpine-rootfs.sh --rootfs artifacts/rootfs/alpine-x86_64
```

Passed checks:

| Check | Result |
|---|---|
| Shell command execution | PASS |
| Alpine release readable | PASS |
| `apk` package manager | PASS |
| HTTPS access | PASS |
| Python runtime | PASS |
| Git in `/workspace` | PASS |
| Node.js runtime | PASS |
| OpenSSH client | PASS |
| `/workspace` writeability | PASS |
| `ai-env-check` | PASS |
| Python HTTP service reachable from host | PASS |

Observed versions:

| Tool | Version |
|---|---|
| Python | `3.12.13` |
| Git | `2.52.0` |
| Node.js | `v24.14.1` |
| OpenSSH | `10.2p1` |
| curl | `8.17.0` |

## Decision Impact

The first-stage minimal Linux userspace is viable. The next blocking question is VM boot, not package selection.

Next implementation target:

```text
Create a QEMU boot path for this rootfs, then repeat the same verification through the VM boundary.
```
