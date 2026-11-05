# ic98260-pd2405 — CVE-2026-98260 temporary-root runner for vivo X200 Pro (PD2405)
#
# Default build uses zig cc (no NDK needed):
#   pip install ziglang
#   make
#
# NDK build (same flags as the upstream InfinityCore lineage):
#   make ndk CC_NDK=/path/to/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang
#
# Reproducibility (important):
#   The canonical, run-7-tested binary is ic98260-v5:
#   sha256 2349665992649b3c56f71dded872e574c2ca0e288aa9ab6aca3f5c44f4857a9f
#   zig cc embeds the ABSOLUTE build path in the output, so a bitwise-equal
#   sha256 is only reproduced from the original build directory. From any
#   other checkout path you get a functionally identical binary (same
#   source, same flags) with a different hash. Use `make verify` for the
#   path-independent equivalence check (version markers per patch layer);
#   use the shipped asset hash to verify the *tested* bytes
#   (app/src/main/assets/ic98260).

ZIG        ?= python3 -m ziglang
TRIPLE     := aarch64-linux-musl
CFLAGS     := -O2 -std=gnu11 -static -fno-stack-protector -fno-pic -no-pie \
              -Isrc/timer -pthread
LDFLAGS    := -Wl,--gc-sections -Wl,-z,norelro
SRC        := src/timer/timer.c
HDRS       := src/timer/target.h src/timer/targets.h src/timer/kernel_module_state.h
EXPECT_SHA := 2349665992649b3c56f71dded872e574c2ca0e288aa9ab6aca3f5c44f4857a9f

.PHONY: all ndk check verify clean

all: ic98260

ic98260: $(SRC) $(HDRS)
	$(ZIG) cc -target $(TRIPLE) $(CFLAGS) $(SRC) -o $@ $(LDFLAGS)

ndk:
ifndef CC_NDK
	$(error pass CC_NDK=/path/to/aarch64-linux-android29-clang)
endif
	$(CC_NDK) $(CFLAGS) $(SRC) -o ic98260 $(LDFLAGS)

check: ic98260
	@echo "built:    $$(sha256sum ic98260 | cut -d' ' -f1)"
	@echo "v5 (canonical run-7-tested build): $(EXPECT_SHA)"
	@echo "note: hashes match only from the original build path (zig embeds it)"

verify: ic98260
	@echo "size:     $$(wc -c < ic98260)"
	@for m in "phase2 fallback release" "freed on cpu%d" "auto-harvest" "re-fire"; do \
		printf 'marker %-28s %s\n' "$$m" "$$(grep -ac "$$m" ic98260)"; \
	done
	@echo "every marker must be >= 1: that proves v2+v3+v4+v5 layers are in the binary"

clean:
	rm -f ic98260
