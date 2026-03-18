# PROrda (Paper plugin)

Плагин для автоматического VK-контента Minecraft-сервера:
- **Auto Promo** — круговая промо-рассылка по группам из `groups.yml`.
- **Auto SMM** — контент в отдельную SMM-группу по расписанию и игровым событиям.

## Что улучшено
- Полностью async-цепочки для OpenAI/VK.
- Защита от дублей и пустышек.
- Лимиты публикаций в сутки (общие и promo).
- Event cooldown для event-driven постов.
- Потокобезопасное SQLite-хранилище.
- `random_id` в VK `wall.post` для защиты от дублей.
- Команда `/piaro status` для быстрой диагностики.

## Команды
- `/piaro start`
- `/piaro stop`
- `/piaro reload`
- `/piaro status` (в т.ч. показывает `last-stage` для диагностики)

## Конфиги
- `config.yml` — токены, расписание, лимиты, модели, стиль текста.
- `groups.yml` — список VK-групп для круговой рассылки.
- `debug.enabled` — включает подробные debug-логи и фиксацию последнего шага (`debug.last_stage`) в БД.

## База
SQLite `piaro.db`:
- `post_history`
- `topic_history`
- `prompt_log`
- `publication_status`
- `kv_state`

## Gradle сборка
```bash
gradle :orda-vk-plugin:build -x test
```

Если есть wrapper:
```bash
./gradlew :orda-vk-plugin:build -x test
```

Готовый jar:
- `orda-vk-plugin/build/libs/orda-vk-plugin-2.0Pro.jar`
