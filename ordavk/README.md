# OrdaVK

`OrdaVK` — плагин для Paper 1.20.4, который связывает сервер Minecraft и VK-чаты.

## Сборка
```bash
mvn -f ordavk/pom.xml test
mvn -f ordavk/pom.xml package
```

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

### 1) Информация
- `!help` — краткий ответ бота.
- `!admins` — список VK-админов и их ролей.

### 2) Наказания игроков
- `!kick <player> <reason>`
  - Отправляет в консоль: `kick <player> <reason>`
- `!mute <player> <time> <reason>`
  - Отправляет в консоль: `tempmute <player> <time> <reason>`
- `!ban <player> [time] <reason>`
  - Если `time` имеет формат `10m/2h/7d`, отправляется: `tempban <player> <time> <reason>`
  - Иначе отправляется: `ban <player> <reason>`

### 3) Управление VK-админами
- `!admin remove <vk_id>`
  - Удаляет права VK-админа.
  - **Обязательно** отправляет в консоль команду для связанного ника:
    `lp user <MC_NICK> parent set default`
  - Пример:
    - `!admin remove 142232`
    - если `142232 -> Steve`, будет отправлено:
      `lp user Steve parent set default`

### 4) Raw console bridge
- `!cmd <server command>`
  - Выполняет команду в консоли сервера (с проверкой policy из `config.yml`).

### 5) VK governance
- `!vk kick <vk_id> <reason>`
  - Пытается удалить пользователя из всех известных VK-чатов бота.
  - Возвращает сводку по успешным/неуспешным попыткам.

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


## Решение ошибки Java 11 / Maven 4
Если видите ошибку:
`Apache Maven 4.x requires Java 17 or newer to run` — это не ошибка кода плагина, а версия JDK в окружении.

### Нужно минимум:
- JDK 17+
- `JAVA_HOME` указывает на JDK 17+

Проверка:
```bash
java -version
mvn -version
```

Обе команды должны показывать Java 17+.

Пример для Linux:
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
mvn -f ordavk/pom.xml clean package
```

После успешной сборки jar будет в:
`ordavk/target/ordavk-1.0.0.jar`


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
