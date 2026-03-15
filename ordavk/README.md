# OrdaVK Manager

OrdaVK Manager — VK-native staff management system для Paper-серверов Minecraft (1.20+).

Плагин объединяет:
- **Management Bridge** (управление сервером через VK),
- **Staff Management** (роли/иерархия/админы),
- **Support Desk** (тикеты и ответы игрокам),
- **Audit & Security** (events-лог опасных действий),
- **Governance** (снятие staff + очистка доступов),
- **Notification Relay** (события сервера в VK).

---

## 1) Возможности (актуально)

### Management Bridge (VK manage-чат)
- `!help`
- `!online`
- `!status`
- `!check <nick>`
- `!admins`
- `!admin info <vk_id>`
- `!admin add <vk_id> <nick> <role>`
- `!admin set <vk_id> <role>`
- `!admin remove <vk_id>`
- `!kick <nick> <reason>`
- `!mute <nick> <time> <reason>`
- `!ban <nick> [time] <reason>`
- `!cmd <raw_command>`

### Support Desk
Игровые команды:
- `/helpop <question>`
- `/report <player> <reason>`

VK support-чат:
- `!list`
- `!info <id>`
- `!close <id>`
- `!r <id> <reply>`

Поддерживается offline-ответ:
- если игрок оффлайн, ответ кладётся в `pending-replies.yml`,
- при следующем входе игрок получает ответ **один раз**,
- после доставки запись удаляется.

### Staff Management
- Иерархия ролей: `helper < moder < admin < staff < chief`.
- `!admin add/set/remove` учитывают ролевую иерархию.
- Bootstrap первого администратора через консоль.

### Governance
`!admin remove <vk_id>` выполняет:
1. удаление из `admins.yml` + сохранение,
2. `lp user <nick> parent set default` (если nick есть),
3. `removeUserFromAllChats(targetVkId)` (в любом случае),
4. summary-ответ в manage-чате,
5. отдельный audit/event лог в events-чат.

### Audit & Security
Events-чат получает:
- join/quit события,
- структурированные логи наказаний (`kick`, `tempmute`, `ban`, `tempban`),
- логи LP group-команд (`parent set/add/remove`),
- логи dangerous raw-команд из `!cmd`,
- лог `!admin remove`.

Есть защита от дублей:
- если событие уже залогировано структурированно (например LP set/add/remove), raw-дубль не отправляется.

### Dangerous command recognition
Нормализуются команды с `/` и без `/`:
- `/lp ...` == `lp ...`
- `/luckperms ...` == `luckperms ...`

Минимальный dangerous-list:
- `kick`, `mute`, `tempmute`, `ban`, `tempban`, `pardon`, `unban`,
- `lp`, `luckperms`, `op`, `deop`, `whitelist`,
- `stop`, `restart`, `reload`,
- `minecraft:stop`, `minecraft:reload`, `minecraft:kick`, `minecraft:ban`, `minecraft:pardon`.

---

## 2) Команды плагина (Minecraft)

### `/ordavk` команды

- `/ordavk reload`
- `/ordavk status`
- `/ordavk doctor`
- `/ordavk testvk`

### `/ordavk reload`
Доступ:
- из консоли: `ordavk reload`
- в игре: `/ordavk reload` (permission `ordavk.reload`, по умолчанию OP)

Что делает reload:
- перечитывает `config.yml`,
- перечитывает `admins.yml`, `tickets.yml`, `pending-replies.yml`,
- перечитывает локализацию,
- пересобирает runtime-сервисы,
- останавливает текущий VK long poll,
- запускает новый long poll,
- предотвращает дублирование polling-потоков.

### Bootstrap первого администратора
Только из консоли:
```bash
ordavk bootstrap <vk_id> <mc_nick> <role>
```
Пример:
```bash
ordavk bootstrap 1103524939 pommesshooter chief
```

Поведение:
- создаёт/обновляет запись в `admins.yml`,
- сразу сохраняет изменения,
- роль валидируется (`helper|moder|admin|staff|chief`).

---

## 3) Конфигурация

Главный файл: `src/main/resources/config.yml` (копируется в папку плагина).

Ключевые секции:
- VK токен и group-id,
- список чатов и их режим (`manage/support/events/ignore`),
- command policy (`whitelist/blacklist` + списки),
- protection-настройки,
- таблица минимальных ролей для команд (`permissions.command-min-role`),
- dangerous command settings.

### Режимы чатов
- `manage` — staff/management команды,
- `support` — тикеты/ответы,
- `events` — только аудит и нотификации,
- `ignore` — сообщения игнорируются.

---

## 4) Хранилища данных

Рабочие файлы:
- `admins.yml`
- `tickets.yml`
- `pending-replies.yml`
- `pending-actions.yml`
- `audit-log.yml`
- `config.yml`

Локализация:
- `i18n/messages_ru.yml`
- `i18n/messages_en.yml`
- `i18n/messages_kk.yml`

---

## 5) Сборка и тесты

Из корня репозитория:
```bash
./ordavk/mvnw -f ordavk/pom.xml test
./ordavk/mvnw -f ordavk/pom.xml clean package
```

Если в окружении требуется флаг для резолва репозиториев:
```bash
mvn -Daether.remoteRepositoryFilter.prefixes=false -f ordavk/pom.xml test
mvn -Daether.remoteRepositoryFilter.prefixes=false -f ordavk/pom.xml clean package
```

Готовый jar:
- `ordavk/target/ordavk-1.0.0.jar`

---

## 6) Требования

- Java 17+
- Paper 1.20.4+ (api-version `1.20`, без NMS)
- Валидный VK token + group id
- Настроенные чаты для `manage/events/support`

---

## 7) Краткий сценарий запуска

1. Скопировать jar в `plugins/`.
2. Запустить сервер один раз для генерации конфигов.
3. Заполнить `config.yml` (token, group-id, chat ids).
4. Выполнить bootstrap первого администратора из консоли.
5. Проверить `!help` и `!status` в manage-чате.
6. Проверить `/helpop` или `/report` и команды `!list/!r` в support-чате.
7. Проверить events-чат: наказания, LP-изменения, dangerous raw-команды.

---

## 8) CHANGELOG (offline-safe moderation)

- Добавлена очередь `pending-actions.yml` для offline-safe moderation.
- `!kick` работает только для онлайн-игроков и не ставится в очередь.
- `!mute`, `!unmute`, `!ban`, `!unban` при оффлайне сохраняются в pending-actions и применяются при следующем входе игрока.
- Применение pending-actions идёт по времени создания (FIFO), с защитой от повторного применения в одном join-цикле.
- При ошибках применения действие не теряется: увеличивается счётчик попыток, сохраняется причина, пишется audit/event лог.
- Pending support replies сохраняются сразу и удаляются только после успешной delayed доставки (2–3 сек через `join-delivery-delay-ticks`).
- Добавлены игровые алиасы: `/ac` для `/helpop` и `/rep` для `/report` (логика, cooldown и лимиты общие).


### `/ovk online`

Показывает игроку его онлайн-статистику через провайдер Plan.
Если Plan недоступен — выводится русский fallback: `Статистика онлайна сейчас недоступна.`
