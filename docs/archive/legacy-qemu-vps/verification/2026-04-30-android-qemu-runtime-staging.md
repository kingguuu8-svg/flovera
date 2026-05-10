# Android QEMU runtime staging - 2026-04-30

> Superseded by [Android QEMU device verification](2026-04-30-android-qemu-device-verification.md).
> The earlier filesDir execution route was rejected because target API 29+ cannot exec app-writable home directory files; the current route bundles the runtime into APK native libs.

## Scope

This record verifies a computer-side staging path for a Termux-derived Android
`aarch64` QEMU runtime.

It does not prove phone execution yet. The phone was disconnected before this
step.

## Result

Command:

```sh
bash scripts/stage-termux-qemu-runtime.sh
```

Result:

| Item | Value |
|---|---|
| Status | OK |
| Source | Termux `termux-main` mirror |
| Root package | `qemu-system-aarch64-headless` |
| Resolved package count | `88` |
| Staged tree | `artifacts/qemu-runtime/app-local` |
| Staged tree size | `428M` |
| QEMU binary size | `29M` |
| QEMU binary SHA256 | `87cd952d0c44dcd96c0ebb2f2bffc2dc5db8e292de6c5f1fa5075e9a5cf57063` |
| RUNPATH | `$ORIGIN` |
| Missing direct libraries | `none` |

## What Changed During Staging

The original Termux binary had:

```text
RUNPATH /data/data/com.termux/files/usr/lib
```

The staged binary is patched with `patchelf` to:

```text
RUNPATH $ORIGIN
```

This lets the runtime use an app-local layout where the QEMU executable and
its shared libraries sit together in the app's `nativeLibraryDir`.

## Staged Layout

```text
artifacts/qemu-runtime/app-local/
├── bin/
│   └── qemu-system-aarch64
├── lib/
│   └── *.so*
└── share/
    └── qemu/
```

The first failed staging attempt copied all package `share/` files and pulled in
large unrelated manpage trees. The script now copies only `share/qemu`.

## Next Phone Test Inputs

For the next real-device test, push:

```text
artifacts/qemu-runtime/app-local/bin/qemu-system-aarch64
  -> app's resolved nativeLibraryDir path as libqemu-system-aarch64.so

artifacts/qemu-runtime/app-local/lib/*.so*
  -> alongside libqemu-system-aarch64.so in nativeLibraryDir
```

Keep the existing VM inputs:

```text
QEMU_EFI.fd
vmlinuz-virt
ai-linux-aarch64.cpio.gz
id_ed25519
```

Because the binary lives in `nativeLibraryDir`, `$ORIGIN` resolves to that
directory and the loader finds the colocated libraries there.

## Risks

- The direct `NEEDED` library check passes, but QEMU may still load optional
  modules or data files at runtime.
- The staged tree is large (`428M`) because Termux's dependency closure pulls in
  packages outside the minimal QEMU path.
- This is still a temporary verification source. A source-built minimal QEMU
  remains the cleaner long-term route.
- QEMU is GPL-licensed; redistribution must preserve license and source-code
  obligations.
