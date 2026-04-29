# QEMU VM Boundary Verification - 2026-04-29

## Scope

This record verifies that the first-stage Alpine Linux userspace can boot as a QEMU VM and can be controlled across the VM boundary.

It does not verify Android APK packaging or Android-device execution. This is a development-host QEMU validation step.

## Build Inputs

| Item | Value |
|---|---|
| Rootfs | `artifacts/rootfs/alpine-x86_64` |
| QEMU image | `artifacts/qemu/ai-linux-x86_64.ext4` |
| Image size | `512M` |
| Kernel | `/boot/vmlinuz-5.15.0-176-generic` |
| Initrd | `/boot/initrd.img-5.15.0-176-generic` |
| VM init | `/usr/local/sbin/ai-vm-init` |
| Command bridge | dropbear SSH over QEMU user-mode port forwarding |

## Commands

```sh
bash scripts/build-qemu-image.sh --rootfs artifacts/rootfs/alpine-x86_64 --force
bash scripts/verify-qemu-vm.sh --image artifacts/qemu/ai-linux-x86_64.ext4 --timeout 150
```

## Passed Checks

| Check | Result |
|---|---|
| QEMU boots image | PASS |
| SSH command bridge executes commands | PASS |
| Alpine release readable | PASS |
| `apk` package manager | PASS |
| HTTPS access from guest | PASS |
| Python runtime | PASS |
| Git in `/workspace` | PASS |
| Node.js runtime | PASS |
| OpenSSH client | PASS |
| `/workspace` writeability | PASS |
| `ai-env-check` | PASS |
| Guest Python HTTP service reachable from host through QEMU port forwarding | PASS |

## Observed Versions

| Tool | Version |
|---|---|
| Alpine | `3.23.4` |
| Python | `3.12.13` |
| Git | `2.52.0` |
| Node.js | `v24.14.1` |
| OpenSSH client | `10.2p1` |
| curl | `8.17.0` |

## Notes

- The current VM path uses an `x86_64` development-host kernel to verify the VM boundary quickly.
- The guest rootfs includes `dropbear` only to provide a minimal command bridge for QEMU validation.
- The init script auto-detects the guest network interface because Ubuntu initramfs can rename `eth0` to names such as `ens3`.
- The next architecture step is an `aarch64` QEMU path with a target kernel closer to Android devices.
