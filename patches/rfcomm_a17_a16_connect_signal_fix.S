// AArch64 instruction snippets used by the Android 17 Bluetooth native-library
// compatibility patch. Android 16's BluetoothSocket expects the pre-A17
// 44-byte sock_connect_signal_t (including two 64-bit connection UUID fields),
// while Android 17 emits a 28-byte structure.

.text

.global inline_signal_patch
inline_signal_patch:
  mov w10, #44
  ldr x8, [x23]
  sub x10, x29, #0x24
  mov w2, #44
  str x8, [x25]
  stur w24, [x29, #-0x28]
  stp xzr, xzr, [x10]
  stp xzr, x20, [x10, #16]
  strh w2, [x1]
  cmp w0, #44

.global generic_signal_patch
generic_signal_patch:
  // Bias send_fd so -1 becomes zero. This lets the later CMN + B.EQ pair
  // collapse to one CBZ and frees an instruction for the final size write.
  add w20, w3, #1
  add x10, sp, #4
  mov w8, #44
  strh w8, [sp, #4]
  ldr x8, [x23]
  add x9, sp, #16
  add x1, sp, #4
  mov w0, w19
  mov w2, #44
  strh w2, [x1]
  str x8, [x25]
  str w22, [sp, #12]
  stp xzr, xzr, [x9]
  stp xzr, x21, [x9, #16]
  cbz w20, generic_no_fd
  sub w3, w20, #1
  nop
  nop
generic_no_fd:
  cmp w0, #44
