# Build notes

Этот репозиторий публикует исходники и патчи, а не готовый универсальный build
environment. Воспроизводимая сборка требует полного Android 16/crDroid дерева,
Android SDK 36, Android NDK для arm64 и Zygisk API.

## Bluetooth stack

1. Checkout `crdroidandroid/android_packages_modules_Bluetooth` на совместимой
   Android 16 ревизии (проверенная база указана в README).
2. Примените `patches/0001-android16-lhdcv5-stack-backport.patch`.
3. Перенесите маленькое изменение `BluetoothCodecType` из соседнего upstream
   patch; на Android 16 его обычно нужно разрешить вручную из-за отличий API.
4. Примените `patches/0002-android16-forward-extended-codec-specific.patch`.
5. Соберите Bluetooth APEX/APK и `libbluetooth_jni.so` в полном дереве прошивки.

Текущий device-модуль использует точечную runtime-подмену только в namespace
`com.android.bluetooth`. Нельзя брать payload от другой сборки: installer и
boot guard намеренно сверяют SHA-256 штатной базы.

## Controller

Контроллер — минимальное Java-приложение без AndroidX. `MainActivity.java`
компилируется против `android.jar` API 36, затем D8 формирует `classes.dex`.
Манифест находится в `controller/apk/AndroidManifest.xml`.

`LhdcControlBridge.java` должен исполняться системным `app_process` с доступом к
Bluetooth framework API. `LhdcControlDaemon.java` принимает только команды
`list`/валидированный `set` через приватные app-owned файлы.

Подпишите APK своим ключом. Никогда не коммитьте JKS, пароли или готовый payload.
`controller/ApkSign.java` — использованный локальный helper на базе Android
`apksig`; ключ и пароли передаются аргументами и в репозиторий не входят.

## Zygisk helper

Соберите `zygisk/lhdcv5_mount.cpp` как arm64 Zygisk-модуль с официальным
`zygisk.hpp`. Пути и build guard сейчас намеренно привязаны к module id
`lhdcv5_kl7` и Bluetooth APEX данной сборки.

## Module package

В ZIP помещаются скрипты из `module/`, собранные controller APK/JAR, Zygisk SO и
device-specific Bluetooth payload. В публичном репозитории бинарники отсутствуют.
Перед распространением обязательно обновите ожидаемые хеши и протестируйте
аварийное отключение модулей на целевом устройстве.

