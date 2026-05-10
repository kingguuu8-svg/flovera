# QEMU VM Boundary Verification - 2026-04-29

## Scope

This record verifies that the first-stage Alpine Linux userspace can boot as a QEMU VM and can be controlled across the VM boundary.

It does not verify Android APK packaging or Android-device execution. This is a development-host QEMU validation step.

## Build Inputs

| Item | Value |
|---|---|
| Rootfs | `artifacts/rootfs/alpine-x86_64`, `artifacts/rootfs/alpine-aarch64` |
| QEMU image | `artifacts/qemu/ai-linux-x86_64.ext4` |
| aarch64 initramfs root | `artifacts/qemu/initramfs/ai-linux-aarch64.cpio.gz` |
| Image size | `512M` for disk image; `48M` for aarch64 initramfs root |
| x86_64 kernel | `/boot/vmlinuz-5.15.0-176-generic` |
| x86_64 initrd | `/boot/initrd.img-5.15.0-176-generic` |
| aarch64 kernel | `artifacts/qemu/kernel/aarch64/boot/vmlinuz-virt` |
| aarch64 modules | `artifacts/qemu/kernel/aarch64/boot/modloop-virt` |
| VM init | `/usr/local/sbin/ai-vm-init` |
| Command bridge | dropbear SSH over QEMU user-mode port forwarding |

## Commands

```sh
bash scripts/build-qemu-image.sh --rootfs artifacts/rootfs/alpine-x86_64 --force
bash scripts/verify-qemu-vm.sh --image artifacts/qemu/ai-linux-x86_64.ext4 --timeout 150

bash scripts/download-alpine-qemu-kernel.sh --arch aarch64 --force
bash scripts/build-qemu-initramfs.sh --arch aarch64 --rootfs artifacts/rootfs/alpine-aarch64 --modloop artifacts/qemu/kernel/aarch64/boot/modloop-virt --force
bash scripts/verify-qemu-vm.sh --arch aarch64 --initramfs-root --initrd artifacts/qemu/initramfs/ai-linux-aarch64.cpio.gz --kernel artifacts/qemu/kernel/aarch64/boot/vmlinuz-virt --timeout 240
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

The same checks pass across the `aarch64` VM boundary.

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

- The `x86_64` VM path uses a development-host Ubuntu kernel and an ext4 disk image to verify the VM boundary quickly.
- The `aarch64` VM path uses Alpine's `virt` kernel and a generated initramfs root because the Alpine netboot initramfs does not directly mount this minimal ext4 rootfs in the current QEMU path.
- The guest rootfs includes `dropbear` only to provide a minimal command bridge for QEMU validation.
- The init script auto-detects the guest network interface because Ubuntu initramfs can rename `eth0` to names such as `ens3`.
- The `aarch64` initramfs includes only the network modules needed for validation instead of the full Alpine `modloop`.
- Alpine's `aarch64` `virt` kernel cannot run DHCP in this setup because `udhcpc` cannot open `AF_PACKET`; the init script falls back to QEMU user-mode networking defaults: `10.0.2.15/24`, gateway `10.0.2.2`, DNS `10.0.2.3`.
