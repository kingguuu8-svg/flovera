# Ubuntu Arm64 Cloud Guest Verification

Date: 2026-05-01

## Purpose

Verify the first-stage VPS-like guest path using an official Ubuntu 24.04 arm64 cloud image under host-side QEMU.

This verifies the Linux computer baseline before Android UI integration:

- boot an `aarch64` Linux guest
- reach SSH terminal
- complete cloud-init
- access HTTPS from the guest
- use Python and Git
- write to `/workspace`
- preview a guest HTTP service through QEMU `hostfwd`
- control VM pause/resume through QMP

Node is optional in this verification. If it is not present in the base cloud image, it is left for later guest-side provisioning instead of blocking first boot.

## Inputs

```text
image=artifacts/cloud-images/ubuntu/noble-server-cloudimg-arm64.img
seed=artifacts/qemu/ubuntu/seed/seed.iso
ssh_key=artifacts/qemu/ssh/ai_linux_vm_ed25519
```

The image, seed, SSH key, overlay, serial logs, and reports are generated under `artifacts/` and are not committed.

## Commands

```bash
bash scripts/download-ubuntu-cloud-image.sh
bash scripts/build-ubuntu-nocloud-seed.sh --force
bash scripts/verify-ubuntu-cloud-vm.sh \
  --image artifacts/cloud-images/ubuntu/noble-server-cloudimg-arm64.img \
  --seed artifacts/qemu/ubuntu/seed/seed.iso \
  --timeout 900
```

## Result

PASS.

Report:

```text
artifacts/reports/verify-ubuntu-cloud-vm-20260501T123511Z.txt
```

Observed key lines:

```text
ssh=ok
cloud_init=done
arch=aarch64
Python 3.12.3
git version 2.43.0
node=not-installed(optional)
guest_baseline=ok
http_preview=ok
qmp_pause_resume=ok
terminal-ok
status=ok
```

The verifier retries HTTP preview startup until the forwarded endpoint returns the expected `/workspace/phase1.txt` content.

## Engineering Notes

- Runtime overlay, copied image, copied seed, PID file, and QMP socket are placed in WSL-native `/tmp` during verification.
- Final reports and serial logs are copied back to `artifacts/`.
- Running QEMU disk overlays directly on the Windows-mounted repo path can trigger unstable behavior and should not be the default verification path.
- First boot must avoid installing large packages through cloud-init. Toolchain expansion belongs to a later guest-side provisioning step.

## Scope Boundary

This is a host-side QEMU guest baseline. It does not prove Android terminal UI, APK packaging, or broad device compatibility.
