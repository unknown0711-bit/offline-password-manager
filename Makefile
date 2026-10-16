.PHONY: test build install

test:
	./gradlew test lintDebug assembleDebug --offline

build:
	./gradlew assembleDebug

install: build
	adb install -r app/build/outputs/apk/debug/app-debug.apk
