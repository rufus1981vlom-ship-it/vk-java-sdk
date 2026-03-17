# PiarOrda (Paper plugin)

Актуальный плагин для автоматического VK-контента Minecraft-сервера.

## Функции

### 1) Auto Promo
- Генерирует ИИ-промо посты для привлечения игроков.
- Публикует в VK-группы **по кругу** из `groups.yml`.
- Интервал публикации настраивается (`auto-promo.interval-minutes`, по умолчанию 20 мин).
- В посте обязательно указываются сайт и IP сервера.

### 2) Auto SMM
- Пассивно генерирует контент в отдельную SMM-группу.
- Период настраивается (`auto-pr.smm-group.period-hours`, по умолчанию 8 часов).
- Есть дополнительные триггеры (утро, вечер, weekly, event-driven).

## Команды
- `/piaro start` — запустить auto-promo + auto-smm.
- `/piaro stop` — остановить auto-promo + auto-smm.
- `/piaro reload` — перезагрузить конфиг и список групп.

## Конфиги
- `config.yml` — токены, расписание, лимиты, модели, стиль текста.
- `groups.yml` — список VK-групп для круговой рассылки.

## Хранение
SQLite файл `piaro.db`:
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
