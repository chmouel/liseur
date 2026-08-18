SHELL := /bin/bash

GRADLE := ./gradlew
SDK_DIR := $(or $(ANDROID_SDK_ROOT),$(ANDROID_HOME),$(shell sed -n 's/^sdk\.dir=//p' local.properties))
ADB ?= $(if $(wildcard $(SDK_DIR)/platform-tools/adb),$(SDK_DIR)/platform-tools/adb,adb)
EMULATOR ?= $(if $(wildcard $(SDK_DIR)/emulator/emulator),$(SDK_DIR)/emulator/emulator,emulator)
SCRCPY ?= scrcpy
AVD ?= liseur_phone_api36
SERIAL ?= emulator-5554
ADB_TARGET := -s $(SERIAL)
PACKAGE := com.chmouel.liseur
ACTIVITY := $(PACKAGE)/.MainActivity
DEBUG_APK := app/build/outputs/apk/debug/app-debug.apk

.PHONY: help build debug release test lint check clean emulator stop shutdown install run run-bg reset screenshots icon feature-graphic

help:
	@printf '%s\n' \
		'make build             Build the debug APK' \
		'make release           Build the release APK' \
		'make test              Run JVM unit tests' \
		'make lint              Run Android Lint' \
		'make check             Run tests, lint, and debug build' \
		'make emulator          Start the configured Android emulator' \
		'make stop              Stop the configured Android emulator' \
		'make shutdown          Shut down the configured Android emulator' \
		'make install           Build and install the debug APK' \
		'make run               Start the emulator, install, launch, and show it with scrcpy' \
		'make run-bg            Start the emulator, install, and launch without scrcpy' \
		'make reset             Reinstall the app, wipe its storage, and reseed a demo library' \
		'make clean             Remove build outputs' \
		'make screenshots       Capture the UI screenshots' \
		'make icon              Generate the store icon' \
		'make feature-graphic   Generate the store feature graphic' \
		'' \
		'Variables: AVD=liseur_phone_api36 SERIAL=...'

build debug:
	$(GRADLE) assembleDebug

release:
	$(GRADLE) assembleRelease

test:
	$(GRADLE) testDebugUnitTest

lint:
	$(GRADLE) lintDebug

check: test lint build

clean:
	$(GRADLE) clean

emulator:
	@command -v $(EMULATOR) >/dev/null || { printf 'error: emulator is not installed\n' >&2; exit 1; }
	@$(EMULATOR) -list-avds | grep -Fxq '$(AVD)' || { printf 'error: AVD not found: $(AVD)\n' >&2; exit 1; }
	@if $(ADB) $(ADB_TARGET) get-state >/dev/null 2>&1; then \
		printf 'An emulator is already connected%s.\n' "$(if $(SERIAL), as $(SERIAL),)"; \
	else \
		printf 'Starting AVD %s.\n' '$(AVD)'; \
		$(EMULATOR) @$(AVD) -no-window >/tmp/liseur-emulator.log 2>&1 & \
	fi

stop:
	@$(ADB) $(ADB_TARGET) emu kill 2>/dev/null || true

shutdown: stop

install: build
	$(ADB) $(ADB_TARGET) install -r '$(DEBUG_APK)'

run-bg: emulator build
	@$(ADB) $(ADB_TARGET) wait-for-device
	@printf 'Waiting for Android to finish booting.\n'
	@until [ "$$($(ADB) $(ADB_TARGET) shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 1; done
	$(ADB) $(ADB_TARGET) install -r '$(DEBUG_APK)'
	$(ADB) $(ADB_TARGET) shell am start -n '$(ACTIVITY)'

run: run-bg
	@command -v $(SCRCPY) >/dev/null || { printf 'error: scrcpy is not installed\n' >&2; exit 1; }
	$(SCRCPY) -s '$(SERIAL)'

reset: run-bg
	./hack/reset-books -s $(SERIAL)

screenshots:
	./hack/screenshots

icon:
	./hack/icon

feature-graphic:
	./hack/feature-graphic
