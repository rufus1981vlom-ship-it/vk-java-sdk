# OrdaVK 2.0 Lite (Paper 1.20.4)

## Lite-этап (что есть сейчас)
- Разделение чатов: `ADMMANAGE`, `EVENTS`, `SUPPORT`, `MODMANAGE`.
- Маршрутизация:
  - `/ac`, `/helpop` → `SUPPORT`
  - `/report`, `/rep` → `MODMANAGE`
- Быстрые тикеты: `support/report` + статусы `OPEN`, `IN_PROGRESS`, `WAITING_PLAYER`, `CLOSED`.
- Команды тикетов: `!tickets`, `!ticket`, `!reply`, `!assign`, `!close`, `!move`, `!r`.
- `!help` по разделам и роли пользователя.
- `!check`, `!check tech`, `!lookup`, `!staffstats`.
- Last seen и онлайн-метрики (today/7d/30d).
- `!admin remove` удаляет из всех бесед, где работает бот.
- Raw policy: whitelist/blacklist + absolute blacklist + bypass для chief-level.
- Pending-ответы для оффлайн игроков с одноразовой доставкой и cleanup.

## Основные команды
### Help
- `!help`
- `!help support`
- `!help mod`
- `!help admin`

### Тикеты
- `!tickets`
- `!ticket <id>`
- `!reply <id> <text>`
- `!assign <id> <vk_id>`
- `!close <id>`
- `!move <id> support|report`
- `!r <id> accepted|checking|needproof|closed`

### Проверка игрока / staff
- `!check <nick>`
- `!check tech <nick>`
- `!lookup <nick>`
- `!staffstats <vk_id|nick>`

### Наказания
- `!mute <nick> <preset|time reason>`
- `!ban <nick> <preset|time reason>`
- `!warn <nick> <preset|reason>`

### Админ и raw
- `!admins`
- `!admin set|level|remove`
- `!rnick`
- `!kick`, `!ban @id`, `!unban`
- `!noname`
- `!cmd <raw command>`

## Конфиг
### Типы чатов
```yaml
vk:
  chats:
    ADMMANAGE: 0
    EVENTS: 0
    SUPPORT: 0
    MODMANAGE: 0
```

### Шаблоны быстрых ответов
```yaml
vk:
  reply-templates:
    accepted: "Принято, уже в работе."
    checking: "Проверяем, дайте пару минут."
    needproof: "Нужны дополнительные доказательства."
    closed: "Кейс закрыт. Спасибо за обращение."
```

### Presets наказаний
```yaml
vk:
  punishment-presets:
    mute:
      flood: "10m Flood"
    ban:
      cheat: "7d Cheat client"
    warn:
      tox: "Toxic behavior"
```

### Retention
```yaml
vk:
  retention:
    command-log-days: 7
    pending-days: 7
    closed-ticket-days: 14
```
