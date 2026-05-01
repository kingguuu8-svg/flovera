#!/system/bin/sh
# QEMU is expected at:
# ${APP_NATIVE_LIBRARY_DIR}/libqemu-system-aarch64.so
: "${APP_NATIVE_LIBRARY_DIR:?set APP_NATIVE_LIBRARY_DIR to the app nativeLibraryDir}"
exec "${APP_NATIVE_LIBRARY_DIR}/libqemu-system-aarch64.so" \
  -machine virt,accel=tcg \
  -cpu cortex-a57 \
  -m 768M \
  -smp 1 \
  -no-reboot \
  -bios QEMU_EFI.fd \
  -display none \
  -serial stdio \
  -qmp tcp:127.0.0.1:4444,server=on,wait=off \
  -kernel vmlinuz-virt \
  -initrd ai-linux-aarch64.cpio.gz \
  -append "console=ttyAMA0 earlycon panic=1 rdinit=/usr/local/sbin/ai-vm-init" \
  -netdev user,id=net0,hostfwd=tcp:127.0.0.1:2222-:22 \
  -device virtio-net-pci,netdev=net0,romfile=
