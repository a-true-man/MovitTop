# motis-patches/

Patches applied to the upstream `motis-project/motis` checkout before every
build (local scripts and `.github/workflows/build-motis-binary.yml`), so they
survive a clean re-checkout of the pinned `MOTIS_REF` instead of only living
as uncommitted changes in someone's local `movitop_data/motis`.

## android-seccomp-shim.patch

Without this, `libmotis.so` runs fine under `adb shell` (which is how a
quick manual test can look successful) but is killed with `SIGSYS` the
moment a real Android app process runs it — Android's seccomp-bpf filter
for `untrusted_app` processes traps several syscalls modern musl/boost use
(`clone3`, `rseq`, `openat2`, `faccessat2`, `pidfd_open`, `statx`,
`io_uring_*`) instead of returning the plain `-ENOSYS` musl's own fallback
logic expects. The patch installs an early `SIGSYS` handler that fakes that
`-ENOSYS` and lets execution continue normally.

Discovered 2026-09-29: a from-scratch rebuild of the pinned MOTIS commit
(done to fix an unrelated arm64/x86_64 binary version mismatch) dropped this
patch because it had only ever existed as an uncommitted, untracked change
in one developer's local `movitop_data/motis` checkout — `git status` showed
`exe/android_seccomp_shim.h` as untracked and `exe/main.cc` as modified.
`adb shell` runs aren't subject to the same seccomp filter as real app
processes, so the regression only showed up once the rebuilt binary was
actually installed and launched as the app.

## Applying a patch by hand

```bash
git -C movitop_data/motis apply motis-patches/android-seccomp-shim.patch
```

Idempotent check: skip if `movitop_data/motis/exe/android_seccomp_shim.h`
already exists (the build scripts do this automatically).
