# OrdaVK 2.0 PRO (на базе Lite)

## Что добавлено поверх Lite
- Сохранены все Lite-команды и маршруты (`/ac`, `/helpop`, `/report`, `/rep`).
- Ticket workflow PRO: `!take`, `!unassign`, `!reopen`, `!replyclose`, фильтры `!tickets`, `!ticket full`.
- Templates UX: `!rlist`, placeholders `{player}`, `{ticket}`, `{staff}`.
- Audit commands: `!audit`, `!audit recent`, `!audit <staff>`, `!audit ticket <id>`, `!audit player <nick>`, `!audit punish <nick>`.
- Discipline commands: `!staffnote`, `!staffwarn`, `!staffreprimand`, `!staffdiscipline`, `!staffforgive`.
- Punishment flow: `!unmute`, `!pardon`, `!unwarn`, `!punishlog` + tracking reversals.
- Явные VK-алиасы для chat moderation: `!vkick`, `!vkban`, `!vkunban` (старые команды оставлены).
- Расширен `!lookup`: режимы command/tickets/punish и лимит.
- Расширен `!staffstats`: support/report, replies, first response, punish/reverted, discipline, quality/recommendation.
- SQLite storage для операционных данных: tickets/history, pending, punishments, discipline, audit.

## Команды
### Help
- `!help`
- `!help support`
- `!help mod`
- `!help admin`
- `!help punish`
- `!help audit`

### Tickets
- `!tickets`
- `!tickets mine|open|unassigned|support|report`
- `!ticket <id>`
- `!ticket full <id>` / `!ticket <id> full`
- `!reply <id> <text>`
- `!replyclose <id> <text>`
- `!assign <id> <vk_id>`
- `!take <id>`
- `!unassign <id>`
- `!close <id> [comment]`
- `!reopen <id>`
- `!move <id> support|report [--reason ...]`
- `!r <id> <template>`
- `!rlist`

### Player / Staff
- `!check <nick>`
- `!check tech <nick>`
- `!lookup <nick>`
- `!lookup <nick> 20`
- `!lookup <nick> tickets`
- `!lookup <nick> punish`
- `!staffstats <vk_id|nick> [full]`

### Audit / Discipline
- `!audit`
- `!audit recent`
- `!audit <vk_id|nick>`
- `!audit ticket <id>`
- `!audit player <nick>`
- `!audit punish <nick>`
- `!staffnote <vk_id|nick> <text>`
- `!staffwarn <vk_id|nick> <text>`
- `!staffreprimand <vk_id|nick> <text>`
- `!staffdiscipline <vk_id|nick>`
- `!staffforgive <record_id>`
- `!staffsuspend <vk_id|nick> [reason]`
- `!staffrestore <vk_id|nick> [reason]`
- `!staffstatus <vk_id|nick>`
- `!staffrevokecheck <vk_id|nick>`

### Punishments
- `!mute <nick> <preset|time reason>`
- `!ban <nick> <preset|time reason>`
- `!warn <nick> <preset|reason>`
- `!unmute <nick> [reason]`
- `!pardon <nick> [reason]`
- `!unwarn <nick> [reason]`
- `!punishlog <nick>`

### Admin / VK / Raw
- `!admins`
- `!admin set|level|remove`
- `!rnick`
- `!kick` / `!ban @id` / `!unban`
- `!vkick` / `!vkban` / `!vkunban`
- `!noname`
- `!cmd <raw>`

## Хранение данных
SQLite (`ordavk-pro.db`):
- `tickets`
- `ticket_history`
- `audit_log`
- `punishments`
- `discipline`
- `pending`

YAML:
- config/presets/templates/policies/retention.


## Auto-suspend staff
- 3 активных `REPRIMAND` => автоматический `SUSPENDED`.
- Бот удаляет staff из VK staff-чатов и запускает revoke-команды сервера из `config.yml`.
- Восстановление только вручную: `!staffrestore` (по умолчанию без авто-возврата серверных прав).

- Быстрый контроль: `!staffstatus` и `!staffrevokecheck` для senior staff.
