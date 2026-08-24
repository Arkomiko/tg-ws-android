# Сборка из исходников

## Консольный прокси

Ядро — обычный Python-пакет и запускается на любой настольной системе без Android:

```bash
pip install -e .
tg-ws-proxy --port 1443
```

Ключи командной строки перечислены в `proxy/tg_ws_proxy.py`, функция `main`.

## Приложение для Android

Сборка APK описана отдельно — [README.android.md](./README.android.md).

## Тесты ядра

```bash
pip install pytest
pytest tests -q
```

## Десктопное приложение с треем

В этом форке его нет: репозиторий содержит только ядро и Android-приложение.
Версии для Windows, macOS и Linux живут в оригинале —
[Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
