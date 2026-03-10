# OrdaVK Manager

`OrdaVK` — плагин для Paper 1.20.4, который связывает сервер Minecraft и VK-чаты.

## Сборка
```bash
./ordavk/mvnw -f ordavk/pom.xml test
./ordavk/mvnw -f ordavk/pom.xml package
```


## Management модули
- **Management Bridge**: VK manage-команды и command bridge.
- **Staff Management**: роли staff, bootstrap первого администратора, управление правами.
- **Support Desk**: тикеты `/helpop`, `/report`, команды `!list/!info/!close/!r`.
- **Audit & Security**: events-логирование наказаний, dangerous/raw и LP group-команд.
- **Governance**: снятие администраторов и удаление из бесед.
- **Analytics**: базовый фундамент для метрик staff-действий.

## Bootstrap первого администратора
Только из консоли сервера:
```bash
ordavk bootstrap <vk_id> <mc_nick> <role>
```
Пример:
```bash
ordavk bootstrap 1103524939 pommesshooter chief
```

## Reload без рестарта
- `/ordavk reload` (для OP/perm `ordavk.reload`)
- `ordavk reload` (из консоли)

Reload перезагружает конфиг/хранилища и безопасно перезапускает VK long poll без дублирования потоков.

## Что делает плагин
- Принимает управляющие команды из VK-чата `manage`.
- Принимает команды поддержки из VK-чата `support`.
- Отправляет события сервера в VK-чаты режима `events`.
- Создаёт тикеты из игры через `/helpop` и `/report`.
- Умеет хранить ответ саппорта для оффлайн-игрока и доставлять его один раз при входе.

## Режимы VK-чатов
Настраиваются в `config.yml`:
- `manage` — чат управления (команды админов).
- `support` — чат поддержки (работа с тикетами).
- `events` — только лог-сообщения от плагина.
- `ignore` — сообщения из чата игнорируются.

## Команды VK (чат manage)

- `!help`
- `!online`
- `!status`
- `!check <ник>`
- `!kick <ник> <причина>`
- `!mute <ник> <время> <причина>`
- `!ban <ник> [время] <причина>`
- `!admins`
- `!admin info <vk_id>`
- `!admin add <vk_id> <ник> <роль>`
- `!admin set <vk_id> <роль>`
- `!admin remove <vk_id>`
- `!cmd <команда>`

`!admin remove` выполняет одним вызовом:
- проверку прав и иерархии ролей,
- сброс LP-группы (`lp user <nick> parent set default`) при наличии ника,
- попытку удалить пользователя из всех известных VK-бесед,
- удаление записи из `admins.yml` и сохранение,
- итоговый подробный отчёт в manage-чате и событие в events-чате.

## Команды VK (чат support)
- `!list` — список открытых тикетов.
- `!info <id>` — информация по тикету.
- `!r <id> <reply>` — ответ игроку.
  - Если игрок онлайн — отправляет сразу.
  - Если оффлайн — сохраняет и доставляет при следующем входе (один раз).
- `!close <id>` — закрыть тикет.

## Команды в игре
- `/helpop <question>` — создаёт тикет типа `QUESTION`.
- `/report <player> <reason>` — создаёт тикет типа `REPORT`.

Игрок получает подтверждение с номером тикета.

## Файлы данных
- `config.yml`
- `admins.yml`
- `tickets.yml`
- `pending-replies.yml`
- `audit-log.yml`

## Ключевые настройки безопасности
- `vk.cmd-policy.mode`: `whitelist` или `blacklist`
- `vk.cmd-policy.allowed` / `vk.cmd-policy.blocked`
- `vk.cmd-policy.allowed-roles`
- `vk.protected-users`
- `vk.allow-protected-removal`


## Решение ошибки Java 11 / Maven 4 (и Paper SNAPSHOT)
Если видите ошибку:
`Apache Maven 4.x requires Java 17 or newer to run` — это не ошибка кода плагина, а версия JDK в окружении.

### Нужно минимум:
- JDK 17+
- Maven 3.9.x (в проект добавлен `./ordavk/mvnw`)
- `JAVA_HOME` указывает на JDK 17+

Проверка:
```bash
java -version
./ordavk/mvnw -version
```

Обе команды должны показывать Java 17+.

Пример для Linux:
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./ordavk/mvnw -f ordavk/pom.xml clean package
```

После успешной сборки jar будет в:
`ordavk/target/ordavk-1.0.0.jar`


> Почему так: в части окружений Maven 4 некорректно резолвит `paper-api` SNAPSHOT (ошибки вида `Prefix ... NOT allowed` или `403`), поэтому сборка зафиксирована на Maven 3 через wrapper.


## Локализация (RU/EN/KK)
- Поддерживаются языки: `ru`, `en`, `kk`.
- Язык задаётся в `config.yml`:
  - `general.language: ru` (по умолчанию русский)
- Файлы переводов:
  - `src/main/resources/i18n/messages_ru.yml`
  - `src/main/resources/i18n/messages_en.yml`
  - `src/main/resources/i18n/messages_kk.yml`

## Гибкая таблица прав
В `config.yml` есть блок `permissions.command-min-role`, где задаётся минимальная роль для каждой команды.

Пример:
```yml
permissions:
  command-min-role:
    manage.help: helper
    manage.kick: moder
    manage.admin.remove: admin
    manage.cmd: chief
    support.reply: helper
```

Таким образом, права можно менять без изменения кода.
