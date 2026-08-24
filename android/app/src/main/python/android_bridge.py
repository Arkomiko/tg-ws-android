"""
Мост между Kotlin-слоем Android-приложения и ядром прокси.

Ядро (`proxy/`) не изменяется и не дублируется: Gradle-задача `syncPythonCore`
кладёт его в `build/pythonCore`, а Chaquopy подаёт эту папку как источник
Python-кода, поэтому `import proxy` здесь работает как на десктопе.

Здесь же живёт МОБИЛЬНАЯ ПОЛИТИКА ВОССТАНОВЛЕНИЯ. Ядро писалось под десктоп со
стабильной сетью, и его кулдауны там разумны, а на телефоне превращают одну
осечку сети в намертво деградировавший прокси:

  * `ws_blacklist` — множество DC, для которых WebSocket отключён. За сессию
    оно НИКОГДА не очищается. Один раз получили 302 со всех доменов (например,
    в момент переключения LTE→Wi-Fi) — и WS для этого DC мёртв навсегда,
    остаётся только медленный фолбэк.
  * `ip_fail_until` — ЧАС кулдауна после одного таймаута соединения.
  * `dc_fail_until` — минута.
  * `_WsPool.REFILL_BACKOFF_MAX` — до часа между попытками наполнить пул.

Симптом у пользователя выглядит так: приложение живо, порт слушается, а
Telegram бесконечно «ищет прокси», потому что каждое новое соединение уходит
в тупик. Поэтому здесь есть сторож, который эти структуры расхлопывает.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
import threading
import time

_log = logging.getLogger("tg-ws-android")

# --- политика восстановления -------------------------------------------------

WATCHDOG_INTERVAL = 10.0      # как часто сторож смотрит на счётчики
IP_COOLDOWN_CAP = 120.0       # вместо часа из ядра
DC_COOLDOWN_CAP = 30.0        # вместо минуты
BLACKLIST_TTL = 120.0         # WS-чёрный список живёт не дольше двух минут
STUCK_SOFT_S = 30.0           # «соединения идут, данных нет» → мягкое лечение
STUCK_HARD_S = 120.0          # не помогло → полный перезапуск ядра

# Возраст соединения в пуле. В ядре 120 с — разумно для десктопа на витой паре.
# На телефоне маршрут меняется чаще: сокет, убитый сменой сети или подъёмом VPN,
# НЕ помечается закрытым, пока в него не запишут. Пул отдаёт такой «живой» на вид
# сокет, данные уходят в никуда, и клиент молча ждёт. Чем короче срок жизни, тем
# уже окно, в котором это возможно.
POOL_MAX_AGE_MOBILE = 100.0

# Сколько ждать освобождения порта при перезапуске и сколько раз пробовать.
# Наблюдалось: старый event loop не успевал закрыть слушающий сокет, новый
# получал [Errno 98] и прокси умирал насовсем.
BIND_RETRIES = 6
BIND_RETRY_DELAY = 2.0
_EADDRINUSE = (98, 48, 10048)  # Linux, BSD/macOS, Windows

# Сколько ждать завершения потока прокси при остановке. Прежние 6 секунд
# иногда не хватало, а ссылка на поток обнулялась всё равно — после чего
# следующий запуск натыкался на занятый порт.
STOP_JOIN_TIMEOUT = 15.0

# --- состояние ---------------------------------------------------------------

_thread = None
_loop = None
_stop_event = None
_started_at = 0.0
_last_error = ""
_state = "stopped"            # stopped | starting | running | error
_config_json = "{}"
_log_path = None

_watchdog = None
_watchdog_stop = threading.Event()
_blacklist_cleared_at = 0.0
_stuck_since = 0.0
_last_total = 0
_last_down = 0
_recover_soft_count = 0
_recover_hard_count = 0
_last_recovery = ""


def _setup_logging(verbose: bool, log_path=None) -> None:
    """stdout уходит в logcat, файл нужен там, где logcat недоступен.

    На Transsion/Infinix logcat по приложению пуст, поэтому файловый лог —
    единственный способ разобраться в том, что случилось ночью.
    """
    root = logging.getLogger()
    for h in list(root.handlers):
        root.removeHandler(h)
        try:
            h.close()
        except Exception:
            pass

    fmt = logging.Formatter(
        "%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%m-%d %H:%M:%S",
    )

    stream = logging.StreamHandler(sys.stdout)
    stream.setFormatter(fmt)
    root.addHandler(stream)

    if log_path:
        try:
            # Ротация из utils/logging_setup.py: там уже учтено, что
            # RotatingFileHandler молча не ротирует при backupCount == 0.
            from utils.logging_setup import build_log_handler
            fh = build_log_handler(log_path, log_max_mb=2, backups=2)
            fh.setFormatter(fmt)
            root.addHandler(fh)
        except Exception as exc:  # noqa: BLE001
            root.addHandler(stream)
            _log.warning("Не удалось открыть файл лога %s: %r", log_path, exc)

    root.setLevel(logging.DEBUG if verbose else logging.INFO)
    logging.getLogger("asyncio").setLevel(logging.WARNING)


def _apply_mobile_pool_policy() -> None:
    """Урезает срок жизни соединения в пуле, не трогая исходники ядра."""
    from proxy.pool import _WsPool

    if _WsPool.WS_POOL_MAX_AGE > POOL_MAX_AGE_MOBILE:
        _WsPool.WS_POOL_MAX_AGE = POOL_MAX_AGE_MOBILE
        _log.info("Срок жизни соединения в пуле снижен до %.0f с (мобильная политика)",
                  POOL_MAX_AGE_MOBILE)


def _apply_config(cfg: dict) -> None:
    from proxy.config import proxy_config, parse_dc_ip_list, coerce_domain_list

    proxy_config.host = cfg.get("host", "127.0.0.1")
    proxy_config.port = int(cfg.get("port", 1443))
    proxy_config.secret = cfg.get("secret") or os.urandom(16).hex()
    proxy_config.dc_redirects = parse_dc_ip_list(
        cfg.get("dc_ip") or ["2:149.154.167.220", "4:149.154.167.220"]
    )
    proxy_config.buffer_size = max(4, int(cfg.get("buf_kb", 256))) * 1024
    proxy_config.pool_size = max(0, int(cfg.get("pool_size", 4)))
    proxy_config.fallback_cfproxy = bool(cfg.get("cfproxy", True))
    proxy_config.cfproxy_user_domains = coerce_domain_list(
        cfg.get("cfproxy_user_domain") or []
    )
    proxy_config.cfproxy_worker_domains = coerce_domain_list(
        cfg.get("cfproxy_worker_domain") or []
    )
    proxy_config.fake_tls_domain = (cfg.get("fake_tls_domain") or "").strip()
    proxy_config.force_test_dc = bool(cfg.get("force_test_dc", False))
    proxy_config.proxy_protocol = False


# --- лечение -----------------------------------------------------------------

def _clear_failure_caches(reason: str) -> None:
    """Снимает чёрные списки и кулдауны, накопленные ядром."""
    from proxy import tg_ws_proxy as core

    n_bl = len(core.ws_blacklist)
    n_dc = len(core.dc_fail_until)
    n_ip = len(core.ip_fail_until)
    core.ws_blacklist.clear()
    core.dc_fail_until.clear()
    core.ip_fail_until.clear()
    if n_bl or n_dc or n_ip:
        _log.info("Сброшены отказы ядра (%s): ws_blacklist=%d dc=%d ip=%d",
                  reason, n_bl, n_dc, n_ip)


def _cap_cooldowns() -> None:
    """Урезает кулдауны ядра до мобильных значений.

    Ядро ставит час на IP и минуту на DC. На телефоне это слишком долго:
    сеть меняется чаще, чем истекает кулдаун.
    """
    from proxy import tg_ws_proxy as core

    now = time.monotonic()
    for store, cap in ((core.ip_fail_until, IP_COOLDOWN_CAP),
                       (core.dc_fail_until, DC_COOLDOWN_CAP)):
        for key, until in list(store.items()):
            if until - now > cap:
                store[key] = now + cap


async def _repool() -> None:
    from proxy.pool import ws_pool, cf_worker_pool

    ws_pool.reset()
    cf_worker_pool.reset()
    await ws_pool.warmup()
    await cf_worker_pool.warmup()


def _run_on_loop(coro_factory, timeout: float = 10.0) -> bool:
    loop = _loop
    if loop is None or loop.is_closed():
        return False
    try:
        fut = asyncio.run_coroutine_threadsafe(coro_factory(), loop)
        fut.result(timeout=timeout)
        return True
    except Exception as exc:  # noqa: BLE001
        _log.warning("Не удалось выполнить действие в event loop: %r", exc)
        return False


def _soft_recover(reason: str) -> None:
    global _recover_soft_count, _last_recovery
    _recover_soft_count += 1
    _last_recovery = "soft:%s" % reason
    _log.warning("Мягкое восстановление (%s): чистим отказы и пересобираем пул",
                 reason)
    _clear_failure_caches(reason)
    _run_on_loop(_repool)


def on_network_change(description: str = "") -> str:
    """Вызывается из Kotlin при смене сети или подъёме/падении VPN.

    Смена сети рвёт все установленные сокеты. Без этого вызова ядро запомнит
    отказы и будет час обходить WebSocket стороной. Это же событие приходит,
    когда включается или выключается VPN ByeDPI.
    """
    if _state != "running":
        return status_json()
    _soft_recover("network:%s" % (description or "change"))
    return status_json()


# --- сторож ------------------------------------------------------------------

def _watchdog_loop() -> None:
    """Ищет состояние «соединения приходят, а данных нет».

    Именно так выглядит залипание для пользователя: Telegram бесконечно
    пытается подключиться, прокси принимает соединения, но наверх ничего не
    уходит. Счётчики ядра это показывают: connections_total растёт,
    bytes_down стоит.
    """
    global _blacklist_cleared_at, _stuck_since, _last_total, _last_down

    from proxy.stats import stats
    from proxy import tg_ws_proxy as core

    _last_total = stats.connections_total - stats.connections_bad
    _last_down = stats.bytes_down
    _stuck_since = 0.0
    _blacklist_cleared_at = time.monotonic()

    while not _watchdog_stop.wait(WATCHDOG_INTERVAL):
        if _state != "running":
            continue
        try:
            now = time.monotonic()
            _cap_cooldowns()

            # WS-чёрный список в ядре бессрочный. На мобильной сети это
            # недопустимо: даём WebSocket ещё один шанс каждые BLACKLIST_TTL.
            if core.ws_blacklist and now - _blacklist_cleared_at > BLACKLIST_TTL:
                _log.info("Снимаем WS-чёрный список по таймауту: %s",
                          sorted(core.ws_blacklist))
                core.ws_blacklist.clear()
                _blacklist_cleared_at = now
                _run_on_loop(_repool)

            # Из счётчика вычитаем неудачные рукопожатия: клиент со старым
            # секретом долбится в прокси бесконечно, данные при этом не идут —
            # без вычитания это неотличимо от залипания, и сторож зря дёргает
            # восстановления и перезапуски.
            total = stats.connections_total - stats.connections_bad
            down = stats.bytes_down
            new_conns = total > _last_total
            new_data = down > _last_down
            _last_total, _last_down = total, down

            if new_conns and not new_data:
                if _stuck_since == 0.0:
                    _stuck_since = now
                stuck_for = now - _stuck_since
                if stuck_for > STUCK_HARD_S:
                    _hard_restart("залипание %.0f с" % stuck_for)
                    _stuck_since = 0.0
                elif stuck_for > STUCK_SOFT_S:
                    _soft_recover("залипание %.0f с" % stuck_for)
            elif new_data:
                _stuck_since = 0.0
        except Exception as exc:  # noqa: BLE001
            _log.error("Сторож упал: %r", exc, exc_info=True)


def _hard_restart(reason: str) -> None:
    global _recover_hard_count, _last_recovery
    _recover_hard_count += 1
    _last_recovery = "hard:%s" % reason
    _log.warning("Полный перезапуск ядра (%s)", reason)
    cfg = _config_json
    stop()
    time.sleep(1.0)
    start(cfg)


# --- жизненный цикл ----------------------------------------------------------

def _port_of_config() -> int:
    try:
        from proxy.config import proxy_config
        return int(proxy_config.port)
    except Exception:
        return 0


def _thread_main() -> None:
    global _loop, _stop_event, _state, _last_error, _started_at

    from proxy.tg_ws_proxy import _run

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    _loop = loop
    stop_ev = asyncio.Event()
    _stop_event = stop_ev

    try:
        _state = "running"
        _started_at = time.time()

        # Порт может быть ещё занят предыдущим экземпляром: при перезапуске
        # старый event loop иногда не успевает закрыть слушающий сокет.
        # В логе это выглядело как «Прокси упал: [Errno 98] address already
        # in use» — прокси умирал совсем, хотя достаточно было подождать
        # пару секунд. Поэтому на «адрес занят» делаем несколько попыток.
        attempt = 0
        while True:
            try:
                loop.run_until_complete(_run(stop_event=stop_ev))
                break
            except OSError as exc:
                if (exc.errno not in _EADDRINUSE
                        or attempt >= BIND_RETRIES
                        or stop_ev.is_set()):
                    raise
                attempt += 1
                _log.warning("Порт занят, попытка %d из %d через %.0f с",
                             attempt, BIND_RETRIES, BIND_RETRY_DELAY)
                time.sleep(BIND_RETRY_DELAY)
    except Exception as exc:  # noqa: BLE001
        _state = "error"
        # Сырой текст OSError пользователю ничего не говорит; для двух самых
        # частых причин даём внятную формулировку.
        if isinstance(exc, OSError) and exc.errno in _EADDRINUSE:
            _last_error = "Порт %d занят другим приложением" % _port_of_config()
        elif isinstance(exc, OSError) and exc.errno in (13, 1):
            _last_error = "Нет прав на порт %d" % _port_of_config()
        else:
            _last_error = "%s: %s" % (type(exc).__name__, exc)
        _log.error("Прокси упал: %s", _last_error, exc_info=True)
    else:
        _state = "stopped"
    finally:
        try:
            pending = [t for t in asyncio.all_tasks(loop) if not t.done()]
            for t in pending:
                t.cancel()
            if pending:
                loop.run_until_complete(
                    asyncio.gather(*pending, return_exceptions=True))
            loop.run_until_complete(loop.shutdown_asyncgens())
        except Exception:
            pass
        loop.close()
        _loop = None
        _stop_event = None
        if _state == "running":
            _state = "stopped"


def start(config_json: str) -> str:
    """Поднимает прокси в отдельном потоке. Возвращает JSON с состоянием."""
    global _thread, _state, _last_error, _config_json, _log_path, _watchdog

    if _thread is not None and _thread.is_alive():
        # Предыдущий экземпляр ещё догорает после stop(). Даём ему время
        # закрыть слушающий сокет — иначе новый получит «адрес занят».
        if _state in ("stopping", "stopped"):
            _log.info("Ждём завершения предыдущего экземпляра")
            _thread.join(timeout=STOP_JOIN_TIMEOUT)
        if _thread.is_alive():
            return status_json()
        _thread = None

    try:
        cfg = json.loads(config_json) if config_json else {}
    except ValueError as exc:
        _state = "error"
        _last_error = "плохой config_json: %s" % exc
        return status_json()

    _config_json = config_json or "{}"
    _log_path = cfg.get("log_path") or _log_path
    _setup_logging(bool(cfg.get("verbose", True)), _log_path)

    try:
        _apply_config(cfg)
        _apply_mobile_pool_policy()
    except Exception as exc:  # noqa: BLE001
        _state = "error"
        _last_error = "плохая конфигурация: %s" % exc
        _log.error(_last_error)
        return status_json()

    _last_error = ""
    _state = "starting"
    _thread = threading.Thread(target=_thread_main, name="tg-ws-proxy", daemon=True)
    _thread.start()

    for _ in range(60):
        if _state in ("running", "error"):
            break
        time.sleep(0.05)

    if _state == "running" and (_watchdog is None or not _watchdog.is_alive()):
        _watchdog_stop.clear()
        _watchdog = threading.Thread(target=_watchdog_loop,
                                     name="tg-ws-watchdog", daemon=True)
        _watchdog.start()
        _log.info("Сторож запущен (интервал %.0f с)", WATCHDOG_INTERVAL)

    return status_json()


def stop() -> str:
    """Просит прокси остановиться и дожидается завершения потока."""
    global _thread, _state

    _watchdog_stop.set()

    loop, stop_ev = _loop, _stop_event
    if loop is not None and stop_ev is not None:
        try:
            loop.call_soon_threadsafe(stop_ev.set)
        except RuntimeError:
            pass

    th = _thread
    if th is not None:
        th.join(timeout=STOP_JOIN_TIMEOUT)
        if th.is_alive():
            # Ссылку НЕ обнуляем: раньше обнуляли всегда, и следующий start()
            # считал, что предыдущего экземпляра нет, поднимал новый — а порт
            # всё ещё держал старый. В логе это давало [Errno 98].
            _log.warning("Поток прокси не завершился за %.0f с, ссылку сохраняем",
                         STOP_JOIN_TIMEOUT)
            if _state != "error":
                _state = "stopping"
            return status_json()
    _thread = None
    if _state != "error":
        _state = "stopped"
    return status_json()


def is_running() -> bool:
    return _thread is not None and _thread.is_alive() and _state == "running"


def status_json() -> str:
    from proxy.config import proxy_config

    return json.dumps({
        "state": _state,
        "error": _last_error,
        "host": proxy_config.host,
        "port": proxy_config.port,
        "secret": proxy_config.secret,
        "link": "tg://proxy?server=%s&port=%d&secret=dd%s" % (
            proxy_config.host, proxy_config.port, proxy_config.secret),
        "uptime": int(time.time() - _started_at) if is_running() else 0,
        "log_path": _log_path or "",
    })


def stats_json() -> str:
    from proxy.stats import stats
    from proxy import tg_ws_proxy as core

    return json.dumps({
        "total": stats.connections_total,
        "active": stats.connections_active,
        "ws": stats.connections_ws,
        "tcp_fallback": stats.connections_tcp_fallback,
        "cfproxy": stats.connections_cfproxy,
        "bad": stats.connections_bad,
        "ws_errors": stats.ws_errors,
        "bytes_up": stats.bytes_up,
        "bytes_down": stats.bytes_down,
        "pool_hits": stats.pool_hits,
        "pool_misses": stats.pool_misses,
        "uptime": int(time.time() - _started_at) if is_running() else 0,
        "blacklist": sorted(core.ws_blacklist),
        "recover_soft": _recover_soft_count,
        "recover_hard": _recover_hard_count,
        "last_recovery": _last_recovery,
        "stuck": _stuck_since > 0,
    })


def selftest() -> str:
    """Диагностика окружения: доступно ли всё, что нужно ядру."""
    result = {}
    try:
        import proxy
        result["proxy_version"] = proxy.__version__
    except Exception as exc:  # noqa: BLE001
        result["proxy_version"] = "ОШИБКА: %r" % (exc,)
    try:
        from proxy._aes import Cipher, algorithms, modes
        enc = Cipher(algorithms.AES(b"\x00" * 32), modes.CTR(b"\x00" * 16)).encryptor()
        result["aes"] = enc.update(b"\x00" * 16).hex()[:16]
        import cryptography
        result["cryptography"] = cryptography.__version__
    except Exception as exc:  # noqa: BLE001
        result["aes"] = "ОШИБКА: %r" % (exc,)
    try:
        import certifi
        where = certifi.where()
        result["certifi_path"] = where
        result["certifi_readable"] = os.path.isfile(where) and os.path.getsize(where) > 0
    except Exception as exc:  # noqa: BLE001
        result["certifi_path"] = "ОШИБКА: %r" % (exc,)
        result["certifi_readable"] = False
    result["python"] = sys.version.split()[0]
    return json.dumps(result)


def dump_stacks() -> str:
    """Стеки всех потоков Python. Нужно, когда прокси жив, но ничего не делает.

    На Transsion logcat по приложению пуст, а файловый лог в момент залипания
    может оказаться единственным свидетельством — этот дамп кладётся и туда.
    """
    import traceback

    lines = []
    frames = sys._current_frames()
    by_id = {t.ident: t for t in threading.enumerate()}
    for ident, frame in frames.items():
        th = by_id.get(ident)
        name = th.name if th else "?"
        alive = th.is_alive() if th else False
        lines.append("--- поток %s (id=%s, alive=%s)" % (name, ident, alive))
        for entry in traceback.format_stack(frame)[-8:]:
            lines.append("    " + entry.rstrip())
    text = os.linesep.join(lines)
    _log.warning("Дамп стеков:%s%s", os.linesep, text)
    return text


def diagnose() -> str:
    """Полный срез состояния для разбора залипаний."""
    from proxy.stats import stats
    from proxy import tg_ws_proxy as core
    from proxy.pool import _WsPool

    loop = _loop
    return json.dumps({
        "state": _state,
        "error": _last_error,
        "thread_alive": _thread.is_alive() if _thread else False,
        "watchdog_alive": _watchdog.is_alive() if _watchdog else False,
        "loop_running": bool(loop and loop.is_running()),
        "loop_closed": bool(loop and loop.is_closed()),
        "pool_max_age": _WsPool.WS_POOL_MAX_AGE,
        "ws_blacklist": sorted(core.ws_blacklist),
        "dc_fail_until": len(core.dc_fail_until),
        "ip_fail_until": len(core.ip_fail_until),
        "recover_soft": _recover_soft_count,
        "recover_hard": _recover_hard_count,
        "last_recovery": _last_recovery,
        "stuck_since": _stuck_since,
        "counters": {
            "total": stats.connections_total,
            "active": stats.connections_active,
            "ws": stats.connections_ws,
            "tcp_fb": stats.connections_tcp_fallback,
            "cf": stats.connections_cfproxy,
            "bad": stats.connections_bad,
            "ws_errors": stats.ws_errors,
            "up": stats.bytes_up,
            "down": stats.bytes_down,
            "pool_hits": stats.pool_hits,
            "pool_misses": stats.pool_misses,
        },
        "threads": [t.name for t in threading.enumerate()],
    }, ensure_ascii=False)
