# Android arm64 `qemu-system-aarch64` binary source - 2026-04-30

## Conclusion

The shortest auditable source for an Android arm64 `qemu-system-aarch64` binary is the Termux package line, using `qemu-system-aarch64-headless` as the preferred temporary verification source.

Why this route:

- It is already an Android/aarch64 build.
- It is reproducible from Termux package infrastructure.
- The headless package avoids extra GUI dependencies that the current spike does not need.
- It is a better fit for the current APK spike than an opaque third-party binary drop.

This is still a temporary verification source, not the final APK-bundled solution.

## Source links

- Termux `qemu-system-aarch64-headless` package index: [termux.librehat mirror](https://termux.librehat.com/apt/termux-main/pool/main/q/qemu-system-aarch64-headless/)
- Termux `qemu-system-aarch64` package index: [termux.librehat mirror](https://termux.librehat.com/apt/termux-x11/pool/main/q/qemu-system-aarch64/)
- Termux package builder docs: [termux-packages wiki](https://github.com/termux/termux-packages/wiki/Package-Builder)
- QEMU build-system docs: [QEMU build system](https://qemu.readthedocs.io/en/v8.2.10/devel/build-system.html)
- QEMU license: [QEMU documentation license page](https://www.qemu.org/docs/master/about/license.html)

## What was verified

### 1) Package source and version

Downloaded the following Termux packages from the Termux mirror:

- `qemu-system-aarch64-headless_1:10.2.1_aarch64.deb`
- `qemu-system-aarch64_1:10.2.1_aarch64.deb`

Observed package metadata:

- Architecture: `aarch64`
- Version: `1:10.2.1`
- Homepage: `https://www.qemu.org`
- Headless package description: generic and open source machine emulator and virtualizer, headless

### 2) ELF identity

`readelf -h` on both binaries shows:

- `ELF64`
- `Machine: AArch64`
- `Type: DYN`
- Android loader path via `INTERP: /system/bin/linker64`

This confirms the artifact is an Android/aarch64 ELF, not a Linux/glibc host binary.

### 3) Dynamic dependency profile

`readelf -d` on the headless binary shows:

- `RUNPATH: /data/data/com.termux/files/usr/lib`
- `NEEDED` libraries include:
  - `libz.so.1`
  - `libgnutls.so`
  - `libpixman-1.so`
  - `libpng16.so`
  - `libjpeg.so.8`
  - `liblzo2.so`
  - `libfdt.so`
  - `libc.so`
  - `libgio-2.0.so.0`
  - `libgobject-2.0.so.0`
  - `libglib-2.0.so.0`
  - `libzstd.so.1`
  - `libslirp.so`
  - `libdw.so.1`
  - `libncursesw.so.6`
  - `libiconv.so`
  - `libgmodule-2.0.so.0`
  - `libspice-server.so`
  - `libusbredirparser.so`
  - `libusb-1.0.so`
  - `libasound.so`
  - `libpulse.so`
  - `libjack.so.0`
  - `libcurl.so`
  - `libssh.so`
  - `libbz2.so.1.0`
  - `libm.so`
  - `libdl.so`

The GUI package adds more display-related dependencies such as `gtk3`, `gdk-pixbuf`, `cairo`, `libX11`, `SDL2`, and `virglrenderer`.

### 4) Size

Binary size on disk:

- headless binary: `30,404,368` bytes
- GUI binary: `30,524,656` bytes

Package sizes:

- headless `.deb`: `3,610,364` bytes
- GUI `.deb`: `3,669,156` bytes

## Why this is not yet a final APK-internal solution

The Termux binary is not self-contained for our app sandbox:

- The runtime search path is hard-coded to `/data/data/com.termux/files/usr/lib`.
- The binary expects Termux-provided shared libraries.
- A normal app private directory is not the same path, so the binary will not run unchanged after copying into our APK's files directory.

For the spike app, the executable is now expected to live in the app's
`nativeLibraryDir` as `libqemu-system-aarch64.so`, with the shared libraries
co-located there and `RUNPATH` set to `$ORIGIN`.

So for the next real-device验收 there are two valid sub-options:

1. Ship the Termux binary plus the Termux-compatible shared library set in the APK-private runtime area and patch the search path.
2. Build a custom Android/aarch64 QEMU binary with a prefix and dependency set that matches our app runtime.

## Recommended route

Use the Termux `qemu-system-aarch64-headless` package as the temporary validation source only.

Why:

- fastest auditable path
- easy to reproduce
- lower risk than an unknown third-party binary
- avoids GUI-only dependencies

For the longer-term APK solution, prefer a controlled build from source or a reproducible Termux package build pipeline with explicit dependency staging.

## Repro commands used

Downloaded and inspected with:

```text
node_repl download to artifacts/qemu-source/termux/
ar t <package>.deb
ar x <package>.deb
tar -xf data.tar.xz
tar -xf control.tar.xz
readelf -h <binary>
readelf -d <binary>
readelf -l <binary>
```

## Next 真机验收 needs

Before the next phone test, the spike still needs:

- `qemu-system-aarch64` binary
- `QEMU_EFI.fd`
- `vmlinuz-virt`
- `ai-linux-aarch64.cpio.gz`
- `id_ed25519`

If the Termux package route is used, the next device run will also need the Termux shared libraries listed in the package control file unless the binary is repointed to an app-local prefix.

Main risk:

- the binary is Android-compatible but not app-sandbox portable as-is
- package dependency closure is large
- GUI package adds more libraries than the current spike requires
- QEMU is GPL-licensed, so any redistributed repackaging must preserve the license and source-code obligations
