# InfinityCore

**CVE-2026-98260 temporary-root runner for vivo X200 Pro (PD2405)**

This tree is the complete, self-contained source of the PD2405 port: the exploit runner with the full patch chain (v2→v5) already applied, a reproducible build, the run documentation, the provenance patches, and an Android app (Shizuku-based) that stages and runs the binary without typing adb commands.

---

## Target

| | |
|---|---|
| Device | vivo X200 Pro (PD2405), bootloader locked |
| Firmware used in testing | PD2405_A_17.2.11.3.W10 |
| Kernel | `6.6.127-android15-8-g3b723d0e8507-abogki558077879-4k` |
| Shared lineage | X200 Ultra (PD2408) shares the kernel commit — static offsets carry over |
| CVE | 2026-98260 — `exec()` / POSIX CPU-timer cleanup UAF (non-leader `de_thread()` TID swap, freed `k_itimer` timerqueue node dereferenced again) |

## How it works (one paragraph)

A sacrificial child creates 4096 POSIX CPU timers and pauses its exec thread; the parent captures the victim `k_itimer` slab page from the kmem tracepoint, arms an 8 KB socket-buffer spray matched to the order-1 victim page, then releases the exec. The exec/timer-cleanup race frees the victim page while the timerqueue node stays linked; the kernel's one-shot `rb_erase` then writes into whatever reclaimed the page — our sprayed payload — redirecting the write onto the running child's `cred` (zero-writes per field pair, then vendor `vr.ko` de-tag, then `kptr_restrict`). A verify-and-repair loop re-checks every write; the child ends up `uid=0` and execs a root shell on the terminal. The parent parks holding the zombie so nothing reaps the damaged timer state; a reboot is the clean exit.

## Repository layout

```
Makefile                  reproducible build (zig cc default, NDK recipe included)
src/timer.c               the runner, v2+v3+v4+v5 patches applied (≈4000 lines, heavily commented)
src/target.h              PD2405 structure/offset constants
src/targets.h             per-target table
src/kernel_module_state.h vendor module layout used by the de-tag stage
patches/                  provenance: the five patches in apply order against upstream
app/                      Android app (Kotlin + Shizuku) — stages and runs the binary, shows the log
```

## Build

```bash
pip install ziglang
make            # -> ./ic98260
make verify     # path-independent layer check (all version markers >= 1)
```

NDK route: `make ndk CC_NDK=$ANDROID_NDK_HOME/.../aarch64-linux-android29-clang`.

**Reproducibility note:** the canonical, run-7-tested bytes are the v5 binary (`app/src/main/assets/ic98260`, sha256 `2349665992649b3c56f71dded872e574c2ca0e288aa9ab6aca3f5c44f4857a9f`). `zig cc` embeds the *absolute build path* into its output, so a bitwise-equal sha256 only reproduces from the original build directory; from any other checkout you get a functionally identical binary with a different hash. Use `make verify` for the path-independent check (it greps the binary for one version marker per patch layer — v2 `re-fire`, v3 `fallback release`, v4/v5 watcher + `auto-harvest` — all must be present). Never ship a runner whose markers don't all resolve.

## CLI quick start

```bash
# 0. fresh boot, unlocked, settled: >= 15 min uptime, 1-min load < 5
adb push ic98260 /data/local/tmp/ic98260 && adb shell chmod 755 /data/local/tmp/ic98260

# 1. pre-flight — all three must pass
adb shell "ps -A | grep ic98260"        # must be EMPTY (parked process = reboot first)
adb shell sha256sum /data/local/tmp/ic98260
adb shell uptime

# 2. fire (one attempt per boot — never two)
adb shell /data/local/tmp/ic98260 shell 2>&1 | tee runN.log   # NEW filename every run
```

Reading the log:

- `[base] count` ≥ 400 → clean heap; `< 50` → dirty boot, abort and reboot.
- `slide=` must differ from the previous run (per-boot KASLR; identical = same boot = invalid).
- `plant free watch: victim slab freed on cpuN; phase2 released at Xms` → v5 watcher working.
- `[verify] round N: …` → the verify-and-repair loop; `re-fire` lines are it repairing.
- `ROOT SHELL LIVE on this terminal` → type into that same terminal (no prompt is drawn — output goes through `tee`); `exit` lets the parent park.
- `auto-harvest: kallsyms N bytes` → `/data/local/tmp/kallsyms-auto.txt` was dumped by the root child automatically.

End of run: park (normal, immutable) → `adb reboot` clears everything.

## Android app

`app/` is a minimal Android Studio project that does the CLI flow with taps instead of typing:

1. **Shizuku** executes everything as `shell` (uid 2000) — the exploit *requires* the shell SELinux context (tracefs read), so an app-context exploit is impossible; Shizuku is the correct executor (same reason DirtyFrag-style app-context bugs don't apply here).
2. Stages `assets/ic98260` → `/data/local/tmp/ic98260` via a Shizuku `cat` pipe, `chmod 755`.
3. **Pre-flight card**: parked-process check, on-device sha256 vs the pinned hash, uptime.
4. **Run card**: executes the runner, streams its stdout into a log view; "exit the root shell → parent parks" is surfaced in the UI.

Build: open `app/` in Android Studio (AGP 8.5, Kotlin 1.9, minSdk 26), assemble, install, grant Shizuku. The binary ships in `app/src/main/assets/ic98260` — replace it whenever you rebuild the runner (and update `Preflight.EXPECTED_SHA`).

## Version history

| Ver | sha256 (short) | Change |
|---|---|---|
| v2 | `a230a263…` | verify-and-repair loop: re-burn non-zero cred pairs, ≤3 rounds; re-fire offsets `+0x00/+0x08/+0x10/+0x18` |
| v3 | `b8f7d955…` | 150 ms timed phase-2 fallback when the free event is lost (run 4 root cause) |
| v4 | `ee534de4…` | pre-zombie free watcher + pre-fire ring flush (run 6: fallback's 229 ms is past the one-shot `rb_erase` window) |
| v5 | `2349665992649b3c56f71dded872e574c2ca0e288aa9ab6aca3f5c44f4857a9f` | watcher scans **all 8 cpus**, exits at zombie (run 7: the free is not on the trigger cpu's ring); flush on all cpus; auto kallsyms harvest in the root child |

## Implement-on-it pointers

- **Reclaim strategy** lives in `run_plant()` (`src/timer.c`): pre-fire flush → watcher (`free_watch_thread`) → zombie → phase-1/phase-2 socket spray → hit scan. The 150 ms fallback is the last backstop.
- **Saturation alternative** (upstream InfinityCore style, `refs: ylh440104/InfinityCore-Android-CVE-2026-98260`): no free-event watching at all — ~64k AIO-ring pages (~256 MB) saturate the allocator so the unlink double-write lands in attacker memory regardless of timing. Heavier, immune to event loss; a hybrid (precision first, saturation on fallback) is the obvious v6 experiment.
- **One-shot fake cred** (`mode 2` payload in `run_plant`): craft the full fake cred and let the unlink write the pointer into `task->cred` — one race instead of eight zero-write plants. Upstream already proves this primitive on the same CVE.
- **Offsets** for other kernels: `src/target.h`/`src/targets.h`; see `docs/PORTING-PD2405.md` for the extraction procedure.
- **Run history and signatures**: `docs/RUN-RECORD.md` — every failure mode so far has a named root cause; do not re-roll a failed signature without reading it first.

## Safety model

- One attempt per boot. A parked process or a dirty `[base] count` = reboot before anything else.
- Failure modes observed in testing: park (hold zombie, forever, oom-protected), crash → adb drop, panic → forced reboot. All clear on reboot; no persistent change, no brick path observed or designed.
- Never reap a held zombie manually; never run a second instance on the same boot (run 8: panic mid-plant).

## License/Credits

1. **Lineage** — forked from upstream [`kailashrs/pd2454-kernelsu-runner`](https://github.com/kailashrs/pd2454-kernelsu-runner).
2. **Exploit chain** — [`ylh440104/InfinityCore-Android-CVE-2026-98260`](https://github.com/ylh440104/InfinityCore-Android-CVE-2026-98260) (InfinityCore). Architecture ideas referenced from [`mitschud/DirtyFrag`](https://github.com/mitschud/DirtyFrag) (different CVE, not integrated).

> **Note:** Verify upstream licenses before publishing any derivative.
> The PD2405 port, patches v2–v5, documentation, and app in this tree are this project's own work.
