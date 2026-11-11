# NOVA AI

Платформа AI-персонажей для Android с токеновой экономикой (Google Play Billing + AdMob Rewarded с SSV).

## Честный статус проекта

Это **первый срез** (серверное ядро и инфраструктура). Проект **не готов к публикации**.
Ничего из написанного ещё **не компилировалось и не запускалось**: в среде, где создавался код, не было доступа к `npm`/`gradle` и Android SDK. Первая реальная проверка — запуск CI в GitHub Actions.

### Сделано (код написан, не проверен сборкой)
- `backend/prisma/schema.prisma` — все таблицы из ТЗ (+ `UserBlock`, `Setting`), индексы, внешние ключи.
- `backend/src/ledger.ts` — атомарные начисление/списание под `SELECT ... FOR UPDATE`, идемпотентность, запрет отрицательного баланса, три корзины (purchased / ad reward / bonus), возврат токенов за техническую ошибку.
- `backend/src/billing.ts` — серверная проверка покупки через Google Play Developer API, однократное начисление, consume после коммита, обработка отмены и возврата.
- `backend/src/admobSsv.ts` — проверка подписи AdMob SSV (ECDSA/SHA-256), срока действия, ad unit, суммы награды.
- `backend/src/server.ts` — регистрация/логин (Argon2id), короткие access-токены, ротация refresh с отзывом семейства при повторном использовании, rate limiting, кошелёк, история операций, `/v1/billing/verify`, `/v1/ads/status`, `/v1/ads/ssv`, суточный лимит и интервал.
- `docker-compose.yml`, `backend/Dockerfile`, ежедневный `pg_dump`, `.env.example`, CI (`.github/workflows/ci.yml`).

### Не сделано
- Android-приложение (Compose UI, 19 экранов, Billing, AdMob) — каталога `android/` пока нет; job сборки в CI включится, когда появится `android/gradlew`.
- Prisma-миграции (`prisma migrate dev` нужно выполнить с доступом к сети; CHECK-ограничения `>= 0` для балансов добавить в SQL миграции).
- Персонажи, чат со streaming, память, AI Gateway, Art Studio, сообщество, модерация, админ-панель.
- Тесты (unit/integration/security/performance), RTDN-обработчик возвратов (функция `handleVoidedPurchase` есть, эндпоинта нет), политики и документация API.

## Запуск backend локально
```
cp .env.example .env   # заполнить секреты
docker compose up --build
```

## Что нужно от владельца
1. Google Play Console: создать приложение, пять расходуемых продуктов `nova_tokens_100|300|700|1500|3500`, сервисный аккаунт с доступом к API, передать `PLAY_PACKAGE_NAME` и `PLAY_SERVICE_ACCOUNT_JSON`.
2. AdMob: создать rewarded ad unit, включить SSV с URL `https://<домен>/v1/ads/ssv`, задать `ADMOB_REWARDED_AD_UNIT` и награду.
3. Ключ подписи Android: создать keystore и добавить секреты репозитория `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Без них release-подпись не создаётся.
4. Ключи AI-провайдеров (хранятся только на сервере).
5. Выбрать возрастную категорию — по фактическому контенту и правилам площадок.
